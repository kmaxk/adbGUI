package ui

import adb.AdbDevice
import adb.AdbService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<AdbDevice?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var isLoadingDevices by remember { mutableStateOf(false) }

    fun refreshDevices() {
        scope.launch {
            isLoadingDevices = true
            devices = AdbService.devices()
            if (selectedDevice == null || selectedDevice !in devices) {
                selectedDevice = devices.firstOrNull()
            }
            isLoadingDevices = false
        }
    }

    LaunchedEffect(Unit) { refreshDevices() }

    MaterialTheme(colorScheme = AppDarkColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(modifier = Modifier.fillMaxSize()) {
                AppNavigationRail(selectedTab = selectedTab, onTabSelect = { selectedTab = it })

                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline)
                )

                Column(modifier = Modifier.weight(1f)) {
                    if (selectedTab != 4) {
                        DeviceBar(
                            devices = devices,
                            selected = selectedDevice,
                            isLoading = isLoadingDevices,
                            onSelect = { selectedDevice = it },
                            onRefresh = { refreshDevices() }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }

                    val device = selectedDevice
                    when (selectedTab) {
                        4 -> SettingsScreen()
                        else -> if (device == null) {
                            NoDevicePlaceholder()
                        } else {
                            when (selectedTab) {
                                0 -> LogcatScreen(device)
                                1 -> UninstallScreen(device)
                                2 -> FilesScreen(device)
                                3 -> DeviceScreen(device)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class NavItem(val label: String, val icon: ImageVector, val tab: Int)

@Composable
private fun AppNavigationRail(selectedTab: Int, onTabSelect: (Int) -> Unit) {
    val items = listOf(
        NavItem("Logcat", Icons.Filled.Article, 0),
        NavItem("Uninstall", Icons.Filled.Delete, 1),
        NavItem("Files", Icons.Filled.Folder, 2),
        NavItem("Device", Icons.Filled.PhoneAndroid, 3),
        NavItem("Settings", Icons.Filled.Settings, 4),
    )

    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        header = {
            Spacer(Modifier.height(4.dp))
            Text(
                "adb",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    ) {
        Spacer(Modifier.height(8.dp))
        items.forEach { item ->
            NavigationRailItem(
                selected = selectedTab == item.tab,
                onClick = { onTabSelect(item.tab) },
                icon = { Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(20.dp)) },
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            )
        }
    }
}

@Composable
private fun NoDevicePlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "No device connected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Connect an Android device via USB or start an emulator",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceBar(
    devices: List<AdbDevice>,
    selected: AdbDevice?,
    isLoading: Boolean,
    onSelect: (AdbDevice) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Filled.PhoneAndroid,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.width(360.dp)
        ) {
            OutlinedTextField(
                value = selected?.displayName ?: "No device",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                )
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (devices.isEmpty()) {
                    DropdownMenuItem(text = { Text("No devices found") }, onClick = {}, enabled = false)
                } else {
                    devices.forEach { device ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Icons.Filled.PhoneAndroid, null, modifier = Modifier.size(16.dp))
                            },
                            text = { Text(device.displayName) },
                            onClick = { onSelect(device); expanded = false }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Refresh, "Refresh devices", modifier = Modifier.size(18.dp))
            }
        }
    }
}
