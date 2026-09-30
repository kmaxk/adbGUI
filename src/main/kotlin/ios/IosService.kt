package ios

import device.Device
import device.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import process.Exec
import process.ExecResult

enum class IosKind { Simulator, Physical }

data class IosDevice(
    val udid: String,
    override val name: String,
    val osVersion: String,
    val kind: IosKind,
    /** Simulators: "Booted", "Shutdown", "Booting", ... Physical: "Connected". */
    val state: String,
    /** Physical only: "wired" or "localNetwork" as reported by devicectl. */
    val transport: String? = null,
    /** Physical: marketing name (e.g. "iPhone 12"); simulators: device type (e.g. "iPhone 18 Pro"). */
    val model: String? = null,
) : Device {
    override val id get() = udid
    override val platform get() = Platform.IOS
    val isSimulator get() = kind == IosKind.Simulator
    val isBooted get() = state == "Booted"
    override val isReady get() = kind == IosKind.Physical || isBooted
}

/**
 * iOS counterpart to AdbService. Simulators go through `xcrun simctl`, physical
 * devices through `xcrun devicectl`. macOS only: on other systems [available] is
 * false and nothing is ever executed.
 *
 * Feature files in this package add their calls as extension functions on this
 * object and use [simctl], [devicectl] and [devicectlJson] as building blocks.
 */
object IosService {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val available: Boolean by lazy {
        System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
            runCatching { Exec.capture(listOf("xcrun", "--find", "simctl")).isSuccess }.getOrDefault(false)
    }

    private const val DEVICE_POLL_INTERVAL_MS = 3000L

    // --- Building blocks ---

    /** `xcrun simctl <args>`, stdout and stderr kept apart. */
    fun simctl(vararg args: String, stdin: String? = null): ExecResult =
        Exec.capture(listOf("xcrun", "simctl", *args), stdin)

    /** `xcrun devicectl <args>` with human-readable output. */
    fun devicectl(vararg args: String): ExecResult =
        Exec.capture(listOf("xcrun", "devicectl", *args))

    /**
     * `xcrun devicectl <args> --json-output -` parsed to the `result` object.
     * Fails with devicectl's error text when the command fails.
     */
    fun devicectlJson(vararg args: String): Result<JsonElement> {
        val res = Exec.capture(listOf("xcrun", "devicectl", *args, "--json-output", "-", "--quiet"))
        return runCatching {
            val root = json.parseToJsonElement(res.stdout).jsonObject
            val outcome = root["info"]?.jsonObject?.get("outcome")?.jsonPrimitive?.content
            if (outcome != null && outcome != "success") {
                val err = root["error"]?.jsonObject
                // Plain string in some Xcode versions, {"string": "..."} in others
                val desc = (err?.get("userInfo") as? JsonObject)?.get("NSLocalizedDescription")
                val msg = ((desc as? JsonPrimitive) ?: ((desc as? JsonObject)?.get("string") as? JsonPrimitive))?.content
                    ?: res.stderr.trim().ifEmpty { "devicectl failed" }
                throw Exception(msg)
            }
            root["result"] ?: JsonObject(emptyMap())
        }.recoverCatching { e ->
            // Non-JSON stdout (e.g. invalid arguments) — surface stderr instead of a parse error
            if (e is kotlinx.serialization.SerializationException) {
                throw Exception(res.stderr.trim().ifEmpty { res.stdout.trim() }.ifEmpty { "devicectl failed" })
            }
            throw e
        }
    }

    /** Converts a failed [ExecResult] into a failure carrying its stderr. */
    fun ExecResult.toResult(): Result<String> =
        if (isSuccess) Result.success(stdout.trim())
        else Result.failure(Exception(stderr.trim().ifEmpty { stdout.trim() }.ifEmpty { "exit code $exitCode" }))

    // --- Devices ---

