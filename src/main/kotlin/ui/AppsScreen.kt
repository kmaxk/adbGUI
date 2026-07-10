package ui

import adb.AdbDevice
import adb.AdbService
import adb.AppInfo
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppsScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    var confirmClearData by remember { mutableStateOf<String?>(null) }
    var infoPackage by remember { mutableStateOf<String?>(null) }
    var appInfo by remember { mutableStateOf<AppInfo?>(null) }

    fun loadPackages() {
        scope.launch {
            isLoading = true
            packages = AdbService.packages(device.serial)
            isLoading = false
        }
    }

    LaunchedEffect(device.serial) { loadPackages() }

    fun installApks(paths: List<String>) {
        scope.launch {
            isBusy = true
            for (path in paths) {
                val result = AdbService.install(device.serial, path)
                feedback = result.fold(
                    onSuccess = { true to it },
                    onFailure = { false to "Install failed: ${it.message}" }
                )
            }
            isBusy = false
            loadPackages()
        }
    }

    fun pickAndInstallApk() {
        scope.launch {
            val paths = withContext(Dispatchers.Swing) {
                val dialog = FileDialog(null as Frame?, "Install APK…", FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.setFilenameFilter { _, name -> name.endsWith(".apk", ignoreCase = true) }
                dialog.isVisible = true
                dialog.files.map { it.absolutePath }
            }
            if (paths.isNotEmpty()) installApks(paths)
        }
    }

    fun runAction(block: suspend () -> Pair<Boolean, String>) {
        scope.launch {
            isBusy = true
            feedback = block()
            isBusy = false
        }
    }

    confirmUninstall?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Uninstall app?") },
            text = { Text("$pkg\n\nApp and its data will be removed.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmUninstall = null
                        runAction {
                            val result = AdbService.uninstall(device.serial, pkg)
                            if (result.isSuccess) {
                                packages = packages - pkg
                                selected = null
                                true to "Uninstalled: $pkg"
                            } else false to "Failed: ${result.exceptionOrNull()?.message}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } }
        )
    }

    confirmClearData?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmClearData = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear app data?") },
            text = { Text("$pkg\n\nAll data and settings of this app will be deleted. Cannot be undone.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearData = null
                        runAction {
                            val result = AdbService.clearData(device.serial, pkg)
                            if (result.isSuccess) true to "Data cleared: $pkg"
                            else false to "Failed: ${result.exceptionOrNull()?.message}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear data") }
            },
            dismissButton = { TextButton(onClick = { confirmClearData = null }) { Text("Cancel") } }
        )
    }

    infoPackage?.let { pkg ->
        LaunchedEffect(pkg) {
            appInfo = null
            appInfo = runCatching { AdbService.appInfo(device.serial, pkg) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { infoPackage = null },
            icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(pkg, style = MaterialTheme.typography.titleSmall) },
            text = {
                val i = appInfo
                if (i == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppInfoRow("Version", "${i.versionName} (${i.versionCode})")
                        AppInfoRow("SDK", "min ${i.minSdk} · target ${i.targetSdk}")
                        AppInfoRow("Installed", i.firstInstall)
                        AppInfoRow("Updated", i.lastUpdate)
                        AppInfoRow("Installer", i.installer)
                        AppInfoRow("APK path", i.apkPath)
                        AppInfoRow("Data dir", i.dataDir)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { infoPackage = null }) { Text("Close") } }
        )
    }

    val filtered = remember(packages, search) {
        if (search.isBlank()) packages else packages.filter { it.contains(search, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onExternalDrag(
                onDrop = { value ->
                    val data = value.dragData
                    if (data is DragData.FilesList) {
                        val apks = data.readFiles()
                            .mapNotNull { runCatching { File(URI(it)).absolutePath }.getOrNull() }
                            .filter { it.endsWith(".apk", ignoreCase = true) }
                        if (apks.isNotEmpty()) installApks(apks)
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
                onValueChange = { search = it; selected = null; feedback = null },
                placeholder = { Text("Search packages…") },
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
            Button(onClick = { pickAndInstallApk() }, enabled = !isBusy) {
                Icon(Icons.Filled.InstallMobile, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Install APK")
            }
            IconButton(onClick = { loadPackages() }) {
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
        AnimatedFade(selected) { pkg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = appCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(pkg, style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(enabled = !isBusy, onClick = {
                            runAction {
                                val result = AdbService.launchApp(device.serial, pkg)
                                if (result.isSuccess) true to "Launched: $pkg"
                                else false to "Failed: ${result.exceptionOrNull()?.message}"
                            }
                        }) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Launch")
                        }
                        OutlinedButton(enabled = !isBusy, onClick = {
                            runAction {
                                AdbService.forceStop(device.serial, pkg)
                                true to "Force-stopped: $pkg"
                            }
                        }) {
                            Icon(Icons.Filled.StopCircle, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Force-Stop")
                        }
                        OutlinedButton(enabled = !isBusy, onClick = { infoPackage = pkg }) {
                            Icon(Icons.Filled.Info, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Info")
                        }
                        OutlinedButton(enabled = !isBusy, onClick = { confirmClearData = pkg }) {
                            Icon(Icons.Filled.CleaningServices, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear Data")
                        }
                        Spacer(Modifier.weight(1f))
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Button(
                                onClick = { confirmUninstall = pkg },
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
            "${filtered.size} packages · drop APK files anywhere to install",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered) { pkg ->
                    val isSelected = pkg == selected
                    val (hoverSource, hovered) = rememberHover()
                    Card(
                        onClick = { selected = if (isSelected) null else pkg; feedback = null },
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
                                Icons.Filled.Android,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                pkg,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily),
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppInfoRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppMonoFamily))
    }
}
