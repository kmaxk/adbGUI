package ios

import ios.IosService.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import process.Exec
import java.io.File

// --- Info ---

/**
 * What the Device screen shows about a device. [capabilities] holds devicectl's
 * feature identifiers (last component, e.g. "rebootdevice") for phones; null means
 * unknown (simulators, or the phone didn't answer), in which case nothing is hidden.
 */
data class IosDeviceDetails(
    val rows: List<Pair<String, String>>,
    val capabilities: Set<String>? = null,
)

suspend fun IosService.deviceDetails(device: IosDevice): Result<IosDeviceDetails> = withContext(Dispatchers.IO) {
    if (device.isSimulator) simulatorDetails(device) else physicalDetails(device)
}

private fun IosService.simulatorDetails(device: IosDevice): Result<IosDeviceDetails> = runCatching {
    val res = simctl("list", "devices", "-j")
    if (!res.isSuccess) throw Exception(res.stderr.trim().ifEmpty { "simctl list failed" })
    val byRuntime = json.parseToJsonElement(res.stdout).jsonObject["devices"]?.jsonObject
        ?: throw Exception("Unexpected simctl output")
    val (runtime, sim) = byRuntime.firstNotNullOfOrNull { (runtime, devices) ->
        devices.jsonArray.map { it.jsonObject }.firstOrNull { it.str("udid") == device.udid }?.let { runtime to it }
    } ?: throw Exception("Simulator ${device.udid} not found")
    // "com.apple.CoreSimulator.SimRuntime.iOS-27-0" -> "iOS 27.0"
    val runtimeName = runtime.substringAfterLast('.').replaceFirst('-', ' ').replace('-', '.')
    val deviceType = sim.str("deviceTypeIdentifier").substringAfterLast('.').replace('-', ' ')
    IosDeviceDetails(
        listOfNotNull(
            "Name" to sim.str("name"),
            "Device type" to deviceType.ifEmpty { "–" },
            "Runtime" to runtimeName,
            "UDID" to device.udid,
            "State" to sim.str("state"),
            sim["dataPathSize"]?.jsonPrimitive?.content?.toLongOrNull()?.let { "Data size" to formatBytes(it) },
            sim.str("dataPath").takeIf { it.isNotEmpty() }?.let { "Data path" to it },
        )
    )
}

private fun IosService.physicalDetails(device: IosDevice): Result<IosDeviceDetails> =
    devicectlJson("device", "info", "details", "--device", device.udid).map { result ->
        val r = result.jsonObject
        // The flat *Properties objects are deprecated in favour of `properties`; read both
        val hw = r["hardwareProperties"]?.jsonObject
        val dev = r["deviceProperties"]?.jsonObject
        val conn = r["connectionProperties"]?.jsonObject
        val props = r["properties"]?.jsonObject
        val pHw = props?.get("hardware")?.jsonObject
        val pConn = props?.get("connection")?.jsonObject
        val pSw = props?.get("software")?.jsonObject
        val pState = props?.get("state")?.jsonObject

        fun pick(vararg values: String?) = values.firstOrNull { !it.isNullOrBlank() }

        val osVersion = pick(
            dev?.str("osVersionNumber"),
            pSw?.get("osVersionNumber")?.jsonObject?.str("stringValue"),
            device.osVersion,
        )
        val build = pick(dev?.str("osBuildUpdate"))
        val devMode = pick(
            dev?.str("developerModeStatus"),
            pState?.get("developerModeStatus")?.jsonObject?.keys?.firstOrNull(),
        )
        val storage = (hw?.get("internalStorageCapacity") ?: pHw?.get("internalStorageCapacity"))
            ?.jsonPrimitive?.content?.toLongOrNull()
        val capabilities = r["capabilities"]?.jsonArray
            ?.mapNotNull { it.jsonObject["featureIdentifier"]?.jsonPrimitive?.content?.substringAfterLast('.') }
            ?.toSet()

        IosDeviceDetails(
            rows = listOfNotNull(
                "Name" to (pick(dev?.str("name"), pState?.str("name"), device.name) ?: device.name),
                pick(hw?.str("marketingName"), pHw?.str("marketingName"))?.let { m ->
                    "Model" to (pick(hw?.str("productType"), pHw?.str("productType"))?.let { "$m ($it)" } ?: m)
                },
                osVersion?.let { "iOS" to (build?.let { b -> "$it ($b)" } ?: it) },
                "UDID" to device.udid,
                pick(hw?.str("serialNumber"), pHw?.str("serialNumber"))?.let { "Serial" to it },
                storage?.let { "Storage" to formatBytes(it) },
                pick(conn?.str("transportType"), pConn?.str("transportType"), device.transport)
                    ?.let { "Transport" to if (it == "localNetwork") "Wi-Fi (localNetwork)" else it },
                pick(conn?.str("tunnelState"), pConn?.str("state"))?.let { "Connection" to it },
                devMode?.let { "Developer Mode" to it },
                pick(dev?.str("bootState"), pState?.str("bootState"))?.let { "Boot state" to it },
            ),
            capabilities = capabilities,
        )
    }

