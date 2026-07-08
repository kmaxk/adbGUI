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
    var isLoading by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var rebootTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }

    fun refresh() {
        scope.launch {
            isLoading = true
            info = runCatching { AdbService.deviceInfo(device.serial) }.getOrNull()
            forwards = runCatching { AdbService.listForwards(device.serial) }.getOrDefault(emptyList())
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

        feedback?.let { (success, msg) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (success) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
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

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
