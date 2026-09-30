package ios

import ios.IosService.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import process.Exec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// --- Screenshot ---

/** PNG bytes of the device screen. Both tools only write to a file, so this goes through a temp file. */
suspend fun IosService.screenshot(device: IosDevice): Result<ByteArray> = withContext(Dispatchers.IO) {
    val tmp = File.createTempFile("adbgui-ios-shot", ".png")
    try {
        val result = if (device.isSimulator) {
            // `screenshot -` writes a file literally named "-" instead of stdout
            simctl("io", device.udid, "screenshot", "--type=png", tmp.absolutePath).toResult().map { }
        } else {
            devicectlJson("device", "capture", "screenshot", "--device", device.udid, "--destination", tmp.absolutePath)
                .map { }
        }
        result.mapCatching {
            val bytes = tmp.readBytes()
            if (bytes.size < 8 || bytes[1] != 'P'.code.toByte() || bytes[2] != 'N'.code.toByte()) {
                throw Exception("No PNG was written")
            }
            bytes
        }
    } finally {
        tmp.delete()
    }
}

// --- Screen recording ---

private class Recording(val process: Process, val file: File, val output: StringBuffer)

private val recordings = ConcurrentHashMap<String, Recording>()

/** Messages about recordings that were saved without the user choosing a place, keyed by udid. */
private val recordingNotices = ConcurrentHashMap<String, String>()
/** Unattended stops still finalizing, keyed by udid; the shutdown hook waits for them. */
private val finishing = ConcurrentHashMap<String, CountDownLatch>()

// Registered when this file is first used. A recorder left running after the app quits would keep
// writing forever, so finalize whatever is still recording into ~/Movies. Quitting disposes the
// Screen tab first, which starts an unattended stop; wait for those too, or the JVM kills them mid-save.
private val shutdownHook = Thread {
    recordings.forEach { (udid, rec) -> runCatching { finishRecording(udid, fallbackRecordingFile(udid, rec.file.extension)) } }
    finishing.values.forEach { it.await(120, TimeUnit.SECONDS) }
}.also { Runtime.getRuntime().addShutdownHook(it) }

/** simctl writes a QuickTime movie, devicectl insists on .mp4. */
fun IosService.recordingExtension(device: IosDevice): String = if (device.isSimulator) "mov" else "mp4"

fun IosService.isRecording(device: IosDevice): Boolean = recordings[device.udid]?.process?.isAlive == true

suspend fun IosService.startRecording(device: IosDevice): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        if (isRecording(device)) throw Exception("Already recording")
        recordings.remove(device.udid)?.file?.parentFile?.deleteRecursively()
        val dir = Files.createTempDirectory("adbgui-ios-rec").toFile()
        val file = File(dir, "recording.${recordingExtension(device)}")
        val args = if (device.isSimulator) {
            listOf("xcrun", "simctl", "io", device.udid, "recordVideo", "--codec=h264", "--force", file.absolutePath)
        } else {
            listOf(
                "xcrun", "devicectl", "device", "capture", "screen-record",
                "--device", device.udid, "--destination", file.absolutePath,
            )
        }
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = StringBuffer()
        Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        }.apply { isDaemon = true }.start()
        // Registered before the start wait, so leaving the screen meanwhile still stops it
        val rec = Recording(process, file, output)
        recordings[device.udid] = rec

        // simctl reports "Recording started" on the first frame; devicectl has no such marker,
        // so for phones only an early exit (locked device, no Developer Mode) counts as failure.
        val deadline = System.currentTimeMillis() + if (device.isSimulator) 10_000 else 2_000
        while (System.currentTimeMillis() < deadline && process.isAlive && !output.contains("Recording started")) {
            Thread.sleep(100)
        }
        if (!process.isAlive) {
            recordings.remove(device.udid, rec)
            dir.deleteRecursively()
            throw Exception(output.toString().lines().lastOrNull { it.isNotBlank() }?.trim() ?: "Recorder exited")
        }
    }
}

/** Stops the recording, waits for the video to be finalized and moves it to [target]. */
suspend fun IosService.stopRecording(device: IosDevice, target: File): Result<String> = withContext(Dispatchers.IO) {
    runCatching { finishRecording(device.udid, target) }
}

/**
 * Stops a recording nobody is going to stop by hand any more (screen left, device switched) and
 * keeps the video in ~/Movies. The message is picked up by [takeRecordingNotice].
 */
suspend fun IosService.stopRecordingUnattended(device: IosDevice): Unit = withContext(Dispatchers.IO) {
    val rec = recordings[device.udid] ?: return@withContext
    val target = fallbackRecordingFile(device.udid, rec.file.extension)
    val done = CountDownLatch(1)
    finishing[device.udid] = done
    try {
        recordingNotices[device.udid] = runCatching { finishRecording(device.udid, target) }.fold(
            onSuccess = { "Recording stopped when you left the Screen tab. Saved: $it" },
            onFailure = { "Recording stopped when you left the Screen tab, but saving failed: ${it.message}" },
        )
    } finally {
        finishing.remove(device.udid, done)
        done.countDown()
    }
}

/** True while [stopRecordingUnattended] is still finalizing; its notice arrives afterwards. */
fun IosService.isFinishingRecording(device: IosDevice): Boolean = finishing.containsKey(device.udid)

fun IosService.takeRecordingNotice(device: IosDevice): String? = recordingNotices.remove(device.udid)

private fun finishRecording(udid: String, target: File): String {
    val rec = recordings.remove(udid) ?: throw Exception("No recording running")
    try {
        // Both tools finalize the file on SIGINT; Process.destroy() sends SIGTERM and can leave it unreadable.
        // xcrun execs the tool in place, so the pid is the recorder itself; descendants are signalled too to be safe.
        val pids = listOf(rec.process.pid()) + rec.process.descendants().map { it.pid() }.toList()
        Exec.capture(listOf("kill", "-INT") + pids.map { it.toString() })
        // devicectl still has to pull the video off the phone after stopping
        if (!rec.process.waitFor(120, TimeUnit.SECONDS)) {
            rec.process.destroyForcibly()
            throw Exception("Recorder didn't finish in time; the video may be incomplete")
        }
        if (!rec.file.exists() || rec.file.length() == 0L) {
            val last = rec.output.toString().lines().lastOrNull { it.isNotBlank() }?.trim()
            throw Exception(last ?: "No video was written")
        }
        target.parentFile?.mkdirs()
        Files.move(rec.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target.absolutePath
    } finally {
        rec.file.parentFile?.deleteRecursively()
    }
}

private fun fallbackRecordingFile(udid: String, extension: String): File {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    return File(System.getProperty("user.home"), "Movies/adbGUI/recording-${udid.take(8)}-$stamp.$extension")
}
