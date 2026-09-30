package ui

import adb.AdbService
import ios.IosService
import ios.idevicesyslogPath
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import process.Exec
import settings.AppSettings

@Composable
fun SettingsScreen() {
    var adbPath by remember { mutableStateOf(AppSettings.adbPath.ifEmpty { AdbService.adbPath }) }
    var saved by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = appCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Terminal, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("ADB Binary", style = MaterialTheme.typography.titleSmall)
                    }

                    OutlinedTextField(
                        value = adbPath,
                        onValueChange = { adbPath = it; saved = false },
                        label = { Text("Path") },
                        placeholder = { Text("/opt/homebrew/bin/adb") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = AppMonoFamily),
                        supportingText = { Text("Empty = auto-detect on next launch") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        )
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = {
                            val trimmed = adbPath.trim()
                            AppSettings.adbPath = trimmed
                            AdbService.adbPath = trimmed.ifEmpty { AdbService.detectAdb() }
                            saved = true
                        }) {
                            Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }

                        OutlinedButton(onClick = {
                            adbPath = AdbService.detectAdb()
                            saved = false
                        }) {
                            Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Auto-detect")
                        }

                        if (saved) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Text("Saved", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = appCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Info,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Active: ${AdbService.adbPath}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // `available` runs `xcrun --find` on first read; keep that off the UI thread
            val iosAvailable by produceState(false) { value = withContext(Dispatchers.IO) { IosService.available } }
            if (iosAvailable) IosToolsCard()
        }
    }
}

/** Read-only status of the Apple tooling iOS support relies on. */
@Composable
private fun IosToolsCard() {
    var developerDir by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    val syslogPath = remember { IosService.idevicesyslogPath }

    LaunchedEffect(Unit) {
        developerDir = withContext(Dispatchers.IO) {
            runCatching { Exec.capture(listOf("xcode-select", "-p")) }.getOrNull()
                ?.takeIf { it.isSuccess }?.stdout?.trim()?.ifEmpty { null }
        }
        checked = true
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = appCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.PhoneIphone, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("iOS", style = MaterialTheme.typography.titleSmall)
            }

            IosToolRow(
                "Xcode developer directory",
                developerDir ?: if (checked) "Not set. Install Xcode, then run: sudo xcode-select -s /Applications/Xcode.app" else "…",
                found = developerDir != null,
            )

            IosToolRow(
                "idevicesyslog (live logs on physical devices)",
                syslogPath ?: "Not found. Install it with: brew install libimobiledevice",
                found = syslogPath != null,
            )
        }
    }
}

@Composable
private fun IosToolRow(label: String, value: String, found: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                if (found) Icons.Filled.CheckCircle else Icons.Filled.Info,
                null,
                modifier = Modifier.size(14.dp),
                tint = if (found) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
