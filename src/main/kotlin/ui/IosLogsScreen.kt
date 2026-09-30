package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ios.IosDevice
import ios.IosService
import ios.canStreamLogs
import ios.logFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val FILTER_DEBOUNCE_MS = 600L

@Composable
fun IosLogsScreen(device: IosDevice) {
    if (!IosService.canStreamLogs(device)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Filled.Terminal,
                "Live device logs need libimobiledevice",
                "Install it with: brew install libimobiledevice — then open this screen again.",
            )
        }
        return
    }

    val log = rememberLogViewState()
    var processInput by remember { mutableStateOf("") }
    var processFilter by remember { mutableStateOf("") }
    var streamError by remember { mutableStateOf<String?>(null) }
    var restartKey by remember { mutableStateOf(0) }

    // Restart the stream only once typing pauses
    LaunchedEffect(processInput) {
        delay(FILTER_DEBOUNCE_MS)
        processFilter = processInput.trim()
    }

    // Keyed on the device: switching devices or leaving the screen cancels the flow, which destroys the process
    LaunchedEffect(device.udid, processFilter, restartKey) {
        log.clear()
        streamError = null
        try {
            IosService.logFlow(device, processFilter).collect { entry ->
                log.append(entry.text, entry.level)
            }
            streamError = "Log stream ended. The ${if (device.isSimulator) "simulator" else "device"} may have shut down or disconnected."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val hint = if (device.isSimulator) "Check the process or subsystem filter and that the simulator is booted."
            else "Check that ${device.name} is unlocked, trusted and connected."
            streamError = "Log stream stopped: ${e.message?.trimEnd('.')}. $hint"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LogToolbar {
            OutlinedTextField(
                value = processInput,
                onValueChange = { processInput = it },
                label = {
                    Text(
                        if (device.isSimulator) "Process or subsystem" else "Process",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                placeholder = { Text("All processes", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.width(260.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                trailingIcon = if (processInput.isNotEmpty()) {
                    {
                        IconButton(onClick = { processInput = "" }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Filled.Clear, "Clear", modifier = Modifier.size(14.dp))
                        }
                    }
                } else null
            )

            LogFilterControls(log)

            LogFollowControls(log)

            LogExportButton(log, "ios-log-${device.name.replace(' ', '-')}.txt")

            LogToolbarButton(Icons.Filled.DeleteSweep, "Clear view") { log.clear() }
        }

        LogStatusRow(log) {
            if (processFilter.isNotEmpty()) {
                Text(
                    "● $processFilter",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppMonoFamily),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        AnimatedFade(streamError) { msg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                border = appCardBorder(),
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Error,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { restartKey++ }) { Text("Restart stream") }
                }
            }
        }

        LogLines(log, modifier = Modifier.fillMaxSize())
    }
}
