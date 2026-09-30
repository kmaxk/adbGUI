package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.DragData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.onExternalDrag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ios.IosApp
import ios.IosDevice
import ios.IosService
import ios.appDataContainer
import ios.apps
import ios.installApp
import ios.launchApp
import ios.revealInFinder
import ios.terminateApp
import ios.uninstallApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

private fun isInstallable(file: File) =
    file.extension.equals("ipa", ignoreCase = true) || (file.extension.equals("app", ignoreCase = true) && file.isDirectory)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IosAppsScreen(device: IosDevice) {
    val scope = rememberCoroutineScope()
    var apps by remember(device.udid) { mutableStateOf<List<IosApp>>(emptyList()) }
    var loadError by remember(device.udid) { mutableStateOf<String?>(null) }
    var isLoading by remember(device.udid) { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var selectedId by remember(device.udid) { mutableStateOf<String?>(null) }
    var feedback by remember(device.udid) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isBusy by remember(device.udid) { mutableStateOf(false) }
    var confirmUninstall by remember(device.udid) { mutableStateOf<IosApp?>(null) }
    var infoApp by remember(device.udid) { mutableStateOf<IosApp?>(null) }

    fun loadApps() {
        scope.launch {
            isLoading = true
            IosService.apps(device).fold(
                onSuccess = { apps = it; loadError = null },
                onFailure = { apps = emptyList(); loadError = it.message ?: "Could not list apps" },
            )
            if (apps.none { it.bundleId == selectedId }) selectedId = null
            isLoading = false
        }
    }

    LaunchedEffect(device.udid) {
        selectedId = null
        feedback = null
        loadApps()
    }

    fun installFiles(files: List<File>) {
        scope.launch {
            isBusy = true
            for (file in files) {
                feedback = true to "Installing ${file.name}…"
                feedback = IosService.installApp(device, file).fold(
                    onSuccess = { true to "Installed: $it" },
                    onFailure = { false to "Install failed: ${it.message}" }
                )
            }
            isBusy = false
            loadApps()
        }
    }

    fun pickAndInstall() {
        scope.launch {
            val files = withContext(Dispatchers.Swing) {
                // Let the macOS dialog pick .app bundles as files instead of browsing into them
                val key = "apple.awt.use-file-dialog-packages"
                val previous = System.getProperty(key)
                System.setProperty(key, "true")
                try {
                    val dialog = FileDialog(null as Frame?, "Install .app / .ipa…", FileDialog.LOAD)
                    dialog.isMultipleMode = true
                    dialog.setFilenameFilter { dir, name -> isInstallable(File(dir, name)) }
                    dialog.isVisible = true
                    dialog.files.toList()
                } finally {
                    if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
                }
            }
            val valid = files.filter(::isInstallable)
            if (valid.isNotEmpty()) installFiles(valid)
            else if (files.isNotEmpty()) feedback = false to "Choose an .app bundle or an .ipa file"
        }
    }

    fun runAction(block: suspend () -> Pair<Boolean, String>) {
        scope.launch {
            isBusy = true
            feedback = block()
            isBusy = false
        }
    }

    fun Result<*>.message(success: String) =
        fold(onSuccess = { true to success }, onFailure = { false to "Failed: ${it.message}" })

    confirmUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Uninstall app?") },
            text = {
                Text(
                    "${app.name}\n${app.bundleId}\n\nApp and its data will be removed from ${device.name}.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmUninstall = null
                        runAction {
                            val result = IosService.uninstallApp(device, app.bundleId)
                            if (result.isSuccess) {
                                apps = apps.filter { it.bundleId != app.bundleId }
                                selectedId = null
                            }
                            result.message("Uninstalled: ${app.bundleId}")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } }
        )
    }

    infoApp?.let { app ->
        AlertDialog(
            onDismissRequest = { infoApp = null },
            icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(app.name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IosAppInfoRow("Bundle ID", app.bundleId)
                    IosAppInfoRow("Version", app.displayVersion.ifEmpty { "–" })
                    IosAppInfoRow("Type", if (app.isSystem) "System" else "User")
                    IosAppInfoRow("Removable", if (app.isRemovable) "yes" else "no")
                    IosAppInfoRow("Bundle", app.path.ifEmpty { "–" })
                    app.dataContainer?.let { IosAppInfoRow("Data", it) }
                }
            },
            confirmButton = { TextButton(onClick = { infoApp = null }) { Text("Close") } }
        )
    }

    val filtered = remember(apps, search, showSystem) {
        apps.filter { app ->
            (showSystem || !app.isSystem) &&
                (search.isBlank() || app.name.contains(search, ignoreCase = true) ||
                    app.bundleId.contains(search, ignoreCase = true))
        }
    }
    val selected = apps.firstOrNull { it.bundleId == selectedId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onExternalDrag(
                onDrop = { value ->
                    val data = value.dragData
                    if (data is DragData.FilesList && !isBusy) {
                        val files = data.readFiles()
                            .mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                            .filter(::isInstallable)
                        if (files.isNotEmpty()) installFiles(files)
                    }
                }
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Search + install row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it; feedback = null },
                placeholder = { Text("Search apps…") },
                leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (search.isNotEmpty()) {
                    { IconButton(onClick = { search = "" }, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Filled.Clear, "Clear", modifier = Modifier.size(14.dp))
                    }}
                } else null,
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                )
            )
            FilterChip(
                selected = showSystem,
                onClick = { showSystem = !showSystem },
                label = { Text("System apps") },
                leadingIcon = if (showSystem) {
                    { Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
            Button(onClick = { pickAndInstall() }, enabled = !isBusy) {
                Icon(Icons.Filled.InstallMobile, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Install App")
            }
            IconButton(onClick = { loadApps() }, enabled = !isLoading) {
                Icon(Icons.Filled.Refresh, "Refresh")
            }
        }

        // Feedback banner
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

        // Selection action bar
        AnimatedFade(selected) { app ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = appCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(
                        "${app.name} · ${app.bundleId}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(enabled = !isBusy, onClick = {
                            runAction { IosService.launchApp(device, app.bundleId).message("Launched: ${app.bundleId}") }
                        }) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Launch")
                        }
                        OutlinedButton(enabled = !isBusy, onClick = {
                            runAction { IosService.terminateApp(device, app).message("Terminated: ${app.bundleId}") }
                        }) {
                            Icon(Icons.Filled.StopCircle, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Terminate")
                        }
                        OutlinedButton(enabled = !isBusy, onClick = { infoApp = app }) {
                            Icon(Icons.Filled.Info, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Info")
                        }
                        if (device.isSimulator) {
                            OutlinedButton(enabled = !isBusy, onClick = {
                                runAction {
                                    IosService.appDataContainer(device, app.bundleId)
                                        .mapCatching { IosService.revealInFinder(it).getOrThrow() }
                                        .message("Opened data container of ${app.bundleId}")
                                }
                            }) {
                                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Show Data in Finder")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else if (app.isRemovable) {
                            Button(
                                onClick = { confirmUninstall = app },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Uninstall")
                            }
                        }
                    }
                }
            }
        }

        Text(
            "${filtered.size} ${if (showSystem) "apps" else "user apps"} · drop .app or .ipa files anywhere to install",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val error = loadError
        when {
            isLoading && apps.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Filled.ErrorOutline, "Could not list apps", error)
            }
            filtered.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Filled.Apps,
                    if (search.isNotBlank()) "No apps match \"$search\"" else "No user apps installed",
                    if (!showSystem) "Enable \"System apps\" to see built-in apps" else null,
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered, key = { it.bundleId }) { app ->
                    val isSelected = app.bundleId == selectedId
                    val (hoverSource, hovered) = rememberHover()
                    Card(
                        onClick = { selectedId = if (isSelected) null else app.bundleId; feedback = null },
                        interactionSource = hoverSource,
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                hovered.value -> HoverColor
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (app.isSystem) Icons.Filled.Settings else Icons.Filled.PhoneIphone,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                            Text(
                                app.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 220.dp)
                            )
                            Text(
                                app.bundleId,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                                color = textColor.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (app.displayVersion.isNotEmpty()) {
                                Text(
                                    app.displayVersion,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IosAppInfoRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily))
    }
}
