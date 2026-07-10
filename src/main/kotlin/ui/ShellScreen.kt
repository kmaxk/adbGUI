package ui

import adb.AdbDevice
import adb.AdbService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class ShellEntry(val command: String, val output: String)

@Composable
fun ShellScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    val entries = remember(device.serial) { mutableStateListOf<ShellEntry>() }
    val history = remember(device.serial) { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    var command by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty() || isRunning) return
        command = ""
        history.add(cmd)
        historyIndex = -1
        scope.launch {
            isRunning = true
            val output = runCatching { AdbService.shell(device.serial, cmd) }
                .getOrElse { "Error: ${it.message}" }
            entries.add(ShellEntry(cmd, output.trimEnd()))
            isRunning = false
            listState.scrollToItem(entries.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Output
        Surface(modifier = Modifier.weight(1f).fillMaxWidth(), color = Color(0xFF0D1117)) {
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Filled.Terminal,
                        title = "adb shell",
                        subtitle = device.displayName,
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(entries) { entry ->
                            Column {
                                Text(
                                    "$ ${entry.command}",
                                    color = Color(0xFF56D364),
                                    fontSize = 12.sp,
                                    fontFamily = AppMonoFamily,
                                    lineHeight = 17.sp,
                                )
                                if (entry.output.isNotEmpty()) {
                                    Text(
                                        entry.output,
                                        color = Color(0xFFCDD9E5),
                                        fontSize = 12.sp,
                                        fontFamily = AppMonoFamily,
                                        lineHeight = 17.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "$",
                fontFamily = AppMonoFamily,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = { Text("shell command…", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> { run(); true }
                            Key.DirectionUp -> {
                                if (history.isNotEmpty()) {
                                    historyIndex = if (historyIndex == -1) history.lastIndex
                                    else (historyIndex - 1).coerceAtLeast(0)
                                    command = history[historyIndex]
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                if (historyIndex != -1) {
                                    historyIndex = if (historyIndex >= history.lastIndex) -1 else historyIndex + 1
                                    command = if (historyIndex == -1) "" else history[historyIndex]
                                }
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                )
            )
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { run() }, enabled = command.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Run", modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = { entries.clear() }, enabled = entries.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, "Clear", modifier = Modifier.size(18.dp))
            }
        }
    }
}