// --- Power ---

/** Simulators: shutdown + boot. Phones: `devicectl device reboot`. Not cancelled when the screen goes away. */
suspend fun IosService.reboot(device: IosDevice): Result<String> = withContext(Dispatchers.IO + NonCancellable) {
    if (device.isSimulator) {
        runCatching {
            shutdownIfBooted(device)
            simctl("boot", device.udid).toResult().getOrThrow()
            "${device.name} rebooted"
        }
    } else {
        devicectlJson("device", "reboot", "--device", device.udid).map { "Reboot sent to ${device.name}" }
    }
}

/**
 * Erases a simulator's contents and settings. simctl only erases shut-down devices, so a
 * booted simulator is shut down first and booted again afterwards.
 */
suspend fun IosService.erase(device: IosDevice): Result<String> = withContext(Dispatchers.IO + NonCancellable) {
    runCatching {
        require(device.isSimulator) { "Only simulators can be erased" }
        val wasBooted = shutdownIfBooted(device)
        simctl("erase", device.udid).toResult().getOrThrow()
        if (wasBooted) simctl("boot", device.udid).toResult().getOrThrow()
        "${device.name} erased" + if (wasBooted) " and booted again" else ""
    }
}

/** Returns whether the simulator was running. "Already shut down" isn't an error here. */
private fun IosService.shutdownIfBooted(device: IosDevice): Boolean {
    val res = simctl("shutdown", device.udid)
    if (res.isSuccess) return true
    if (res.stderr.contains("current state: Shutdown")) return false
    throw Exception(res.stderr.trim().ifEmpty { "shutdown failed" })
}

// --- Appearance ---

/** "light" or "dark", or null if the device can't tell. */
suspend fun IosService.appearance(device: IosDevice): Result<String?> = withContext(Dispatchers.IO) {
    if (device.isSimulator) {
        simctl("ui", device.udid, "appearance").toResult().map { it.takeIf { v -> v == "light" || v == "dark" } }
    } else {
        // The JSON layout isn't documented; look for the interface style anywhere in the result
        devicectlJson("device", "info", "appearance", "--device", device.udid).map { findStyle(it) }
    }
}

suspend fun IosService.setAppearance(device: IosDevice, dark: Boolean): Result<String> = withContext(Dispatchers.IO) {
    val mode = if (dark) "dark" else "light"
    if (device.isSimulator) {
        simctl("ui", device.udid, "appearance", mode).toResult().map { "Appearance set to $mode" }
    } else {
        devicectlJson("device", "settings", "appearance", "--device", device.udid, "--mode", mode)
            .map { "Appearance set to $mode" }
    }
}

private fun findStyle(element: JsonElement, key: String = ""): String? = when (element) {
    is JsonObject -> element.entries.firstNotNullOfOrNull { (k, v) -> findStyle(v, k) }
    is JsonArray -> element.firstNotNullOfOrNull { findStyle(it, key) }
    is JsonPrimitive -> element.content.lowercase().takeIf {
        (it == "light" || it == "dark") && (key.contains("style", true) || key.contains("mode", true))
    }
}

// --- Status bar ---

/** Apple's screenshot look: 9:41, full battery, full Wi-Fi and cellular bars. */
suspend fun IosService.overrideStatusBar(device: IosDevice): Result<String> = withContext(Dispatchers.IO) {
    if (device.isSimulator) {
        simctl(
            "status_bar", device.udid, "override",
            "--time", "9:41",
            "--dataNetwork", "wifi",
            "--wifiMode", "active", "--wifiBars", "3",
            "--cellularMode", "active", "--cellularBars", "4",
            "--batteryState", "charged", "--batteryLevel", "100",
        ).toResult().map { "Status bar overridden (9:41, full battery and signal)" }
    } else {
        devicectlJson("device", "simulate", "statusBar", "preset", "--device", device.udid, "screenshot")
            .map { "Status bar overridden (9:41, full battery and signal)" }
    }
}

