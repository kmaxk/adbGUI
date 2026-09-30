package ios

import ios.IosService.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import process.Exec
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// Files are browsed per app container. Simulator containers live on the Mac disk and are
// read with java.io.File; physical devices go through `devicectl device info files` and
// `devicectl device copy from/to`.

/** An app whose containers can be browsed. */
data class IosContainerApp(
    val bundleId: String,
    val name: String,
    val isUser: Boolean,
    val groups: List<String> = emptyList(),
)

/**
 * One browsable container: the app's data container, or one of its app groups
 * ([groupId] set). [localRoot] is the container directory for simulators, null on devices.
 */
data class IosContainer(
    val app: IosContainerApp,
    val groupId: String?,
    val localRoot: File?,
) {
    val domainType get() = if (groupId != null) "appGroupDataContainer" else "appDataContainer"
    val domainIdentifier get() = groupId ?: app.bundleId
    val label get() = groupId ?: "${app.name} data"
}

/** A file inside a container. [path] is relative to the container root ("" is the root). */
data class IosFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** Epoch millis, or null when unknown. */
    val modified: Long?,
)

// --- App list ---

/** User apps first, then system apps; physical devices only list apps with an accessible container. */
suspend fun IosService.containerApps(device: IosDevice): Result<List<IosContainerApp>> = withContext(Dispatchers.IO) {
    runCatching {
        val apps = if (device.isSimulator) simulatorContainerApps(device) else physicalContainerApps(device)
        apps.sortedWith(compareBy<IosContainerApp> { !it.isUser }.thenBy { it.name.lowercase() })
    }
}

private fun IosService.simulatorContainerApps(device: IosDevice): List<IosContainerApp> {
    val plist = simctl("listapps", device.udid).toResult().getOrThrow()
    val converted = Exec.capture(listOf("plutil", "-convert", "json", "-o", "-", "-"), plist).toResult().getOrThrow()
    return json.parseToJsonElement(converted).jsonObject.map { (bundleId, info) ->
        val o = info.jsonObject
        IosContainerApp(
            bundleId = bundleId,
            name = o.string("CFBundleDisplayName") ?: o.string("CFBundleName") ?: bundleId,
            isUser = o.string("ApplicationType") == "User",
            groups = o["GroupContainers"]?.let { g -> (g as? JsonObject)?.keys?.sorted() }.orEmpty(),
        )
    }
}