    suspend fun devices(): List<IosDevice> = withContext(Dispatchers.IO) {
        if (!available) return@withContext emptyList()
        simulators() + physicalDevices()
    }

    fun deviceTrackFlow(): Flow<List<IosDevice>> = flow {
        if (!available) {
            emit(emptyList())
            return@flow
        }
        while (currentCoroutineContext().isActive) {
            emit(runCatching { devices() }.getOrDefault(emptyList()))
            delay(DEVICE_POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun simulators(): List<IosDevice> {
        val res = simctl("list", "devices", "available", "-j")
        if (!res.isSuccess) return emptyList()
        val byRuntime = json.parseToJsonElement(res.stdout).jsonObject["devices"]?.jsonObject ?: return emptyList()
        return byRuntime.flatMap { (runtime, devices) ->
            // "com.apple.CoreSimulator.SimRuntime.iOS-27-0" -> "27.0"
            val suffix = runtime.substringAfterLast('.')
            if (!suffix.startsWith("iOS-")) return@flatMap emptyList()
            val version = suffix.removePrefix("iOS-").replace('-', '.')
            devices.jsonArray.map { d ->
                val o = d.jsonObject
                IosDevice(
                    udid = o.str("udid"),
                    name = o.str("name"),
                    osVersion = version,
                    kind = IosKind.Simulator,
                    state = o.str("state"),
                    model = o["deviceTypeIdentifier"]?.jsonPrimitive?.content
                        ?.substringAfterLast('.')?.replace('-', ' '),
                )
            }
        }
    }

    private fun physicalDevices(): List<IosDevice> {
        val result = devicectlJson("list", "devices").getOrNull() ?: return emptyList()
        val devices = result.jsonObject["devices"]?.jsonArray ?: return emptyList()
        return devices.mapNotNull { d ->
            val o = d.jsonObject
            val conn = o["connectionProperties"]?.jsonObject
            // Paired-but-absent devices have no transport; only list reachable ones
            val transport = conn?.get("transportType")?.jsonPrimitive?.content ?: return@mapNotNull null
            val props = o["deviceProperties"]?.jsonObject
            val hw = o["hardwareProperties"]?.jsonObject
            if (hw?.get("platform")?.jsonPrimitive?.content != "iOS") return@mapNotNull null
            // Booted simulators show up here too ("simulated"); simctl already lists them
            if (hw["reality"]?.jsonPrimitive?.content != "physical") return@mapNotNull null
            IosDevice(
                // devicectl accepts the hardware UDID for --device
                udid = hw["udid"]?.jsonPrimitive?.content ?: o.str("identifier"),
                name = props?.get("name")?.jsonPrimitive?.content ?: "iOS device",
                osVersion = props?.get("osVersionNumber")?.jsonPrimitive?.content ?: "",
                kind = IosKind.Physical,
                state = "Connected",
                transport = transport,
                model = hw["marketingName"]?.jsonPrimitive?.content,
            )
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: ""

    // --- Power ---

    suspend fun boot(device: IosDevice): Result<Unit> = withContext(Dispatchers.IO) {
        simctl("boot", device.udid).toResult().map { }
    }

    suspend fun shutdown(device: IosDevice): Result<Unit> = withContext(Dispatchers.IO) {
        simctl("shutdown", device.udid).toResult().map { }
    }

    /** Brings Simulator.app to the front showing [device]. */
    suspend fun openSimulatorApp(device: IosDevice): Result<Unit> = withContext(Dispatchers.IO) {
        Exec.capture(listOf("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", device.udid)).toResult().map { }
    }

    // --- Deeplinks ---

    suspend fun openUrl(device: IosDevice, url: String): Result<String> = withContext(Dispatchers.IO) {
        if (device.isSimulator) {
            simctl("openurl", device.udid, url).toResult().map { "Opened on ${device.name}" }
        } else {
            devicectlJson("device", "process", "openURL", "--device", device.udid, url)
                .map { "Opened on ${device.name}" }
        }
    }
}
