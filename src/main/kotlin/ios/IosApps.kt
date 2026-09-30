package ios

import ios.IosService.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import process.Exec
import java.io.File
import java.net.URI
import java.nio.file.Files

data class IosApp(
    val bundleId: String,
    val name: String,
    /** CFBundleShortVersionString, e.g. "1.2". */
    val version: String,
    /** CFBundleVersion, e.g. "42". */
    val build: String,
    /** Path of the .app bundle (on the Mac for simulators, on the device for physical ones). */
    val path: String,
    /** Apple/system apps on simulators; "default" (non-developer, non-App-Store) apps on devices. */
    val isSystem: Boolean,
    val isRemovable: Boolean,
    val isHidden: Boolean = false,
    /** Simulator only: the data container on the Mac. */
    val dataContainer: String? = null,
) {
    val displayVersion get() = when {
        version.isEmpty() -> build
        build.isEmpty() || build == version -> version
        else -> "$version ($build)"
    }
}

/** Every installed app, system apps included; callers filter on [IosApp.isSystem]. */
suspend fun IosService.apps(device: IosDevice): Result<List<IosApp>> = withContext(Dispatchers.IO) {
    if (device.isSimulator) simulatorApps(device) else physicalApps(device)
}.map { list -> list.sortedWith(compareBy({ it.name.lowercase() }, { it.bundleId })) }

private fun IosService.simulatorApps(device: IosDevice): Result<List<IosApp>> = runCatching {
    val plist = simctl("listapps", device.udid).toResult().getOrThrow()
    // listapps prints an old-style plist; plutil turns it into JSON
    val converted = Exec.capture(listOf("plutil", "-convert", "json", "-o", "-", "-"), stdin = plist)
    val text = converted.toResult().getOrThrow()
    json.parseToJsonElement(text).jsonObject.map { (bundleId, value) ->
        val o = value.jsonObject
        IosApp(
            bundleId = bundleId,
            name = o.str("CFBundleDisplayName").ifEmpty { o.str("CFBundleName") }.ifEmpty { bundleId },
            version = o.str("CFBundleShortVersionString"),
            build = o.str("CFBundleVersion"),
            path = o.str("Path").ifEmpty { fileUrlToPath(o.str("Bundle")) },
            isSystem = o.str("ApplicationType") != "User",
            isRemovable = o.str("IsRemovable") == "1",
            isHidden = o.str("IsHidden") == "1",
            dataContainer = o.str("DataContainer").takeIf { it.isNotEmpty() }?.let(::fileUrlToPath),
        )
    }
}

private fun IosService.physicalApps(device: IosDevice): Result<List<IosApp>> {
    // Over Wi-Fi the first call after a pause sometimes reports success with no apps at all; ask again
    val first = queryPhysicalApps(device)
    return if (first.getOrNull()?.isEmpty() == true) queryPhysicalApps(device) else first
}

private fun IosService.queryPhysicalApps(device: IosDevice): Result<List<IosApp>> =
    // Without --include-default-apps devicectl only lists developer-built apps, not App Store ones
    devicectlJson("device", "info", "apps", "--device", device.udid, "--include-default-apps").map { result ->
        val apps = result.jsonObject["apps"]?.jsonArray ?: return@map emptyList()
        apps.map { a ->
            val o = a.jsonObject
            val bundleId = o.str("bundleIdentifier")
            IosApp(
                bundleId = bundleId,
                name = o.str("name").ifEmpty { bundleId },
                version = o.str("version"),
                build = o.str("bundleVersion"),
                path = fileUrlToPath(o.str("url")),
                isSystem = o.bool("defaultApp"),
                isRemovable = o.bool("removable"),
                isHidden = o.bool("hidden"),
            )
        }
    }

// --- Actions ---

suspend fun IosService.launchApp(device: IosDevice, bundleId: String): Result<Unit> = withContext(Dispatchers.IO) {
    if (device.isSimulator) {
        simctl("launch", "--terminate-running-process", device.udid, bundleId).toResult().map { }
    } else {
        devicectlJson("device", "process", "launch", "--device", device.udid, "--terminate-existing", bundleId).map { }
    }
}