private fun physicalContainerApps(device: IosDevice): List<IosContainerApp> {
    val result = devicectlFiles(
        "device", "info", "apps", "--device", device.udid,
        "--require-container-access", "--include-app-group-identifiers",
    ).getOrThrow()
    return result.jsonObject["apps"]?.jsonArray.orEmpty().map { a ->
        val o = a.jsonObject
        val bundleId = o.string("bundleIdentifier") ?: ""
        IosContainerApp(
            bundleId = bundleId,
            name = o.string("name") ?: bundleId,
            isUser = o["defaultApp"]?.jsonPrimitive?.booleanOrNull != true,
            groups = o["appGroupIdentifiers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty(),
        )
    }.filter { it.bundleId.isNotEmpty() }
}

// --- Containers ---

/** Resolves the container; for simulators this finds its directory on disk. */
suspend fun IosService.openContainer(device: IosDevice, app: IosContainerApp, groupId: String?): Result<IosContainer> =
    withContext(Dispatchers.IO) {
        if (!device.isSimulator) return@withContext Result.success(IosContainer(app, groupId, null))
        runCatching {
            val path = if (groupId == null) {
                simctl("get_app_container", device.udid, app.bundleId, "data").toResult().getOrThrow()
            } else {
                // Prints "<group id>\t<path>" per line
                simctl("get_app_container", device.udid, app.bundleId, "groups").toResult().getOrThrow()
                    .lines().firstOrNull { it.substringBefore('\t') == groupId }?.substringAfter('\t')
                    ?: throw Exception("App group $groupId not found")
            }
            val dir = File(path.trim())
            if (!dir.isDirectory) throw Exception("Container not found: $path")
            IosContainer(app, groupId, dir)
        }
    }

// --- Listing ---

/** Full recursive listings of device containers, keyed by udid + domain. `info files --no-recurse` fails on devices. */
private val remoteListings = ConcurrentHashMap<String, List<IosFileEntry>>()

private fun cacheKey(device: IosDevice, c: IosContainer) = "${device.udid}|${c.domainType}|${c.domainIdentifier}"

/**
 * Entries directly inside [dir] (relative, "" = root), folders first.
 * On devices the whole container is fetched once; pass [refresh] to fetch it again.
 */
suspend fun IosService.listContainerFiles(
    device: IosDevice,
    container: IosContainer,
    dir: String,
    refresh: Boolean = false,
): Result<List<IosFileEntry>> = withContext(Dispatchers.IO) {
    runCatching {
        val entries = container.localRoot?.let { root -> listLocal(root, dir) } ?: run {
            val key = cacheKey(device, container)
            val all = if (refresh) null else remoteListings[key]
            val listing = all ?: fetchRemoteListing(device, container).also { remoteListings[key] = it }
            listing.filter { parentOf(it.path) == dir }
        }
        entries.sortedWith(compareBy<IosFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }
}

private fun listLocal(root: File, dir: String): List<IosFileEntry> {
    val folder = if (dir.isEmpty()) root else File(root, dir)
    if (!folder.canonicalPath.startsWith(root.canonicalPath)) throw Exception("Outside of the container")
    val children = folder.listFiles() ?: throw Exception("Cannot read ${dir.ifEmpty { "/" }}")
    return children.map { f ->
        val symlink = java.nio.file.Files.isSymbolicLink(f.toPath())
        IosFileEntry(
            name = f.name,
            path = if (dir.isEmpty()) f.name else "$dir/${f.name}",
            isDirectory = f.isDirectory && !symlink,
            isSymlink = symlink,
            size = if (f.isFile) f.length() else 0L,
            modified = f.lastModified().takeIf { it > 0 },
        )
    }
}

private fun fetchRemoteListing(device: IosDevice, c: IosContainer): List<IosFileEntry> {
    val result = devicectlFiles(
        "device", "info", "files", "--device", device.udid,
        "--domain-type", c.domainType, "--domain-identifier", c.domainIdentifier,
    ).getOrThrow()
    return result.jsonObject["files"]?.jsonArray.orEmpty().mapNotNull { f ->
        val o = f.jsonObject
        val path = (o.string("relativePath") ?: o.string("name"))?.trim('/') ?: return@mapNotNull null
        if (path.isEmpty()) return@mapNotNull null
        val res = o["resources"] as? JsonObject
        val meta = o["metadata"] as? JsonObject
        val isDir = res?.get("isDirectory")?.jsonPrimitive?.booleanOrNull == true
        IosFileEntry(
            name = path.substringAfterLast('/'),
            path = path,
            isDirectory = isDir,
            isSymlink = res?.get("isSymbolicLink")?.jsonPrimitive?.booleanOrNull == true,
            size = if (isDir) 0L else meta?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
            modified = meta?.string("lastModDate")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }
}

private fun parentOf(path: String) = if ('/' in path) path.substringBeforeLast('/') else ""

// --- Transfer ---

/** Copies [entry] (file or folder) out of the container to [target]. */
suspend fun IosService.exportContainerFile(
    device: IosDevice,
    container: IosContainer,
    entry: IosFileEntry,
    target: File,
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val root = container.localRoot
        if (root != null) {
            val source = File(root, entry.path)
            if (entry.isDirectory) source.copyRecursively(target, overwrite = true) else source.copyTo(target, overwrite = true)
        } else {
            // A folder's contents land inside --destination, a file is written as --destination
            devicectlFiles(
                "device", "copy", "from", "--device", device.udid,
                "--domain-type", container.domainType, "--domain-identifier", container.domainIdentifier,
                "--source", entry.path, "--destination", target.absolutePath,
                timeoutSeconds = 600,
            ).getOrThrow()
        }
        target.absolutePath
    }
}

/** Copies local [sources] into [dir] of the container. Returns the number of items copied. */
suspend fun IosService.importContainerFiles(
    device: IosDevice,
    container: IosContainer,
    dir: String,
    sources: List<File>,
): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
        val root = container.localRoot
        for (source in sources) {
            val relative = if (dir.isEmpty()) source.name else "$dir/${source.name}"
            if (root != null) {
                val target = File(root, relative)
                if (source.isDirectory) source.copyRecursively(target, overwrite = true)
                else source.copyTo(target, overwrite = true)
            } else {
                devicectlFiles(
                    "device", "copy", "to", "--device", device.udid,
                    "--domain-type", container.domainType, "--domain-identifier", container.domainIdentifier,
                    "--source", source.absolutePath, "--destination", relative,
                    timeoutSeconds = 600,
                ).getOrThrow()
            }
        }
        if (root == null) remoteListings.remove(cacheKey(device, container))
        sources.size
    }
}

