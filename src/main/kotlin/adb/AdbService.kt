package adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class RunningApp(val pid: String, val packageName: String)

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val modified: String,
)

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkLevel: String,
    val buildNumber: String,
    val abi: String,
    val resolution: String,
    val ipAddress: String?,
    val batteryLevel: String,
    val batteryStatus: String,
    val batteryTemp: String,
    val batteryHealth: String,
)

data class PortForward(val serial: String, val local: String, val remote: String)

data class AppInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val minSdk: String,
    val targetSdk: String,
    val firstInstall: String,
    val lastUpdate: String,
    val installer: String,
    val apkPath: String,
    val dataDir: String,
)

data class AdbDevice(val serial: String, val state: String, val model: String? = null) {
    val isOnline get() = state == "device"
    val displayName get() = if (model != null) "$model ($serial)" else serial
    override fun toString() = displayName
}

object AdbService {
    var adbPath: String = findAdb()

    fun detectAdb(): String = findAdb()

    private fun findAdb(): String {
        val candidates = listOfNotNull(
            "adb",
            "/usr/local/bin/adb",
            "/opt/homebrew/bin/adb",
            System.getenv("HOME")?.let { "$it/Library/Android/sdk/platform-tools/adb" },
        )
        return candidates.firstOrNull { path ->
            try { Runtime.getRuntime().exec(arrayOf(path, "version")).waitFor() == 0 }
            catch (_: Exception) { false }
        } ?: "adb"
    }