suspend fun IosService.clearStatusBar(device: IosDevice): Result<String> = withContext(Dispatchers.IO) {
    if (device.isSimulator) {
        simctl("status_bar", device.udid, "clear").toResult().map { "Status bar overrides cleared" }
    } else {
        devicectlJson("device", "simulate", "statusBar", "clear", "--device", device.udid)
            .map { "Status bar overrides cleared" }
    }
}

// --- Location ---

suspend fun IosService.setLocation(device: IosDevice, latitude: Double, longitude: Double): Result<String> =
    withContext(Dispatchers.IO) {
        // Double.toString always uses '.', which both tools require
        // Plain decimals: Double.toString gives "1.0E-5" for tiny values, which the tools reject
        val lat = java.math.BigDecimal(latitude).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val lon = java.math.BigDecimal(longitude).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val msg = "Location set to $lat, $lon"
        if (device.isSimulator) {
            simctl("location", device.udid, "set", "$lat,$lon").toResult().map { msg }
        } else {
            devicectlJson(
                "device", "simulate", "location", "coordinate", "--device", device.udid,
                "--latitude", lat, "--longitude", lon,
            ).map { msg }
        }
    }

suspend fun IosService.clearLocation(device: IosDevice): Result<String> = withContext(Dispatchers.IO) {
    if (device.isSimulator) {
        simctl("location", device.udid, "clear").toResult().map { "Simulated location cleared" }
    } else {
        devicectlJson("device", "simulate", "location", "clear", "--device", device.udid)
            .map { "Simulated location cleared" }
    }
}

// --- Simulator only: push, privacy, media ---

/** Sends [payload] (APNs JSON with an `aps` key, max 4 KB) to [bundleId], via stdin. */
suspend fun IosService.sendPush(device: IosDevice, bundleId: String, payload: String): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching { json.parseToJsonElement(payload) as JsonObject }
            .recoverCatching { throw Exception("Payload must be a JSON object") }
            .mapCatching { simctl("push", device.udid, bundleId, "-", stdin = payload).toResult().getOrThrow() }
            .map { it.ifEmpty { "Notification sent to $bundleId" } }
    }

enum class PrivacyAction { Grant, Revoke, Reset }

val privacyServices = listOf(
    "all", "calendar", "contacts-limited", "contacts", "location", "location-always",
    "photos-add", "photos", "media-library", "microphone", "motion", "reminders", "siri",
)

/** `simctl privacy`; [bundleId] is required for grant/revoke and optional for reset. */
suspend fun IosService.privacy(
    device: IosDevice,
    action: PrivacyAction,
    service: String,
    bundleId: String?,
): Result<String> = withContext(Dispatchers.IO) {
    val verb = action.name.lowercase()
    val args = listOfNotNull("privacy", device.udid, verb, service, bundleId?.takeIf { it.isNotBlank() })
    simctl(*args.toTypedArray()).toResult().map {
        val target = bundleId?.takeIf { b -> b.isNotBlank() } ?: "all apps"
        when (action) {
            PrivacyAction.Grant -> "Granted $service to $target"
            PrivacyAction.Revoke -> "Revoked $service from $target"
            PrivacyAction.Reset -> "Reset $service for $target"
        }
    }
}

suspend fun IosService.addMedia(device: IosDevice, files: List<File>): Result<String> = withContext(Dispatchers.IO) {
    simctl("addmedia", device.udid, *files.map { it.absolutePath }.toTypedArray()).toResult()
        .map { "Added ${files.size} file${if (files.size == 1) "" else "s"} to ${device.name}" }
}

// --- Pasteboard ---

/** Text on the device pasteboard. Phones go through devicectl, which prints the raw content. */
suspend fun IosService.pasteboardGet(device: IosDevice): Result<String> = withContext(Dispatchers.IO) {
    val res = if (device.isSimulator) simctl("pbpaste", device.udid)
    else Exec.capture(listOf("xcrun", "devicectl", "device", "pasteboard", "paste", "--device", device.udid, "--quiet"))
    if (res.isSuccess) Result.success(res.stdout)
    else res.toResult()
}

suspend fun IosService.pasteboardSet(device: IosDevice, text: String): Result<String> = withContext(Dispatchers.IO) {
    val res = if (device.isSimulator) simctl("pbcopy", device.udid, stdin = text)
    else Exec.capture(
        listOf("xcrun", "devicectl", "device", "pasteboard", "copy", "--device", device.udid, "--quiet"),
        stdin = text,
    )
    res.toResult().map { "Copied ${text.length} characters to the device pasteboard" }
}

// --- Helpers ---

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content ?: ""

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1e3)
    else -> "$bytes B"
}
