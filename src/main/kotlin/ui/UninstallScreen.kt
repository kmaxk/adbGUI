package ui

import adb.AdbDevice
import adb.AdbService
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun UninstallScreen(device: AdbDevice) {
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isUninstalling by remember { mutableStateOf(false) }

    fun loadPackages() {
        scope.launch {
            isLoading = true
            packages = AdbService.packages(device.serial)
            isLoading = false
        }
    }

    LaunchedEffect(device.serial) { loadPackages() }

    val filtered = remember(packages, search) {
        if (search.isBlank()) packages else packages.filter { it.contains(search, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Search row
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
            IconButton(onClick = { loadPackages() }) {
                Icon(Icons.Filled.Refresh, "Refresh")
            }
        }

        // Feedback banner
        feedback?.let { (success, msg) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (success) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
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
        selected?.let { pkg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    if (isUninstalling) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    isUninstalling = true
                                    val result = AdbService.uninstall(device.serial, pkg)
                                    isUninstalling = false
                                    if (result.isSuccess) {
                                        feedback = true to "Uninstalled: $pkg"
                                        packages = packages - pkg
                                        selected = null
                                    } else {
                                        feedback = false to "Failed: ${result.exceptionOrNull()?.message}"
                                    }
                                }
                            },
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

        Text(
            "${filtered.size} packages",
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
                    Card(
                        onClick = { selected = if (isSelected) null else pkg; feedback = null },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Android,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                pkg,
                                style = MaterialTheme.typography.bodySmall,
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
