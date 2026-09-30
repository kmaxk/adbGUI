package ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One parsed log event: display text plus a logcat-style level (V/D/I/W/E/F). */
data class IosLogEntry(val text: String, val level: Char?)

/** Running log processes by udid, so a new stream always replaces the previous one. */
private val logProcesses = ConcurrentHashMap<String, Process>()

/** `idevicesyslog` from libimobiledevice, the only way to stream a physical device's system log. */
val IosService.idevicesyslogPath: String? by lazy {
    val candidates = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "idevicesyslog") } +
        listOf(File("/opt/homebrew/bin/idevicesyslog"), File("/usr/local/bin/idevicesyslog"))
    candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
}

/** Whether live logs can be streamed for [device]. */
fun IosService.canStreamLogs(device: IosDevice): Boolean = device.isSimulator || idevicesyslogPath != null

/**
 * Streams the system log of [device]. Simulators: `simctl spawn log stream --style ndjson`
 * with debug and info levels, filtered at the source by a process/subsystem predicate.
 * Physical devices: `idevicesyslog`, filtered by process name. The process is destroyed
 * when collection stops. Fails with the tool's error text if it exits without output
 * (e.g. an invalid predicate or an unreachable device).
 */
fun IosService.logFlow(device: IosDevice, processFilter: String? = null): Flow<IosLogEntry> = channelFlow {
    val filter = processFilter?.trim()?.takeIf { it.isNotEmpty() }
    val cmd = if (device.isSimulator) {
        buildList {
            addAll(listOf("xcrun", "simctl", "spawn", device.udid, "log", "stream", "--style", "ndjson", "--level", "debug", "--type", "log"))
            if (filter != null) {
                val q = filter.replace("\\", "\\\\").replace("\"", "\\\"")
                add("--predicate"); add("process CONTAINS[c] \"$q\" OR subsystem CONTAINS[c] \"$q\"")
            }
        }
    } else {
        val bin = idevicesyslogPath ?: throw IllegalStateException("idevicesyslog not found")
        buildList {
            addAll(listOf(bin, "-u", device.udid, "--no-colors"))
            if (device.transport == "localNetwork") add("--network")
            if (filter != null) { add("--process"); add(filter) }
        }
    }
    val errFile = File.createTempFile("adbgui-log", ".err")
    val process = runCatching { ProcessBuilder(cmd).redirectError(errFile).start() }
        .onFailure { errFile.delete() }.getOrThrow()
    logProcesses.put(device.udid, process)?.destroyForcibly()
    // Read on a child so cancellation can destroy the process even while readLine() blocks
    launch {
        var emitted = false
        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val entry = (if (device.isSimulator) parseNdjson(line) else parseSyslog(line)) ?: continue
                emitted = true
                send(entry)
            }
        }
        // Cancelled: the exit code is our own destroyForcibly(), not a tool failure
        ensureActive()
        if (!emitted && process.waitFor() != 0) {
            ensureActive()
            // log(1) prints a harmless getpwuid_r warning for the host uid; skip it
            val err = runCatching { errFile.readLines() }.getOrDefault(emptyList())
                .filterNot { it.startsWith("getpwuid_r") }.joinToString("\n").trim()
            throw Exception(err.ifEmpty { "Log stream exited with code ${process.exitValue()}" })
        }
        channel.close()
    }
    awaitClose {
        process.destroyForcibly()
        logProcesses.remove(device.udid, process)
        errFile.delete()
    }
}.flowOn(Dispatchers.IO)

/** "Debug"/"Info"/"Default"/"Error"/"Fault" (log(1) and idevicesyslog) -> logcat level. */
private fun iosLevel(type: String?): Char? = when (type?.lowercase()) {
    "debug" -> 'D'
    "info" -> 'I'
    "default", "notice" -> 'V'
    "warning" -> 'W'
    "error" -> 'E'
    "fault" -> 'F'
    else -> null
}

/**
 * One `log stream --style ndjson` event -> "09-30 16:10:17.930 E/process(pid): [subsystem:category] message",
 * shaped like `logcat -v time`. Non-JSON lines (the "Filtering the log data…" header) are skipped.
 */
private fun IosService.parseNdjson(line: String): IosLogEntry? {
    if (!line.startsWith("{")) return null
    val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
    fun str(key: String) = (o[key] as? JsonPrimitive)?.content.orEmpty()
    // The trailing {"count":…,"finished":…} summary has no message
    if (o["eventMessage"] == null) return null
    val message = str("eventMessage")
    val level = iosLevel(str("messageType"))
    // "2026-09-30 16:10:17.930503+0200" -> "09-30 16:10:17.930"
    val ts = str("timestamp").let { if (it.length >= 23) it.substring(5, 23) else it }
    val process = str("processImagePath").substringAfterLast('/').ifEmpty { "?" }
    val pid = str("processID")
    val subsystem = str("subsystem")
    val category = str("category")
    val scope = when {
        subsystem.isNotEmpty() && category.isNotEmpty() -> "[$subsystem:$category] "
        subsystem.isNotEmpty() -> "[$subsystem] "
        else -> ""
    }
    return IosLogEntry("$ts ${level ?: 'V'}/$process($pid): $scope$message", level)
}

private val SYSLOG_LEVEL = Regex("""<(\w+)>:""")

/** idevicesyslog line ("Sep 30 16:10:17.123456 iPhone proc(Lib)[123] <Notice>: msg"), kept as is. */
private fun parseSyslog(line: String): IosLogEntry? {
    if (line.isBlank() || line.startsWith("[connected") || line.startsWith("[disconnected")) return null
    return IosLogEntry(line, iosLevel(SYSLOG_LEVEL.find(line)?.groupValues?.get(1)))
}