    suspend fun devices(): List<AdbDevice> = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "devices", "-l"))
            .lines()
            .drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 2) return@mapNotNull null
                val serial = parts[0]
                val state = parts[1]
                val model = parts.drop(2)
                    .firstOrNull { it.startsWith("model:") }
                    ?.removePrefix("model:")
                    ?.replace('_', ' ')
                AdbDevice(serial, state, model)
            }
            .filter { it.isOnline }
    }

    private const val DEVICE_POLL_INTERVAL_MS = 1000L

    fun deviceTrackFlow(): Flow<List<AdbDevice>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(runCatching { devices() }.getOrDefault(emptyList()))
            delay(DEVICE_POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun runningApps(serial: String): List<RunningApp> = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", "ps", "-A"))
            .lines()
            .drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 3) return@mapNotNull null
                val user = parts[0]
                val pid = parts[1]
                val name = parts.last()
                if (!user.startsWith("u0_a")) return@mapNotNull null
                if (name.startsWith("/") || name.startsWith("[") || name.startsWith(":")) return@mapNotNull null
                RunningApp(pid, name)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.packageName }
    }

    fun logcatFlow(serial: String, pid: String? = null): Flow<String> = flow {
        val cmd = buildList {
            add(adbPath); add("-s"); add(serial); add("logcat"); add("-v"); add("time")
            if (pid != null) add("--pid=$pid")
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        try {
            val reader = process.inputStream.bufferedReader()
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                emit(line)
            }
        } finally {
            process.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun clearLogcat(serial: String) = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "logcat", "-c"))
    }

    suspend fun packages(serial: String): List<String> = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", "pm", "list", "packages"))
            .lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .sorted()
    }

    suspend fun uninstall(serial: String, packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "uninstall", packageName))
            if (output.contains("Success", ignoreCase = true)) Result.success(Unit)
            else Result.failure(Exception(output.trim().ifEmpty { "Unknown error" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFiles(serial: String, path: String): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "shell", "ls", "-lA", shellQuote(path)))
            val lines = output.lines().filter { it.isNotBlank() }

            val entries = lines.mapNotNull { line ->
                if (line.startsWith("total") || line.startsWith("ls:")) return@mapNotNull null
                val parts = line.trim().split("\\s+".toRegex(), limit = 8)
                if (parts.size < 8) return@mapNotNull null
                val perms = parts[0]
                if (perms.length < 10) return@mapNotNull null
                val isSymlink = perms[0] == 'l'
                val name = if (isSymlink) parts[7].substringBefore(" -> ") else parts[7]
                RemoteFile(
                    name = name,
                    path = if (path == "/") "/$name" else "$path/$name",
                    isDirectory = perms[0] == 'd' || isSymlink,
                    isSymlink = isSymlink,
                    size = parts[4].toLongOrNull() ?: 0L,
                    modified = "${parts[5]} ${parts[6]}",
                )
            }.sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })

            if (entries.isEmpty()) {
                val error = lines.firstOrNull {
                    it.contains("No such file", ignoreCase = true) ||
                        it.contains("Permission denied", ignoreCase = true) ||
                        it.contains("Not a directory", ignoreCase = true)
                }
                if (error != null) return@withContext Result.failure(Exception(error.trim()))
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pull(serial: String, remotePath: String, localPath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "pull", remotePath, localPath))
            if (output.contains("pulled", ignoreCase = true)) Result.success(localPath)
            else Result.failure(Exception(output.trim().ifEmpty { "Unknown error" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(serial: String, path: String, recursive: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val flag = if (recursive) "-rf" else "-f"
            val output = runCommand(listOf(adbPath, "-s", serial, "shell", "rm", flag, shellQuote(path)))
            if (output.isBlank()) Result.success(Unit)
            else Result.failure(Exception(output.trim()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deviceInfo(serial: String): DeviceInfo = withContext(Dispatchers.IO) {
        val props = runCommand(listOf(adbPath, "-s", serial, "shell", "getprop"))
            .lines()
            .mapNotNull { line ->
                val match = Regex("""\[(.+?)]: \[(.*)]""").find(line) ?: return@mapNotNull null
                match.groupValues[1] to match.groupValues[2]
            }
            .toMap()

        val battery = runCommand(listOf(adbPath, "-s", serial, "shell", "dumpsys", "battery"))
            .lines()
            .mapNotNull { line ->
                val parts = line.trim().split(": ", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()

        val resolution = runCommand(listOf(adbPath, "-s", serial, "shell", "wm", "size"))
            .lines()
            .firstOrNull { it.contains("Physical size:") }
            ?.substringAfter("Physical size:")
            ?.trim()
            ?: "–"

        val ip = runCommand(listOf(adbPath, "-s", serial, "shell", "ip", "route"))
            .lines()
            .firstOrNull { it.contains(" wlan") && it.contains(" src ") }
            ?.substringAfter(" src ")
            ?.trim()
            ?.substringBefore(' ')

        val statusLabels = mapOf("2" to "Charging", "3" to "Discharging", "4" to "Not charging", "5" to "Full")
        val healthLabels = mapOf(
            "2" to "Good", "3" to "Overheat", "4" to "Dead",
            "5" to "Over voltage", "6" to "Failure", "7" to "Cold",
        )

        DeviceInfo(
            model = props["ro.product.model"] ?: "–",
            manufacturer = props["ro.product.manufacturer"] ?: "–",
            androidVersion = props["ro.build.version.release"] ?: "–",
            sdkLevel = props["ro.build.version.sdk"] ?: "–",
            buildNumber = props["ro.build.display.id"] ?: "–",
            abi = props["ro.product.cpu.abi"] ?: "–",
            resolution = resolution,
            ipAddress = ip,
            batteryLevel = battery["level"]?.let { "$it %" } ?: "–",
            batteryStatus = statusLabels[battery["status"]] ?: "Unknown",
            batteryTemp = battery["temperature"]?.toIntOrNull()?.let { "%.1f °C".format(it / 10.0) } ?: "–",
            batteryHealth = healthLabels[battery["health"]] ?: "Unknown",
        )
    }

    suspend fun reboot(serial: String, mode: String? = null): Unit = withContext(Dispatchers.IO) {
        runCommand(buildList {
            add(adbPath); add("-s"); add(serial); add("reboot")
            if (mode != null) add(mode)
        })
        Unit
    }

    suspend fun enableTcpip(serial: String, port: Int = 5555): Result<String> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "tcpip", port.toString()))
            if (output.contains("error", ignoreCase = true)) Result.failure(Exception(output.trim()))
            else Result.success(output.trim().ifEmpty { "Restarted in TCP mode on port $port" })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun connect(address: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "connect", address)).trim()
            if (output.contains("connected", ignoreCase = true) && !output.contains("cannot", ignoreCase = true))
                Result.success(output)
            else Result.failure(Exception(output.ifEmpty { "Unknown error" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun disconnect(address: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "disconnect", address)).trim()
            if (output.contains("error", ignoreCase = true)) Result.failure(Exception(output))
            else Result.success(output.ifEmpty { "Disconnected" })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pair(address: String, code: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "pair", address, code)).trim()
            if (output.contains("Successfully paired", ignoreCase = true)) Result.success(output)
            else Result.failure(Exception(output.ifEmpty { "Pairing failed" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listForwards(serial: String): List<PortForward> = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "forward", "--list"))
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 3) return@mapNotNull null
                PortForward(parts[0], parts[1], parts[2])
            }
            .filter { it.serial == serial }
    }

    suspend fun addForward(serial: String, localPort: Int, remotePort: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "forward", "tcp:$localPort", "tcp:$remotePort"))
            if (output.contains("error", ignoreCase = true)) Result.failure(Exception(output.trim()))
            else Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeForward(serial: String, local: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "forward", "--remove", local))
            if (output.contains("error", ignoreCase = true)) Result.failure(Exception(output.trim()))
            else Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Install / Push ---

    suspend fun install(serial: String, apkPath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "install", "-r", apkPath))
            if (output.contains("Success", ignoreCase = true)) Result.success("Installed: ${java.io.File(apkPath).name}")
            else Result.failure(Exception(output.trim().ifEmpty { "Unknown error" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun push(serial: String, localPath: String, remotePath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "push", localPath, remotePath))
            if (output.contains("pushed", ignoreCase = true)) Result.success(remotePath)
            else Result.failure(Exception(output.trim().ifEmpty { "Unknown error" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- App actions ---

    suspend fun forceStop(serial: String, packageName: String): Unit = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", "am", "force-stop", packageName))
        Unit
    }

    suspend fun clearData(serial: String, packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "shell", "pm", "clear", packageName))
            if (output.contains("Success", ignoreCase = true)) Result.success(Unit)
            else Result.failure(Exception(output.trim().ifEmpty { "Unknown error" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun launchApp(serial: String, packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(
                adbPath, "-s", serial, "shell", "monkey",
                "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1",
            ))
            if (output.contains("Events injected", ignoreCase = true)) Result.success(Unit)
            else Result.failure(Exception("No launchable activity found"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun appInfo(serial: String, packageName: String): AppInfo = withContext(Dispatchers.IO) {
        val dump = runCommand(listOf(adbPath, "-s", serial, "shell", "dumpsys", "package", packageName))
        fun find(key: String): String? = dump.lines()
            .firstOrNull { it.trim().startsWith("$key=") }
            ?.substringAfter("$key=")
            ?.trim()
        val sdkLine = dump.lines().firstOrNull { it.contains("targetSdk=") }
        AppInfo(
            packageName = packageName,
            versionName = find("versionName") ?: "–",
            versionCode = find("versionCode")?.substringBefore(' ') ?: "–",
            minSdk = sdkLine?.substringAfter("minSdk=")?.substringBefore(' ')?.trim() ?: "–",
            targetSdk = sdkLine?.substringAfter("targetSdk=")?.substringBefore(' ')?.trim() ?: "–",
            firstInstall = find("firstInstallTime") ?: "–",
            lastUpdate = find("lastUpdateTime") ?: "–",
            installer = find("installerPackageName")?.takeIf { it != "null" } ?: "–",
            apkPath = find("codePath") ?: "–",
            dataDir = find("dataDir") ?: "–",
        )
    }

    // --- Screenshot / Screen recording ---

    suspend fun screenshot(serial: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val bytes = runCommandBytes(listOf(adbPath, "-s", serial, "exec-out", "screencap", "-p"))
            // PNG magic: 0x89 'P' 'N' 'G'
            if (bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte())
                Result.success(bytes)
            else Result.failure(Exception(bytes.toString(Charsets.UTF_8).trim().take(200).ifEmpty { "Empty screenshot" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private const val RECORD_REMOTE_PATH = "/sdcard/adbgui_recording.mp4"
    private val recordProcesses = mutableMapOf<String, Process>()

    fun isRecording(serial: String): Boolean = recordProcesses[serial]?.isAlive == true

    suspend fun startRecording(serial: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isRecording(serial)) return@withContext Result.failure(Exception("Already recording"))
            val process = ProcessBuilder(listOf(adbPath, "-s", serial, "shell", "screenrecord", RECORD_REMOTE_PATH))
                .redirectErrorStream(true)
                .start()
            recordProcesses[serial] = process
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stopRecording(serial: String, localPath: String): Result<String> = withContext(Dispatchers.IO) {
        val process = recordProcesses.remove(serial)
            ?: return@withContext Result.failure(Exception("No recording running"))
        try {
            // SIGINT lets screenrecord finalize the MP4 before the shell dies
            runCommand(listOf(adbPath, "-s", serial, "shell", "pkill", "-2", "screenrecord"))
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            process.destroyForcibly()
            Thread.sleep(1000)
            val output = runCommand(listOf(adbPath, "-s", serial, "pull", RECORD_REMOTE_PATH, localPath))
            runCommand(listOf(adbPath, "-s", serial, "shell", "rm", "-f", RECORD_REMOTE_PATH))
            if (output.contains("pulled", ignoreCase = true)) Result.success(localPath)
            else Result.failure(Exception(output.trim().ifEmpty { "Pull failed" }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Shell / Input ---

    suspend fun shell(serial: String, command: String): String = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", command))
    }

    suspend fun inputText(serial: String, text: String): Unit = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", "input", "text", shellQuote(text.replace(" ", "%s"))))
        Unit
    }

    suspend fun keyEvent(serial: String, keyCode: Int): Unit = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", "input", "keyevent", keyCode.toString()))
        Unit
    }

    suspend fun tap(serial: String, x: Int, y: Int): Unit = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", "input", "tap", x.toString(), y.toString()))
        Unit
    }

    suspend fun swipe(serial: String, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Unit =
        withContext(Dispatchers.IO) {
            runCommand(listOf(
                adbPath, "-s", serial, "shell", "input", "swipe",
                x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString(),
            ))
            Unit
        }

    // --- Display ---

    suspend fun displayState(serial: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val size = runCommand(listOf(adbPath, "-s", serial, "shell", "wm", "size")).trim()
        val density = runCommand(listOf(adbPath, "-s", serial, "shell", "wm", "density")).trim()
        size to density
    }

    suspend fun setDisplaySize(serial: String, size: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "shell", "wm", "size", size ?: "reset"))
            if (output.contains("Error", ignoreCase = true) || output.contains("Exception"))
                Result.failure(Exception(output.trim().lines().first()))
            else Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setDensity(serial: String, dpi: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(adbPath, "-s", serial, "shell", "wm", "density", dpi ?: "reset"))
            if (output.contains("Error", ignoreCase = true) || output.contains("Exception"))
                Result.failure(Exception(output.trim().lines().first()))
            else Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Deeplink ---

    suspend fun openUrl(serial: String, url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val output = runCommand(listOf(
                adbPath, "-s", serial, "shell", "am", "start",
                "-a", "android.intent.action.VIEW", "-d", shellQuote(url),
            ))
            if (output.contains("Error", ignoreCase = true) || output.contains("Exception"))
                Result.failure(Exception(output.trim().lines().last()))
            else Result.success(output.trim().lines().first())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Properties ---

    suspend fun getProps(serial: String): Map<String, String> = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", "getprop"))
            .lines()
            .mapNotNull { line ->
                val match = Regex("""\[(.+?)]: \[(.*)]""").find(line) ?: return@mapNotNull null
                match.groupValues[1] to match.groupValues[2]
            }
            .toMap()
    }

    suspend fun batteryLevel(serial: String): Int? = withContext(Dispatchers.IO) {
        runCommand(listOf(adbPath, "-s", serial, "shell", "dumpsys", "battery"))
            .lines()
            .firstOrNull { it.trim().startsWith("level:") }
            ?.substringAfter("level:")
            ?.trim()
            ?.toIntOrNull()
    }

    // --- scrcpy ---

    fun findScrcpy(): String? {
        val candidates = listOf("scrcpy", "/opt/homebrew/bin/scrcpy", "/usr/local/bin/scrcpy")
        return candidates.firstOrNull { path ->
            try { Runtime.getRuntime().exec(arrayOf(path, "--version")).waitFor() == 0 }
            catch (_: Exception) { false }
        }
    }

    suspend fun launchScrcpy(serial: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val scrcpy = findScrcpy() ?: return@withContext Result.failure(Exception("scrcpy not found (brew install scrcpy)"))
            ProcessBuilder(listOf(scrcpy, "-s", serial)).start()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun shellQuote(path: String) = "'" + path.replace("'", "'\\''") + "'"

    private fun runCommandBytes(args: List<String>): ByteArray {
        val process = ProcessBuilder(args).start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        return bytes
    }

    private fun runCommand(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