/** Stops the app. Physical devices need a pid, found by matching the app bundle path in the process list. */
suspend fun IosService.terminateApp(device: IosDevice, app: IosApp): Result<Unit> = withContext(Dispatchers.IO) {
    if (device.isSimulator) {
        val res = simctl("terminate", device.udid, app.bundleId)
        if (!res.isSuccess && "found nothing to terminate" in res.stderr) Result.failure(Exception("${app.name} is not running"))
        else res.toResult().map { }
    } else {
        runCatching {
            val pid = runningPid(device, app) ?: throw Exception("${app.name} is not running")
            devicectlJson("device", "process", "terminate", "--device", device.udid, "--pid", pid.toString()).getOrThrow()
            Unit
        }
    }
}

private fun IosService.runningPid(device: IosDevice, app: IosApp): Int? {
    val result = devicectlJson("device", "info", "processes", "--device", device.udid).getOrThrow()
    val bundle = normalizeDevicePath(app.path).trimEnd('/') + "/"
    val processes = result.jsonObject["runningProcesses"]?.jsonArray ?: return null
    return processes.firstNotNullOfOrNull { p ->
        val o = p.jsonObject
        val exe = normalizeDevicePath(fileUrlToPath(o.str("executable")))
        // The main executable sits directly in the bundle; extensions live deeper (PlugIns/…)
        if (exe.startsWith(bundle) && '/' !in exe.removePrefix(bundle)) o["processIdentifier"]?.jsonPrimitive?.intOrNull
        else null
    }
}

suspend fun IosService.uninstallApp(device: IosDevice, bundleId: String): Result<Unit> = withContext(Dispatchers.IO) {
    if (device.isSimulator) {
        simctl("uninstall", device.udid, bundleId).toResult().map { }
    } else {
        devicectlJson("device", "uninstall", "app", "--device", device.udid, bundleId).map { }
    }
}

/** Installs a `.app` bundle or an `.ipa` (unpacked to its Payload `.app` first). Returns the bundle id when known. */
suspend fun IosService.installApp(device: IosDevice, file: File): Result<String> = withContext(Dispatchers.IO) {
    var tmpDir: File? = null
    try {
        runCatching {
            val app = if (file.extension.equals("ipa", ignoreCase = true)) {
                val dir = Files.createTempDirectory("adbgui-ipa").toFile().also { tmpDir = it }
                Exec.capture(listOf("ditto", "-x", "-k", file.absolutePath, dir.absolutePath)).toResult().getOrThrow()
                File(dir, "Payload").listFiles()?.firstOrNull { it.isDirectory && it.extension == "app" }
                    ?: throw Exception("No .app found in ${file.name}")
            } else file
            if (!app.isDirectory) throw Exception("${file.name} is not an app bundle")
            if (device.isSimulator) {
                simctl("install", device.udid, app.absolutePath).toResult().getOrThrow()
                bundleIdOf(app) ?: app.nameWithoutExtension
            } else {
                val result = devicectlJson("device", "install", "app", "--device", device.udid, app.absolutePath).getOrThrow()
                result.jsonObject["installedApplications"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.str("bundleID")?.ifEmpty { null }
                    ?: bundleIdOf(app) ?: app.nameWithoutExtension
            }
        }
    } finally {
        tmpDir?.deleteRecursively()
    }
}

private fun bundleIdOf(app: File): String? {
    val plist = File(app, "Info.plist").takeIf { it.exists() } ?: return null
    val res = Exec.capture(listOf("plutil", "-extract", "CFBundleIdentifier", "raw", "-o", "-", plist.absolutePath))
    return res.stdout.trim().takeIf { res.isSuccess && it.isNotEmpty() }
}

/** Simulator only: the app's data container on the Mac. */
suspend fun IosService.appDataContainer(device: IosDevice, bundleId: String): Result<File> = withContext(Dispatchers.IO) {
    simctl("get_app_container", device.udid, bundleId, "data").toResult().map { File(it) }
}

/** Opens [dir] in Finder. */
suspend fun IosService.revealInFinder(dir: File): Result<Unit> = withContext(Dispatchers.IO) {
    Exec.capture(listOf("open", dir.absolutePath)).toResult().map { }
}

// --- Helpers ---

private fun fileUrlToPath(url: String): String =
    if (url.startsWith("file:")) runCatching { File(URI(url)).path }.getOrDefault(url.removePrefix("file://"))
    else url

/** /private/var and /var are the same place on iOS; compare without the prefix. */
private fun normalizeDevicePath(path: String) = path.removePrefix("/private")

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: ""
private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false
