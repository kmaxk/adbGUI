package ui

import adb.AdbDevice
import adb.AdbService
import adb.RemoteFile
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private const val HOME_PATH = "/sdcard"

@Composable
fun FilesScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf(HOME_PATH) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedFile by remember { mutableStateOf<RemoteFile?>(null) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<RemoteFile?>(null) }

    fun load() {
        scope.launch {
            isLoading = true
            selectedFile = null
            val result = AdbService.listFiles(device.serial, currentPath)
            result.fold(
                onSuccess = { files = it; error = null },
                onFailure = { files = emptyList(); error = it.message }
            )
            isLoading = false
        }
    }

    LaunchedEffect(device.serial, currentPath) { load() }

    fun navigateUp() {
        if (currentPath == "/") return
        currentPath = currentPath.substringBeforeLast('/').ifEmpty { "/" }
        feedback = null
    }

    fun download(file: RemoteFile) {
        scope.launch {
            val target = withContext(Dispatchers.Swing) {
                val dialog = FileDialog(null as Frame?, "Save to…", FileDialog.SAVE)
                dialog.file = file.name
                dialog.isVisible = true
                val dir = dialog.directory
                val name = dialog.file
                if (dir != null && name != null) File(dir, name).absolutePath else null
            } ?: return@launch

            isBusy = true
            val result = AdbService.pull(device.serial, file.path, target)
            isBusy = false
            feedback = result.fold(
                onSuccess = { true to "Saved: $it" },
                onFailure = { false to "Pull failed: ${it.message}" }
            )
        }
    }

    confirmDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete ${if (file.isDirectory) "folder" else "file"}?") },
            text = {
                Text(
                    file.path + if (file.isDirectory) "\n\nDeletes recursively. Cannot be undone." else "\n\nCannot be undone.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = null
                        scope.launch {
                            isBusy = true
                            val result = AdbService.deleteFile(device.serial, file.path, file.isDirectory)
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { navigateUp() },
                    enabled = currentPath != "/",
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.ArrowUpward, "Up", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { currentPath = HOME_PATH; feedback = null },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Home, "Home", modifier = Modifier.size(18.dp))
                }

                // Breadcrumb
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { currentPath = "/"; feedback = null },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("/", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val segments = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                    segments.forEachIndexed { index, segment ->
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        val target = "/" + segments.take(index + 1).joinToString("/")
                        TextButton(
                            onClick = { currentPath = target; feedback = null },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text(
                                segment,
                                fontFamily = FontFamily.Monospace,
                                color = if (index == segments.lastIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isLoading || isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { load() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, "Refresh", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Feedback banner
        feedback?.let { (success, msg) ->
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
                    modifier = Modifier.size(14.dp),
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
                    Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(12.dp))
                }
            }
        }

        // Content
        when {
            error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.FolderOff, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { navigateUp() }) { Text("Go up") }
                }
            }
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            files.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Empty folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                items(files, key = { it.path }) { file ->
                    val isSelected = file == selectedFile
                    Surface(
                        onClick = {
                            if (file.isDirectory) {
                                currentPath = file.path
                                feedback = null
                            } else {
                                selectedFile = if (isSelected) null else file
                            }
                        },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.background,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
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
                                tint = when {
                                    file.isDirectory -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            if (isSelected) {
                                IconButton(onClick = { download(file) }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Filled.Download, "Download", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { confirmDelete = file }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Filled.Delete, "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                if (!file.isDirectory) {
                                    Text(
                                        formatSize(file.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    file.modified,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                if (file.isDirectory) {
                                    IconButton(onClick = { confirmDelete = file }, modifier = Modifier.size(26.dp)) {
                                        Icon(
                                            Icons.Filled.Delete, "Delete",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
