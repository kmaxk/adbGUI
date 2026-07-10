package ui

import adb.AdbService
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        }
    }
}
