package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ios.IosContainer
import ios.IosContainerApp
import ios.IosDevice
import ios.IosFileEntry
import ios.IosService
import ios.canDeleteFiles
import ios.containerApps
import ios.deleteContainerFile
import ios.exportContainerFile
import ios.importContainerFiles
import ios.listContainerFiles
import ios.openContainer
import ios.revealInFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun IosFilesScreen(device: IosDevice) {
    val scope = rememberCoroutineScope()

    var apps by remember(device.udid) { mutableStateOf<List<IosContainerApp>>(emptyList()) }
    var appsLoading by remember(device.udid) { mutableStateOf(false) }
    var appsError by remember(device.udid) { mutableStateOf<String?>(null) }
    var showSystem by remember { mutableStateOf(false) }

    var selectedApp by remember(device.udid) { mutableStateOf<IosContainerApp?>(null) }
    var selectedGroup by remember(device.udid) { mutableStateOf<String?>(null) }
    var container by remember(device.udid) { mutableStateOf<IosContainer?>(null) }
    // Set when a container was just opened: its first listing bypasses the device cache
    var freshContainer by remember(device.udid) { mutableStateOf(false) }

    var currentDir by remember(device.udid) { mutableStateOf("") }
    var files by remember(device.udid) { mutableStateOf<List<IosFileEntry>>(emptyList()) }
    var isLoading by remember(device.udid) { mutableStateOf(false) }
    var error by remember(device.udid) { mutableStateOf<String?>(null) }
    var selectedFile by remember(device.udid) { mutableStateOf<IosFileEntry?>(null) }
    var feedback by remember(device.udid) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isBusy by remember(device.udid) { mutableStateOf(false) }
    var confirmDelete by remember(device.udid) { mutableStateOf<IosFileEntry?>(null) }

    fun loadApps() {
        scope.launch {
            appsLoading = true
            IosService.containerApps(device).fold(
                onSuccess = { list ->
                    apps = list
                    appsError = null
                    // Keep the selection if the app is still installed, otherwise pick the first user app
                    val keep = selectedApp?.let { s -> list.firstOrNull { it.bundleId == s.bundleId } }
                    if (keep == null) {
                        selectedApp = list.firstOrNull { it.isUser } ?: list.firstOrNull()
                        selectedGroup = null
                    }
                },
                onFailure = { apps = emptyList(); appsError = it.message }
            )
            appsLoading = false
        }
    }

    fun load(refresh: Boolean = false) {
        val c = container ?: return
        val dir = currentDir
        scope.launch {
            isLoading = true
            selectedFile = null
            val result = IosService.listContainerFiles(device, c, dir, refresh)
            // A slow listing that finishes after switching app or folder must not show up (and be deleted from) there
            if (container != c || currentDir != dir) return@launch
            result.fold(
                onSuccess = { files = it; error = null },
                onFailure = { files = emptyList(); error = it.message }
            )
            isLoading = false
        }
    }

    LaunchedEffect(device.udid) { loadApps() }

    // Resolve the container whenever the app or group changes
    LaunchedEffect(device.udid, selectedApp?.bundleId, selectedGroup) {
        val app = selectedApp
        container = null
        files = emptyList()
        currentDir = ""
        feedback = null
        if (app == null) return@LaunchedEffect
        isLoading = true
        IosService.openContainer(device, app, selectedGroup).fold(
            onSuccess = { freshContainer = true; container = it; error = null },
            onFailure = { error = it.message; isLoading = false }
        )
    }

    LaunchedEffect(container, currentDir) {
        // A freshly opened device container is fetched anew, sub-folders come from the cached listing
        if (container != null) {
            load(refresh = freshContainer)
            freshContainer = false
        }
    }

    fun navigateTo(dir: String) {
        currentDir = dir
        feedback = null
    }

    fun navigateUp() {
        if (currentDir.isEmpty()) return
        navigateTo(if ('/' in currentDir) currentDir.substringBeforeLast('/') else "")
    }

    fun export(entry: IosFileEntry) {
        val c = container ?: return
        scope.launch {
            val target = withContext(Dispatchers.Swing) {
                val dialog = FileDialog(null as Frame?, "Export to…", FileDialog.SAVE)
                dialog.file = entry.name
                dialog.isVisible = true
                val dir = dialog.directory
                val name = dialog.file
                if (dir != null && name != null) File(dir, name) else null
            } ?: return@launch
            isBusy = true
            val result = IosService.exportContainerFile(device, c, entry, target)
            isBusy = false
            feedback = result.fold(
                onSuccess = { true to "Saved: $it" },
                onFailure = { false to "Export failed: ${it.message}" }
            )
        }
    }

    fun import() {
        val c = container ?: return
        scope.launch {
            val sources = withContext(Dispatchers.Swing) {
                val dialog = FileDialog(null as Frame?, "Import to /$currentDir…", FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.isVisible = true
                dialog.files.toList()
            }
            if (sources.isEmpty()) return@launch
            isBusy = true
            val result = IosService.importContainerFiles(device, c, currentDir, sources)
            isBusy = false
            feedback = result.fold(
                onSuccess = { n -> true to if (n == 1) "Imported: ${sources.first().name}" else "Imported $n files" },
                onFailure = { false to "Import failed: ${it.message}" }
            )
            load(refresh = true)
        }
    }

    fun reveal(entry: IosFileEntry?) {
        val c = container ?: return
        scope.launch {
            IosService.revealInFinder(c, currentDir, entry).onFailure { feedback = false to "Finder: ${it.message}" }
        }
    }

    confirmDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete ${if (file.isDirectory) "folder" else "file"}?") },
            text = {
                Text(
                    "/" + file.path + if (file.isDirectory) "\n\nDeletes recursively. Cannot be undone." else "\n\nCannot be undone.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = null
                        val c = container ?: return@Button
                        scope.launch {
                            isBusy = true
                            val result = IosService.deleteContainerFile(c, file)
                            isBusy = false
                            feedback = result.fold(
                                onSuccess = { true to "Deleted: ${file.name}" },
                                onFailure = { false to "Delete failed: ${it.message}" }
                            )
                            if (result.isSuccess) load()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }

    val c = container
    val canDelete = c != null && IosService.canDeleteFiles(c)
    val isLocal = c?.localRoot != null

    Column(modifier = Modifier.fillMaxSize()) {
        // Container picker
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val visibleApps = apps.filter { showSystem || it.isUser || it.bundleId == selectedApp?.bundleId }
                PickerDropdown(
                    label = "App",
                    value = selectedApp?.let { "${it.name}  ·  ${it.bundleId}" } ?: if (appsLoading) "Loading…" else "No apps",
                    modifier = Modifier.weight(1f),
                ) { close ->
                    if (visibleApps.isEmpty()) {
                        DropdownMenuItem(text = { Text("No apps") }, onClick = {}, enabled = false)
                    }
                    visibleApps.forEach { app ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(app.name, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        app.bundleId,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = AppMonoFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                if (app.bundleId != selectedApp?.bundleId) {
                                    selectedApp = app
                                    selectedGroup = null
                                }
                                close()
                            }
                        )
                    }
                }
                val groups = selectedApp?.groups.orEmpty()
                if (groups.isNotEmpty()) {
                    PickerDropdown(
                        label = "Container",
                        value = selectedGroup ?: "App data",
                        modifier = Modifier.width(260.dp),
                    ) { close ->
                        DropdownMenuItem(
                            text = { Text("App data", style = MaterialTheme.typography.bodySmall) },
                            onClick = { selectedGroup = null; close() }
                        )
                        groups.forEach { g ->
                            DropdownMenuItem(
                                text = { Text(g, style = MaterialTheme.typography.bodySmall, fontFamily = AppMonoFamily) },
                                onClick = { selectedGroup = g; close() }
                            )
                        }
                    }
                }
                if (device.isSimulator) {
                    FilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text("System apps") },
                        leadingIcon = if (showSystem) {
                            { Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                }
                if (appsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { loadApps() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, "Reload apps", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Path toolbar
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { navigateUp() }, enabled = currentDir.isNotEmpty(), modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ArrowUpward, "Up", modifier = Modifier.size(18.dp))
                }

                // Breadcrumb
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { navigateTo("") }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(
                            c?.label ?: "/",
                            fontFamily = AppMonoFamily,
                            color = if (currentDir.isEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val segments = currentDir.split('/').filter { it.isNotEmpty() }
                    segments.forEachIndexed { index, segment ->
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        val target = segments.take(index + 1).joinToString("/")
                        TextButton(onClick = { navigateTo(target) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(
                                segment,
                                fontFamily = AppMonoFamily,
                                color = if (index == segments.lastIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isLoading || isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (c != null) {
                    if (isLocal) {
                        IconButton(onClick = { reveal(null) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.FolderOpen, "Show in Finder", modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = { import() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Upload, "Import into this folder", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { load(refresh = true) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, "Refresh", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Feedback banner
        AnimatedFade(feedback) { (success, msg) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (success) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    style = MaterialTheme.typography.bodySmall,
                    color = if (success) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { feedback = null }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(14.dp))
                }
            }
        }

        // Content
        when {
            appsError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EmptyState(Icons.Filled.Apps, "Couldn't list apps", appsError)
                    OutlinedButton(onClick = { loadApps() }) { Text("Retry") }
                }
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.FolderOff, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (currentDir.isNotEmpty()) {
                        OutlinedButton(onClick = { navigateUp() }) { Text("Go up") }
                    } else {
                        OutlinedButton(onClick = { if (container != null) load(refresh = true) else loadApps() }) { Text("Retry") }
                    }
                }
            }
            isLoading || (appsLoading && apps.isEmpty()) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    if (!device.isSimulator && c != null) {
                        Text(
                            "Reading the container from ${device.name}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            selectedApp == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Filled.Apps, "No app selected",
                    if (device.isSimulator) "Pick an app to browse its data container"
                    else "Only apps built by you (development-signed) expose their container"
                )
            }
            files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Filled.FolderOpen, title = "Empty folder")
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                items(files, key = { it.path }) { file ->
                    FileRow(
                        file = file,
                        isSelected = file == selectedFile,
                        canDelete = canDelete,
                        canReveal = isLocal,
                        onClick = {
                            if (file.isDirectory) navigateTo(file.path)
                            else selectedFile = if (file == selectedFile) null else file
                        },
                        onExport = { export(file) },
                        onDelete = { confirmDelete = file },
                        onReveal = { reveal(file) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerDropdown(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    items: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            )
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { expanded = false }
        }
    }
}

@Composable
private fun FileRow(
    file: IosFileEntry,
    isSelected: Boolean,
    canDelete: Boolean,
    canReveal: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onReveal: () -> Unit,
) {
    val (hoverSource, hovered) = rememberHover()
    Surface(
        onClick = onClick,
        interactionSource = hoverSource,
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            hovered.value -> HoverColor
            else -> MaterialTheme.colorScheme.background
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                when {
                    file.isSymlink -> Icons.Filled.Link
                    file.isDirectory -> Icons.Filled.Folder
                    else -> Icons.Filled.InsertDriveFile
                },
                null,
                modifier = Modifier.size(16.dp),
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                file.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (file.isDirectory) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            // Files show their actions when selected; folders (click = open) when hovered
            val showActions = isSelected || (file.isDirectory && hovered.value)
            if (showActions) {
                if (canReveal) {
                    IconButton(onClick = onReveal, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Filled.FolderOpen, "Show in Finder", modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = onExport, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Download, "Export", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (canDelete) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Filled.Delete, "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                if (!file.isDirectory) {
                    Text(
                        formatFileSize(file.size),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = AppMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                file.modified?.let {
                    Text(
                        formatDate(it),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = AppMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(millis))
