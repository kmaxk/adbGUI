package ui

import adb.AdbDevice
import adb.AdbService
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import settings.AppSettings

private data class StepResult(val line: String, val success: Boolean, val output: String)

private suspend fun runStep(serial: String, line: String): String {
    val parts = line.trim().split(Regex("\\s+"))
    return when (parts[0].lowercase()) {
        "tap" -> {
            AdbService.tap(serial, parts[1].toInt(), parts[2].toInt())
            "tapped ${parts[1]},${parts[2]}"
        }
        "swipe" -> {
            AdbService.swipe(
                serial, parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt(),
                parts.getOrNull(5)?.toInt() ?: 300,
            )
            "swiped"
        }
        "text" -> {
            val text = line.trim().substringAfter(" ")
            AdbService.inputText(serial, text)
            "typed: $text"
        }
        "key" -> {
            AdbService.shell(serial, "input keyevent ${parts[1]}")
            "key ${parts[1]}"
        }
        "wait" -> {
            delay(parts[1].toLong())
            "waited ${parts[1]}ms"
        }
        "monkey" -> {
            val pkg = parts.getOrNull(1)
            val count = parts.getOrNull(2) ?: "1"
            val cmd = if (pkg != null) "monkey -p $pkg $count" else "monkey $count"
            AdbService.shell(serial, cmd)
        }
        else -> AdbService.shell(serial, line.trim())
    }
}

private suspend fun runBatch(serial: String, script: String): List<StepResult> {
    val results = mutableListOf<StepResult>()
    for (rawLine in script.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val result = runCatching { runStep(serial, line) }
        results.add(
            StepResult(
                line = line,
                success = result.isSuccess,
                output = result.getOrNull() ?: (result.exceptionOrNull()?.message ?: "failed"),
            )
        )
    }
    return results
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    var script by remember { mutableStateOf("") }
    var batchName by remember { mutableStateOf("") }
    var savedBatches by remember { mutableStateOf(AppSettings.batchNames()) }
    var loadExpanded by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<StepResult>>(emptyList()) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Batch Commands", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        SectionCard("Script", Icons.Filled.PlaylistPlay) {
            Text(
                "One step per line. tap x y · swipe x1 y1 x2 y2 [ms] · text <words> · key <keycode> · " +
                    "wait <ms> · monkey [package] [count] · anything else runs as raw `adb shell`.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                placeholder = {
                    Text("tap 540 1200\ntext myuser@example.com\ntap 540 1400\ntext mypassword\nkey KEYCODE_ENTER")
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    enabled = !isRunning && script.isNotBlank(),
                    onClick = {
                        scope.launch {
                            isRunning = true
                            results = runBatch(device.serial, script)
                            isRunning = false
                        }
                    }
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run")
                }
                if (isRunning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = batchName,
                    onValueChange = { batchName = it },
                    label = { Text("Name") },
                    modifier = Modifier.width(160.dp),
                    singleLine = true,
                )
                OutlinedButton(
                    enabled = batchName.isNotBlank() && script.isNotBlank(),
                    onClick = {
                        AppSettings.saveBatch(batchName.trim(), script)
                        savedBatches = AppSettings.batchNames()
                    }
                ) { Text("Save") }
                ExposedDropdownMenuBox(
                    expanded = loadExpanded,
                    onExpandedChange = { loadExpanded = it },
                    modifier = Modifier.width(160.dp),
                ) {
                    OutlinedTextField(
                        value = "Load…",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(loadExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    ExposedDropdownMenu(expanded = loadExpanded, onDismissRequest = { loadExpanded = false }) {
                        if (savedBatches.isEmpty()) {
                            DropdownMenuItem(text = { Text("No saved batches") }, onClick = {}, enabled = false)
                        } else {
                            savedBatches.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        batchName = name
                                        script = AppSettings.batchScript(name)
                                        loadExpanded = false
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            modifier = Modifier.size(20.dp),
                                            onClick = {
                                                AppSettings.deleteBatch(name)
                                                savedBatches = AppSettings.batchNames()
                                            }
                                        ) { Icon(Icons.Filled.Delete, "Delete", modifier = Modifier.size(14.dp)) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (results.isNotEmpty()) {
            SectionCard("Results", Icons.Filled.Checklist) {
                results.forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            if (r.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Column {
                            Text(r.line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily))
                            Text(
                                r.output,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
