package ui

import adb.AdbDevice
import adb.AdbService
import adb.DeviceInfo
import adb.PortForward
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var forwards by remember { mutableStateOf<List<PortForward>>(emptyList()) }
    var display by remember { mutableStateOf<Pair<String, String>?>(null) }
    var props by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var rebootTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }

    fun refresh() {
        scope.launch {
            isLoading = true
            info = runCatching { AdbService.deviceInfo(device.serial) }.getOrNull()
            forwards = runCatching { AdbService.listForwards(device.serial) }.getOrDefault(emptyList())
            display = runCatching { AdbService.displayState(device.serial) }.getOrNull()
            props = runCatching { AdbService.getProps(device.serial) }.getOrDefault(emptyMap())
            isLoading = false
        }
    }

    LaunchedEffect(device.serial) {
        feedback = null
        refresh()
    }

    rebootTarget?.let { (label, mode) ->
        AlertDialog(
            onDismissRequest = { rebootTarget = null },
            title = { Text("Reboot device?") },
            text = { Text("${device.displayName} will reboot ${if (mode == null) "normally" else "into $label"}.") },
            confirmButton = {
                Button(
                    onClick = {
                        rebootTarget = null
                        scope.launch {
                            AdbService.reboot(device.serial, mode)
                            feedback = true to "Reboot ($label) sent"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Reboot") }
            },
            dismissButton = {
                TextButton(onClick = { rebootTarget = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { refresh() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, "Refresh", modifier = Modifier.size(18.dp))
                }
            }
        }

        AnimatedFade(feedback) { (success, msg) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (success) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
                border = appCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = if (success) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        msg,
                        color = if (success) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        SectionCard("Device Info", Icons.Filled.Info) {
            val i = info
            if (i == null) {
                Text(
                    if (isLoading) "Loading…" else "No info available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                InfoRow("Model", "${i.manufacturer} ${i.model}")
                InfoRow("Android", "${i.androidVersion} (API ${i.sdkLevel})")
                InfoRow("Build", i.buildNumber)
                InfoRow("ABI", i.abi)
                InfoRow("Resolution", i.resolution)
                InfoRow("IP address", i.ipAddress ?: "–")
                InfoRow("Battery", "${i.batteryLevel} · ${i.batteryStatus} · ${i.batteryTemp} · ${i.batteryHealth}")
                InfoRow("Serial", device.serial)
            }
        }

        WirelessSection(
            device = device,
            deviceIp = info?.ipAddress,
            onFeedback = { feedback = it },
        )

        ForwardSection(
            device = device,
            forwards = forwards,
            onChanged = { refresh() },
            onFeedback = { feedback = it },
        )

        DisplaySection(
            device = device,
            display = display,
            onChanged = { refresh() },
            onFeedback = { feedback = it },
        )

        DeeplinkSection(
            device = device,
            onFeedback = { feedback = it },
        )

        PropsSection(props = props)

        SectionCard("Reboot", Icons.Filled.RestartAlt) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rebootTarget = "System" to null }) {
                    Icon(Icons.Filled.RestartAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("System")
                }
                OutlinedButton(onClick = { rebootTarget = "Recovery" to "recovery" }) {
                    Icon(Icons.Filled.Healing, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Recovery")
                }
                OutlinedButton(onClick = { rebootTarget = "Bootloader" to "bootloader" }) {
                    Icon(Icons.Filled.Memory, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bootloader")
                }
            }
        }
    }
}

@Composable
private fun WirelessSection(
    device: AdbDevice,
    deviceIp: String?,
    onFeedback: (Pair<Boolean, String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var connectAddress by remember(deviceIp) { mutableStateOf(deviceIp?.let { "$it:5555" } ?: "") }
    var pairAddress by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Result<String>) {
        scope.launch {
            isBusy = true
            val result = block()
            isBusy = false
            onFeedback(
                if (result.isSuccess) true to (result.getOrNull() ?: "OK")
                else false to "Failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    SectionCard("Wireless ADB", Icons.Filled.Wifi) {
        Text(
            "Enable TCP/IP on the USB-connected device, then connect via Wi-Fi. Android 11+ can pair directly (Developer options → Wireless debugging).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !isBusy,
                onClick = { run { AdbService.enableTcpip(device.serial) } }
            ) {
                Icon(Icons.Filled.SettingsEthernet, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Enable TCP/IP :5555")
            }
            if (isBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = connectAddress,
                onValueChange = { connectAddress = it },
                label = { Text("ip:port") },
                placeholder = { Text("192.168.1.42:5555") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                enabled = !isBusy && connectAddress.isNotBlank(),
                onClick = { run { AdbService.connect(connectAddress.trim()) } }
            ) { Text("Connect") }
            OutlinedButton(
                enabled = !isBusy && connectAddress.isNotBlank(),
                onClick = { run { AdbService.disconnect(connectAddress.trim()) } }
            ) { Text("Disconnect") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = pairAddress,
                onValueChange = { pairAddress = it },
                label = { Text("Pairing ip:port") },
                placeholder = { Text("192.168.1.42:37123") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = pairCode,
                onValueChange = { pairCode = it },
                label = { Text("Code") },
                placeholder = { Text("123456") },
                modifier = Modifier.width(120.dp),
                singleLine = true,
            )
            Button(
                enabled = !isBusy && pairAddress.isNotBlank() && pairCode.isNotBlank(),
                onClick = { run { AdbService.pair(pairAddress.trim(), pairCode.trim()) } }
            ) { Text("Pair") }
        }
    }
}

@Composable
private fun ForwardSection(
    device: AdbDevice,
    forwards: List<PortForward>,
    onChanged: () -> Unit,
    onFeedback: (Pair<Boolean, String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var localPort by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("") }

    SectionCard("Port Forwarding", Icons.Filled.SwapHoriz) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = localPort,
                onValueChange = { localPort = it.filter(Char::isDigit) },
                label = { Text("Local port") },
                placeholder = { Text("8080") },
                modifier = Modifier.width(140.dp),
                singleLine = true,
            )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(16.dp))
            OutlinedTextField(
                value = remotePort,
                onValueChange = { remotePort = it.filter(Char::isDigit) },
                label = { Text("Device port") },
                placeholder = { Text("8080") },
                modifier = Modifier.width(140.dp),
                singleLine = true,
            )
            Button(
                enabled = localPort.toIntOrNull() in 1..65535 && remotePort.toIntOrNull() in 1..65535,
                onClick = {
                    scope.launch {
                        val result = AdbService.addForward(device.serial, localPort.toInt(), remotePort.toInt())
                        if (result.isSuccess) {
                            onFeedback(true to "Forward tcp:$localPort → tcp:$remotePort added")
                            localPort = ""; remotePort = ""
                            onChanged()
                        } else {
                            onFeedback(false to "Failed: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }
            ) { Text("Add") }
        }

        if (forwards.isEmpty()) {
            Text(
                "No active forwards",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            forwards.forEach { forward ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${forward.local}  →  ${forward.remote}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val result = AdbService.removeForward(device.serial, forward.local)
                                if (result.isSuccess) {
                                    onFeedback(true to "Forward ${forward.local} removed")
                                    onChanged()
                                } else {
                                    onFeedback(false to "Failed: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private data class DisplayPreset(val name: String, val width: Int, val height: Int, val dpi: Int) {
    val size get() = "${width}x$height"
}

// Ordered by physical diagonal, smallest to biggest device per class.
private val PhonePresets = listOf(
    DisplayPreset("Legacy 4.0\"", 480, 854, 240),
    DisplayPreset("Compact HD 4.6\"", 720, 1280, 320),
    DisplayPreset("FHD 5.2\"", 1080, 1920, 420),
    DisplayPreset("Pixel 3", 1080, 2280, 440),
    DisplayPreset("Galaxy S23", 1080, 2340, 425),
    DisplayPreset("Budget HD+ 6.3\"", 720, 1600, 280),
    DisplayPreset("Pixel 8", 1080, 2400, 420),
    DisplayPreset("Pixel 7 Pro", 1440, 3120, 512),
    DisplayPreset("Pixel 9 Pro XL", 1344, 2992, 480),
    DisplayPreset("S20 Ultra", 1440, 3200, 511),
)

private val TabletPresets = listOf(
    DisplayPreset("Fold inner 7.6\"", 1812, 2176, 420),
    DisplayPreset("Nexus 7", 1200, 1920, 320),
    DisplayPreset("Tab 7\" WXGA", 800, 1280, 213),
    DisplayPreset("Nexus 9", 1536, 2048, 320),
    DisplayPreset("Tab S9 11\"", 1600, 2560, 320),
    DisplayPreset("Tab S9+ 12.4\"", 1752, 2800, 340),
    DisplayPreset("Lenovo P11", 1200, 2000, 240),
    DisplayPreset("Pixel C", 1800, 2560, 308),
    DisplayPreset("Pixel Tablet", 1600, 2560, 276),
    DisplayPreset("Tab S9 Ultra", 1848, 2960, 320),
)

/** Smallest-width dp from `wm size` / `wm density` output; tablet when >= 600dp. */
private fun isTabletDisplay(display: Pair<String, String>): Boolean? {
    val (sizeOut, densityOut) = display
    val match = Regex("""(\d+)x(\d+)""").find(sizeOut) ?: return null
    val (w, h) = match.destructured
    val dpi = Regex("""Physical density:\s*(\d+)""").find(densityOut)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""(\d+)""").find(densityOut)?.value?.toIntOrNull()
        ?: return null
    val swDp = minOf(w.toInt(), h.toInt()) * 160 / dpi
    return swDp >= 600
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplaySection(
    device: AdbDevice,
    display: Pair<String, String>?,
    onChanged: () -> Unit,
    onFeedback: (Pair<Boolean, String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf("") }
    var density by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> Result<Unit>) {
        scope.launch {
            isBusy = true
            val result = block()
            isBusy = false
            onFeedback(
                if (result.isSuccess) true to label
                else false to "Failed: ${result.exceptionOrNull()?.message}"
            )
            if (result.isSuccess) onChanged()
        }
    }

    SectionCard("Display", Icons.Filled.AspectRatio) {
        display?.let { (sizeState, densityState) ->
            Text(
                (sizeState.lines() + densityState.lines()).joinToString(" · ") { it.trim() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        display?.let { isTabletDisplay(it) }?.let { isTablet ->
            val presets = if (isTablet) TabletPresets else PhonePresets
            Text(
                if (isTablet) "Tablet presets (smallest → biggest)" else "Phone presets (smallest → biggest)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                presets.forEach { preset ->
                    AssistChip(
                        enabled = !isBusy,
                        onClick = {
                            run("${preset.name}: ${preset.size} @ ${preset.dpi}dpi") {
                                val sizeResult = AdbService.setDisplaySize(device.serial, preset.size)
                                if (sizeResult.isFailure) sizeResult
                                else AdbService.setDensity(device.serial, preset.dpi.toString())
                            }
                        },
                        label = {
                            Text(
                                "${preset.name} · ${preset.size} · ${preset.dpi}dpi",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = size,
                onValueChange = { size = it },
                label = { Text("Size (WxH)") },
                placeholder = { Text("1080x2400") },
                modifier = Modifier.width(180.dp),
                singleLine = true,
            )
            Button(
                enabled = !isBusy && size.matches(Regex("""\d+x\d+""")),
                onClick = { run("Display size set to $size") { AdbService.setDisplaySize(device.serial, size.trim()) } }
            ) { Text("Apply") }
            OutlinedTextField(
                value = density,
                onValueChange = { density = it.filter(Char::isDigit) },
                label = { Text("Density (dpi)") },
                placeholder = { Text("440") },
                modifier = Modifier.width(140.dp),
                singleLine = true,
            )
            Button(
                enabled = !isBusy && density.toIntOrNull() != null,
                onClick = { run("Density set to $density dpi") { AdbService.setDensity(device.serial, density.trim()) } }
            ) { Text("Apply") }
            OutlinedButton(
                enabled = !isBusy,
                onClick = {
                    run("Display reset") {
                        AdbService.setDisplaySize(device.serial, null)
                        AdbService.setDensity(device.serial, null)
                    }
                }
            ) { Text("Reset") }
            if (isBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun DeeplinkSection(
    device: AdbDevice,
    onFeedback: (Pair<Boolean, String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    SectionCard("Deeplink / URL", Icons.Filled.Link) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL or deeplink") },
                placeholder = { Text("https://example.com or myapp://path") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                enabled = !isBusy && url.isNotBlank(),
                onClick = {
                    scope.launch {
                        isBusy = true
                        val result = AdbService.openUrl(device.serial, url.trim())
                        isBusy = false
                        onFeedback(
                            if (result.isSuccess) true to "Opened: ${url.trim()}"
                            else false to "Failed: ${result.exceptionOrNull()?.message}"
                        )
                    }
                }
            ) {
                Icon(Icons.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open")
            }
            if (isBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

private const val MAX_PROP_RESULTS = 30

@Composable
private fun PropsSection(props: Map<String, String>) {
    var search by remember { mutableStateOf("") }

    SectionCard("System Properties", Icons.Filled.Tune) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search ${props.size} properties…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = if (search.isNotEmpty()) {
                { IconButton(onClick = { search = "" }, modifier = Modifier.size(18.dp)) {
                    Icon(Icons.Filled.Clear, "Clear", modifier = Modifier.size(14.dp))
                }}
            } else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (search.isNotBlank()) {
            val matches = props.entries
                .filter { it.key.contains(search, ignoreCase = true) || it.value.contains(search, ignoreCase = true) }
            matches.take(MAX_PROP_RESULTS).forEach { (key, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        key,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(0.5f)
                    )
                    Text(
                        value.ifEmpty { "–" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(0.5f)
                    )
                }
            }
            if (matches.size > MAX_PROP_RESULTS) {
                Text(
                    "… ${matches.size - MAX_PROP_RESULTS} more — refine search",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (matches.isEmpty()) {
                Text(
                    "No matches",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = appCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