/** devicectl has no delete for app containers, so only simulators support it. */
fun IosService.canDeleteFiles(container: IosContainer) = container.localRoot != null

suspend fun IosService.deleteContainerFile(container: IosContainer, entry: IosFileEntry): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val root = container.localRoot ?: throw Exception("Deleting is only supported on simulators")
            if (entry.path.isEmpty()) throw Exception("Cannot delete the container root")
            val file = File(root, entry.path)
            val ok = if (entry.isDirectory) file.deleteRecursively() else file.delete()
            if (!ok) throw Exception("Could not delete ${entry.name}")
        }
    }

/** Reveals [entry] (or the container's folder at [dir]) in Finder. Simulators only. */
suspend fun IosService.revealInFinder(container: IosContainer, dir: String, entry: IosFileEntry?): Result<Unit> =
    withContext(Dispatchers.IO) {
        val root = container.localRoot ?: return@withContext Result.failure(Exception("Only for simulators"))
        val args = if (entry != null) listOf("open", "-R", File(root, entry.path).absolutePath)
        else listOf("open", if (dir.isEmpty()) root.absolutePath else File(root, dir).absolutePath)
        Exec.capture(args).toResult().map { }
    }

// --- devicectl ---

/**
 * `devicectl ... --json-output -` returning `result`. Unlike [IosService.devicectlJson] this reads
 * the error text from jsonVersion 5 (`{"string": ...}` values) and retries once, because Wi-Fi
 * connections regularly drop the first file-service request ("connection ... no longer valid").
 */
private fun devicectlFiles(vararg args: String, timeoutSeconds: Int = 120): Result<JsonElement> {
    var last: Result<JsonElement> = Result.failure(Exception("devicectl failed"))
    repeat(2) {
        last = runDevicectl(args.toList(), timeoutSeconds)
        if (last.isSuccess) return last
    }
    return last
}

private fun runDevicectl(args: List<String>, timeoutSeconds: Int): Result<JsonElement> {
    val res = Exec.capture(
        listOf("xcrun", "devicectl") + args +
            listOf("--json-output", "-", "--quiet", "--timeout", timeoutSeconds.toString())
    )
    val root = runCatching { IosService.json.parseToJsonElement(res.stdout).jsonObject }.getOrNull()
        ?: return Result.failure(Exception(res.stderr.trim().ifEmpty { res.stdout.trim() }.ifEmpty { "devicectl failed" }))
    val outcome = root["info"]?.jsonObject?.string("outcome")
    if (outcome == "success") return Result.success(root["result"] ?: JsonObject(emptyMap()))
    return Result.failure(Exception(errorMessage(root["error"]) ?: res.stderr.trim().ifEmpty { "devicectl failed" }))
}

/** Most specific description in a devicectl error, following NSUnderlyingError. */
private fun errorMessage(error: JsonElement?): String? {
    val info = (error as? JsonObject)?.get("userInfo") as? JsonObject ?: return null
    val own = info["NSLocalizedDescription"]?.let(::textOf)
    val underlying = ((info["NSUnderlyingError"] as? JsonObject)?.get("error"))?.let(::errorMessage)
    val debug = info["NSDebugDescription"]?.let(::textOf)
    return when {
        own != null && underlying != null && own.contains("unknown", ignoreCase = true) -> underlying
        own != null -> own
        underlying != null -> underlying
        else -> debug
    }
}

private fun textOf(e: JsonElement): String? = when (e) {
    is JsonPrimitive -> e.content
    is JsonObject -> (e["string"] as? JsonPrimitive)?.content
    else -> null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
