package adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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

    private fun shellQuote(path: String) = "'" + path.replace("'", "'\\''") + "'"

    private fun runCommand(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
