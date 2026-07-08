package ui

import adb.AdbDevice
import adb.AdbService
import adb.RunningApp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val MAX_LINES = 3000
private const val TRIM_BATCH = 500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    var filter by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var isPaused by remember { mutableStateOf(false) }
    var runningApps by remember { mutableStateOf<List<RunningApp>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<RunningApp?>(null) }
    var appDropdownExpanded by remember { mutableStateOf(false) }
    var isLoadingApps by remember { mutableStateOf(false) }

    fun refreshApps() {
        scope.launch {
            isLoadingApps = true
            runningApps = AdbService.runningApps(device.serial)
            selectedApp = selectedApp?.let { sel -> runningApps.firstOrNull { it.packageName == sel.packageName } }
            isLoadingApps = false
        }
    }

    LaunchedEffect(device.serial) { refreshApps() }

    LaunchedEffect(device.serial, selectedApp?.pid) {
        lines.clear()
        AdbService.logcatFlow(device.serial, selectedApp?.pid).collect { line ->
            if (!isPaused) {
                lines.add(line)
                if (lines.size > MAX_LINES + TRIM_BATCH) {
                    lines.removeRange(0, TRIM_BATCH)
                }
            }
        }
    }

    val filteredLines = remember(lines.toList(), filter) {
        if (filter.isBlank()) lines.toList()
        else lines.filter { it.contains(filter, ignoreCase = true) }
    }

    LaunchedEffect(filteredLines.size, autoScroll) {
        if (autoScroll && filteredLines.isNotEmpty()) {
            listState.scrollToItem(filteredLines.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Single compact toolbar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // App picker
                ExposedDropdownMenuBox(
                    expanded = appDropdownExpanded,
                    onExpandedChange = { appDropdownExpanded = it },
                    modifier = Modifier.width(260.dp)
                ) {
                    OutlinedTextField(
                        value = selectedApp?.packageName ?: "All apps",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("App", style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(appDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = appDropdownExpanded,
                        onDismissRequest = { appDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All apps") },
                            onClick = { selectedApp = null; appDropdownExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Apps, null, modifier = Modifier.size(16.dp)) }
                        )
                        HorizontalDivider()
                        if (runningApps.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No user apps running", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {},
                                enabled = false
                            )
                        } else {
                            runningApps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
                                    onClick = { selectedApp = app; appDropdownExpanded = false }
                                )
                            }
                        }
                    }
                }

                // Filter text
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter logs…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    trailingIcon = if (filter.isNotEmpty()) {
                        {
                            IconButton(onClick = { filter = "" }, modifier = Modifier.size(18.dp)) {
                                Icon(Icons.Filled.Clear, "Clear", modifier = Modifier.size(14.dp))
                            }
                        }
                    } else null
                )

                // Icon controls
                if (isLoadingApps) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    ToolbarIconButton(Icons.Filled.Refresh, "Refresh app list") { refreshApps() }
                }

                ToolbarIconButton(
                    icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    tooltip = if (isPaused) "Resume" else "Pause",
                    tint = if (isPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                    onClick = { isPaused = !isPaused }
                )

                ToolbarIconButton(
                    icon = Icons.Filled.VerticalAlignBottom,
                    tooltip = "Auto-scroll",
                    tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { autoScroll = !autoScroll }
                )

                ToolbarIconButton(Icons.Filled.DeleteSweep, "Clear logs") {
                    lines.clear()
                    scope.launch { AdbService.clearLogcat(device.serial) }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Status row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "${filteredLines.size} lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isPaused) {
                Text(
                    "PAUSED",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (selectedApp != null) {
                Text(
                    "● ${selectedApp!!.packageName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Log output
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D1117)) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    itemsIndexed(filteredLines) { _, line ->
                        Text(
                            text = line,
                            color = logLineColor(line),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 17.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    tooltip: String,
    tint: Color = LocalContentColor.current,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, tooltip, modifier = Modifier.size(18.dp), tint = tint)
    }
}

private fun logLineColor(line: String): Color {
    val match = Regex("""\s([EWIDVF])/""").find(line.take(50))
        ?: Regex("""\s([EWIDVF])\s""").find(line.take(50))
    return when (match?.groupValues?.getOrNull(1)) {
        "E" -> Color(0xFFFF7B72)
        "W" -> Color(0xFFFFD93D)
        "I" -> Color(0xFF56D364)
        "D" -> Color(0xFF79B8FF)
        "V" -> Color(0xFF6E7681)
        else -> Color(0xFFCDD9E5)
    }
}
