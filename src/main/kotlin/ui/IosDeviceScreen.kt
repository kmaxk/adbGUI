package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ios.IosDevice
import ios.IosDeviceDetails
import ios.IosService
import ios.PrivacyAction
import ios.addMedia
import ios.appearance
import ios.clearLocation
import ios.clearStatusBar
import ios.deviceDetails
import ios.erase
import ios.overrideStatusBar
import ios.pasteboardGet
import ios.pasteboardSet
import ios.privacy
import ios.privacyServices
import ios.reboot
import ios.sendPush
import ios.setAppearance
import ios.setLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame

/** Runs a device call and reports its message in the feedback card. */
private typealias RunAction = (suspend () -> Result<String>) -> Unit

@Composable
fun IosDeviceScreen(device: IosDevice) {
    val scope = rememberCoroutineScope()
    var details by remember(device.udid) { mutableStateOf<IosDeviceDetails?>(null) }
    var detailsError by remember(device.udid) { mutableStateOf<String?>(null) }
    var appearance by remember(device.udid) { mutableStateOf<String?>(null) }
    var isLoading by remember(device.udid) { mutableStateOf(false) }
    var isBusy by remember(device.udid) { mutableStateOf(false) }
    var feedback by remember(device.udid) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirm by remember(device.udid) { mutableStateOf<ConfirmAction?>(null) }
    var bundleId by remember(device.udid) { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            isLoading = true
            IosService.deviceDetails(device).fold(
                onSuccess = { details = it; detailsError = null },
                onFailure = { detailsError = it.message },
            )
            appearance = IosService.appearance(device).getOrNull()
            isLoading = false
        }
    }

    val run: RunAction = { block ->
        scope.launch {
            isBusy = true
            val result = block()
            isBusy = false
            feedback = result.fold(
                onSuccess = { true to it.ifBlank { "Done" } },
                onFailure = { false to "Failed: ${it.message}" },
            )
        }
    }

    LaunchedEffect(device.udid) {
        feedback = null
        refresh()
    }

    // Unknown capabilities (simulator, or the phone didn't answer) hide nothing
    fun supports(capability: String) = device.isSimulator || details?.capabilities?.contains(capability) ?: true

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                Button(
                    onClick = {
                        confirm = null
                        run(action.block)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(action.confirmLabel) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { refresh() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, "Refresh", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        FeedbackCard(feedback)

        SectionCard("Device Info", Icons.Filled.Info) {
            val d = details
            when {
                d != null -> SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        d.rows.forEach { (label, value) -> DetailRow(label, value) }
                    }
                }
                else -> Text(
                    when {
                        isLoading -> "Loading…"
                        detailsError != null -> "No info available: $detailsError"
                        else -> "No info available"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        PowerSection(
            device = device,
            canReboot = supports("rebootdevice"),
            isBusy = isBusy,
            run = run,
            onConfirm = { confirm = it },
        )

        if (supports("customizeuistyle")) {
            AppearanceSection(
                current = appearance,
                isBusy = isBusy,
                onSet = { dark ->
                    run {
                        IosService.setAppearance(device, dark).onSuccess {
                            appearance = IosService.appearance(device).getOrNull() ?: if (dark) "dark" else "light"
                        }
                    }
                },
            )
        }

        StatusBarSection(device, isBusy, run)

        if (supports("simulatelocation")) {
            LocationSection(device, isBusy, run)
        }

        if (supports("pasteboard")) {
            PasteboardSection(device, isBusy, run)
        }

        if (device.isSimulator) {
            PushSection(device, bundleId, { bundleId = it }, isBusy, run)
            PrivacySection(device, bundleId, { bundleId = it }, isBusy, run)
            MediaSection(device, isBusy, run)
        }
    }
}

private class ConfirmAction(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val block: suspend () -> Result<String>,
)

@Composable
private fun FeedbackCard(feedback: Pair<Boolean, String>?) {
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
                val tint = if (success) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer
                Icon(
                    if (success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = tint
                )
                Text(msg, color = tint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PowerSection(
    device: IosDevice,
    canReboot: Boolean,
    isBusy: Boolean,
    run: RunAction,
    onConfirm: (ConfirmAction) -> Unit,
) {
    if (!device.isSimulator && !canReboot) return
    SectionCard("Power", Icons.Filled.PowerSettingsNew) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (device.isSimulator) {
                OutlinedButton(
                    enabled = !isBusy,
                    onClick = { run { IosService.shutdown(device).map { "${device.name} shut down" } } }
                ) {
                    ButtonIcon(Icons.Filled.PowerSettingsNew)
                    Text("Shutdown")
                }
                OutlinedButton(enabled = !isBusy, onClick = { run { IosService.reboot(device) } }) {
                    ButtonIcon(Icons.Filled.RestartAlt)
                    Text("Reboot")
                }
                OutlinedButton(
                    enabled = !isBusy,
                    onClick = {
                        onConfirm(
                            ConfirmAction(
                                title = "Erase simulator?",
                                message = "All apps, data and settings on ${device.name} are deleted. " +
                                    "The simulator is shut down for this and booted again afterwards.",
                                confirmLabel = "Erase",
                            ) { IosService.erase(device) }
                        )
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    ButtonIcon(Icons.Filled.DeleteForever)
                    Text("Erase…")
                }
            } else {
                OutlinedButton(
                    enabled = !isBusy,
                    onClick = {
                        onConfirm(
                            ConfirmAction(
                                title = "Reboot device?",
                                message = "${device.name} will restart and be unavailable until it's back up and unlocked.",
                                confirmLabel = "Reboot",
                            ) { IosService.reboot(device) }
                        )
                    }
                ) {
                    ButtonIcon(Icons.Filled.RestartAlt)
                    Text("Reboot…")
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(current: String?, isBusy: Boolean, onSet: (dark: Boolean) -> Unit) {
    SectionCard("Appearance", Icons.Filled.Contrast) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(false to "Light", true to "Dark").forEach { (dark, label) ->
                val selected = current == label.lowercase()
                val icon = if (dark) Icons.Filled.DarkMode else Icons.Filled.LightMode
                if (selected) {
                    Button(enabled = !isBusy, onClick = { onSet(dark) }) {
                        ButtonIcon(icon)
                        Text(label)
                    }
                } else {
                    OutlinedButton(enabled = !isBusy, onClick = { onSet(dark) }) {
                        ButtonIcon(icon)
                        Text(label)
                    }
                }
            }
            Text(
                "Current: ${current ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBarSection(device: IosDevice, isBusy: Boolean, run: RunAction) {
    SectionCard("Status Bar", Icons.Filled.SignalCellularAlt) {
        Text(
            "Apple's screenshot look: 9:41, full battery, full Wi-Fi and cellular signal." +
                if (device.isSimulator) "" else " Needs a recent iOS version on the device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !isBusy, onClick = { run { IosService.overrideStatusBar(device) } }) {
                ButtonIcon(Icons.Filled.Schedule)
                Text("Override")
            }
            OutlinedButton(enabled = !isBusy, onClick = { run { IosService.clearStatusBar(device) } }) {
                Text("Clear")
            }
        }
    }
}

private data class LocationPreset(val name: String, val latitude: Double, val longitude: Double)

private val LocationPresets = listOf(
    LocationPreset("Apple Park", 37.334900, -122.009020),
    LocationPreset("San Francisco", 37.774900, -122.419400),
    LocationPreset("New York", 40.712800, -74.006000),
    LocationPreset("London", 51.507400, -0.127800),
    LocationPreset("Berlin", 52.520008, 13.404954),
    LocationPreset("Tokyo", 35.676200, 139.650300),
    LocationPreset("Sydney", -33.868800, 151.209300),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationSection(device: IosDevice, isBusy: Boolean, run: RunAction) {
    var latitude by remember(device.udid) { mutableStateOf("") }
    var longitude by remember(device.udid) { mutableStateOf("") }
    val lat = latitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lon = longitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }

    SectionCard("Location", Icons.Filled.LocationOn) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            LocationPresets.forEach { preset ->
                AssistChip(
                    enabled = !isBusy,
                    onClick = {
                        latitude = preset.latitude.toString()
                        longitude = preset.longitude.toString()
                        run {
                            IosService.setLocation(device, preset.latitude, preset.longitude)
                                .map { "Location set to ${preset.name}" }
                        }
                    },
                    label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = latitude,
                onValueChange = { latitude = it },
                label = { Text("Latitude") },
                placeholder = { Text("52.5200") },
                isError = latitude.isNotBlank() && lat == null,
                modifier = Modifier.width(160.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = longitude,
                onValueChange = { longitude = it },
                label = { Text("Longitude") },
                placeholder = { Text("13.4050") },
                isError = longitude.isNotBlank() && lon == null,
                modifier = Modifier.width(160.dp),
                singleLine = true,
            )
            Button(
                enabled = !isBusy && lat != null && lon != null,
                onClick = { if (lat != null && lon != null) run { IosService.setLocation(device, lat, lon) } }
            ) { Text("Set") }
            OutlinedButton(enabled = !isBusy, onClick = { run { IosService.clearLocation(device) } }) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun PasteboardSection(device: IosDevice, isBusy: Boolean, run: RunAction) {
    var text by remember(device.udid) { mutableStateOf("") }

    SectionCard("Pasteboard", Icons.Filled.ContentPaste) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Text to copy to the device, or press Get to read its pasteboard") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 160.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !isBusy,
                onClick = {
                    run {
                        IosService.pasteboardGet(device).map {
                            text = it
                            if (it.isEmpty()) "Device pasteboard is empty" else "Read ${it.length} characters from the device pasteboard"
                        }
                    }
                }
            ) {
                ButtonIcon(Icons.Filled.Download)
                Text("Get")
            }
            Button(enabled = !isBusy && text.isNotEmpty(), onClick = { run { IosService.pasteboardSet(device, text) } }) {
                ButtonIcon(Icons.Filled.Upload)
                Text("Set")
            }
        }
    }
}

private val DefaultPushPayload = """
{
  "aps": {
    "alert": {
      "title": "adbGUI",
      "body": "Test notification"
    },
    "sound": "default",
    "badge": 1
  }
}
""".trimIndent()

@Composable
private fun PushSection(
    device: IosDevice,
    bundleId: String,
    onBundleIdChange: (String) -> Unit,
    isBusy: Boolean,
    run: RunAction,
) {
    var payload by remember { mutableStateOf(DefaultPushPayload) }

    SectionCard("Push Notification", Icons.Filled.Notifications) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            BundleIdField(bundleId, onBundleIdChange, Modifier.weight(1f))
            Button(
                enabled = !isBusy && bundleId.isNotBlank() && payload.isNotBlank(),
                onClick = { run { IosService.sendPush(device, bundleId.trim(), payload) } }
            ) {
                ButtonIcon(Icons.AutoMirrored.Filled.Send)
                Text("Send")
            }
            TextButton(enabled = payload != DefaultPushPayload, onClick = { payload = DefaultPushPayload }) {
                Text("Reset")
            }
        }
        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            label = { Text("Payload (JSON with an \"aps\" key, max 4 KB)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacySection(
    device: IosDevice,
    bundleId: String,
    onBundleIdChange: (String) -> Unit,
    isBusy: Boolean,
    run: RunAction,
) {
    var service by remember { mutableStateOf("photos") }
    var expanded by remember { mutableStateOf(false) }

    fun act(action: PrivacyAction) = run {
        IosService.privacy(device, action, service, bundleId.trim().ifEmpty { null })
    }

    SectionCard("Privacy & Permissions", Icons.Filled.PrivacyTip) {
        Text(
            "Grant or revoke without the system prompt. Reset works without a bundle ID to reset all apps. " +
                "Some changes terminate the app if it's running.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.width(200.dp),
            ) {
                OutlinedTextField(
                    value = service,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Service") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    privacyServices.forEach { s ->
                        DropdownMenuItem(text = { Text(s) }, onClick = { service = s; expanded = false })
                    }
                }
            }
            BundleIdField(bundleId, onBundleIdChange, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !isBusy && bundleId.isNotBlank(), onClick = { act(PrivacyAction.Grant) }) { Text("Grant") }
            OutlinedButton(enabled = !isBusy && bundleId.isNotBlank(), onClick = { act(PrivacyAction.Revoke) }) {
                Text("Revoke")
            }
            OutlinedButton(enabled = !isBusy, onClick = { act(PrivacyAction.Reset) }) { Text("Reset") }
        }
    }
}

@Composable
private fun MediaSection(device: IosDevice, isBusy: Boolean, run: RunAction) {
    val scope = rememberCoroutineScope()

    SectionCard("Media", Icons.Filled.PhotoLibrary) {
        Text(
            "Add photos, videos (incl. Live Photos as photo + video pair) or vCard contacts to the simulator's library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            enabled = !isBusy,
            onClick = {
                scope.launch {
                    val files = withContext(Dispatchers.Swing) {
                        val dialog = FileDialog(null as Frame?, "Add media…", FileDialog.LOAD)
                        dialog.isMultipleMode = true
                        dialog.isVisible = true
                        dialog.files.toList()
                    }
                    if (files.isNotEmpty()) run { IosService.addMedia(device, files) }
                }
            }
        ) {
            ButtonIcon(Icons.Filled.AddPhotoAlternate)
            Text("Add media…")
        }
    }
}

@Composable
private fun BundleIdField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Bundle ID") },
        placeholder = { Text("com.example.app") },
        modifier = modifier,
        singleLine = true,
    )
}

@Composable
private fun ButtonIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, null, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
