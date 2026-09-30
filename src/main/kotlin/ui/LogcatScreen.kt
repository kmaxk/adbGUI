package ui

import adb.AdbDevice
import adb.AdbService
import adb.RunningApp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

private const val MAX_LINES = 3000
private const val TRIM_BATCH = 500
private const val PRIORITY_ORDER = "VDIWEF"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    var filter by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var highlightOnly by remember { mutableStateOf(false) }
    var minLevel by remember { mutableStateOf('V') }
    var levelDropdownExpanded by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }
    var isPaused by remember { mutableStateOf(false) }
    var runningApps by remember { mutableStateOf<List<RunningApp>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<RunningApp?>(null) }
    var appDropdownExpanded by remember { mutableStateOf(false) }
    var isLoadingApps by remember { mutableStateOf(false) }
    var showExceptions by remember { mutableStateOf(false) }

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

    val compiledFilter = remember(filter, useRegex) {
        if (useRegex && filter.isNotBlank()) runCatching { Regex(filter, RegexOption.IGNORE_CASE) }.getOrNull() else null
    }
    val filterIsInvalidRegex = useRegex && filter.isNotBlank() && compiledFilter == null

    fun lineMatchesFilter(line: String): Boolean = when {
        filter.isBlank() -> true
        useRegex -> compiledFilter?.containsMatchIn(line) ?: true
        else -> line.contains(filter, ignoreCase = true)
    }

    val filteredLines = remember(lines.toList(), filter, minLevel, useRegex, highlightOnly, compiledFilter) {
        val minIndex = PRIORITY_ORDER.indexOf(minLevel)
        lines.filter { line ->
            val levelOk = minIndex <= 0 || run {
                val priority = logLinePriority(line)
                priority == null || PRIORITY_ORDER.indexOf(priority) >= minIndex
            }
            val textOk = highlightOnly || lineMatchesFilter(line)
            levelOk && textOk
        }
    }

    val exceptions = remember(filteredLines) { findExceptions(filteredLines) }

    fun exportLogs() {
        scope.launch {
            val target = withContext(Dispatchers.Swing) {
                val dialog = FileDialog(null as Frame?, "Export logs…", FileDialog.SAVE)
                dialog.file = "logcat-${device.serial}.txt"
                dialog.isVisible = true
                val dir = dialog.directory
                val name = dialog.file
                if (dir != null && name != null) File(dir, name) else null
            } ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) { target.writeText(filteredLines.joinToString("\n")) }
            }
        }
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

                // Filter/search text
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(if (highlightOnly) "Search logs…" else "Filter logs…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = filterIsInvalidRegex,
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

                ToolbarIconButton(
                    icon = Icons.Filled.Code,
                    tooltip = if (filterIsInvalidRegex) "Invalid regex" else "Toggle regex filter",
                    tint = when {
                        filterIsInvalidRegex -> MaterialTheme.colorScheme.error
                        useRegex -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = { useRegex = !useRegex }
                )

                ToolbarIconButton(
                    icon = Icons.Filled.Search,
                    tooltip = if (highlightOnly) "Highlighting matches (not filtering)" else "Highlight matches instead of filtering",
                    tint = if (highlightOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { highlightOnly = !highlightOnly }
                )

                // Min log level
                Box {
                    OutlinedButton(
                        onClick = { levelDropdownExpanded = true },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text(
                            minLevel.toString(),
                            fontFamily = AppMonoFamily,
                            color = levelColor(minLevel),
                        )
                        Icon(Icons.Filled.ArrowDropDown, "Min level", modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = levelDropdownExpanded,
                        onDismissRequest = { levelDropdownExpanded = false }
                    ) {
                        val labels = mapOf(
                            'V' to "Verbose", 'D' to "Debug", 'I' to "Info",
                            'W' to "Warning", 'E' to "Error", 'F' to "Fatal",
                        )
                        PRIORITY_ORDER.forEach { level ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "$level  ${labels[level]}",
                                        fontFamily = AppMonoFamily,
                                        color = levelColor(level),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                onClick = { minLevel = level; levelDropdownExpanded = false }
                            )
                        }
                    }
                }

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

                ToolbarIconButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    tooltip = "Jump to bottom",
                    onClick = {
                        scope.launch {
                            if (filteredLines.isNotEmpty()) listState.scrollToItem(filteredLines.size - 1)
                        }
                    }
                )

                ToolbarIconButton(
                    icon = Icons.Filled.BugReport,
                    tooltip = if (exceptions.isEmpty()) "No exceptions" else "Browse exceptions (${exceptions.size})",
                    tint = when {
                        showExceptions -> MaterialTheme.colorScheme.primary
                        exceptions.isNotEmpty() -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = { showExceptions = !showExceptions }
                )

                ToolbarIconButton(Icons.Filled.Download, "Export logs") { exportLogs() }

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
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppMonoFamily),
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

        Row(modifier = Modifier.fillMaxSize()) {
            // Log output
            Surface(modifier = Modifier.weight(1f).fillMaxHeight(), color = Bench.Well) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        itemsIndexed(filteredLines) { _, line ->
                            Text(
                                text = highlightedLine(line, filter, useRegex, compiledFilter),
                                color = logLineColor(line),
                                fontSize = 12.sp,
                                fontFamily = AppMonoFamily,
                                lineHeight = 17.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (showExceptions) {
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
                ExceptionsPanel(
                    exceptions = exceptions,
                    onJumpTo = { index ->
                        autoScroll = false
                        scope.launch { listState.scrollToItem(index) }
                    },
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                )
            }
        }
    }
}

private data class LogException(val startIndex: Int, val text: String)

private val EXCEPTION_HEADER_PATTERN = Regex("""(FATAL EXCEPTION|[\w.$]+(Exception|Error)\b)""")
private val BRIEF_TAG_PATTERN = Regex("""\s([EWIDVF])/([^(]+)\(""")
private val THREADTIME_TAG_PATTERN = Regex("""\s([EWIDVF])\s+([^:]+):""")

private fun logLineTag(line: String): String? {
    val head = line.take(50)
    BRIEF_TAG_PATTERN.find(head)?.let { return it.groupValues[2].trim() }
    THREADTIME_TAG_PATTERN.find(head)?.let { return it.groupValues[2].trim() }
    return null
}

private fun findExceptions(lines: List<String>): List<LogException> {
    val results = mutableListOf<LogException>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val priority = logLinePriority(line)
        val isHeader = (priority == 'E' || priority == 'F' || priority == 'W') &&
            EXCEPTION_HEADER_PATTERN.containsMatchIn(line)
        if (isHeader) {
            val tag = logLineTag(line)
            val start = i
            val block = StringBuilder(line)
            var j = i + 1
            while (j < lines.size) {
                val next = lines[j]
                if (logLinePriority(next) != priority || logLineTag(next) != tag) break
                block.append("\n").append(next)
                j++
            }
            results.add(LogException(start, block.toString()))
            i = j
        } else {
            i++
        }
    }
    return results
}

private fun exceptionTitle(text: String): String {
    val firstLine = text.lineSequence().first()
    val match = Regex("""([\w.$]+(?:Exception|Error))(:.*)?$""").find(firstLine)
    return match?.value?.take(120) ?: firstLine.take(120)
}

@Composable
private fun ExceptionsPanel(
    exceptions: List<LogException>,
    onJumpTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Text(
                    "Exceptions (${exceptions.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (exceptions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No exceptions in current view",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(exceptions) { idx, entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpTo(entry.startIndex) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "#${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(
                                    modifier = Modifier.size(24.dp),
                                    onClick = {
                                        Toolkit.getDefaultToolkit().systemClipboard
                                            .setContents(StringSelection(entry.text), null)
                                    }
                                ) { Icon(Icons.Filled.ContentCopy, "Copy", modifier = Modifier.size(14.dp)) }
                            }
                            Text(
                                exceptionTitle(entry.text),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                                color = LogLevelColors.Error,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

private val HIGHLIGHT_STYLE = SpanStyle(background = Color(0xFF6B4A0E), color = Bench.Chalk)

private fun highlightedLine(line: String, filter: String, useRegex: Boolean, compiledFilter: Regex?) =
    buildAnnotatedString {
        append(line)
        if (filter.isBlank()) return@buildAnnotatedString
        val ranges = if (useRegex) {
            compiledFilter?.findAll(line)?.map { it.range }?.toList() ?: emptyList()
        } else {
            buildList {
                var idx = line.indexOf(filter, ignoreCase = true)
                while (idx >= 0) {
                    add(idx until idx + filter.length)
                    idx = line.indexOf(filter, idx + filter.length, ignoreCase = true)
                }
            }
        }
        ranges.forEach { range ->
            if (!range.isEmpty()) addStyle(HIGHLIGHT_STYLE, range.first, range.last + 1)
        }
    }

private fun logLinePriority(line: String): Char? {
    val match = Regex("""\s([EWIDVF])/""").find(line.take(50))
        ?: Regex("""\s([EWIDVF])\s""").find(line.take(50))
    return match?.groupValues?.getOrNull(1)?.firstOrNull()
}

private fun levelColor(level: Char): Color = when (level) {
    'F' -> LogLevelColors.Fatal
    'E' -> LogLevelColors.Error
    'W' -> LogLevelColors.Warn
    'I' -> LogLevelColors.Info
    'D' -> LogLevelColors.Debug
    'V' -> LogLevelColors.Verbose
    else -> LogLevelColors.Default
}

private fun logLineColor(line: String): Color =
    logLinePriority(line)?.let { levelColor(it) } ?: LogLevelColors.Default
