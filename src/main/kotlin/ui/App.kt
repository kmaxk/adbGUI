package ui

import adb.AdbDevice
import adb.AdbService
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Every screen the app can show. [needsDevice] screens get the device bar. */
private enum class Screen(val label: String, val needsDevice: Boolean = true) {
    Logcat("Logcat"),
    Apps("Apps"),
    Deeplinks("Deeplinks"),
    Files("Files"),
    Capture("Screen"),
    Shell("Shell"),
    Batch("Batch"),
    Device("Device"),
    Settings("Settings", needsDevice = false),
    Help("Help", needsDevice = false),
}

/** One rail entry; groups with several screens show a sub-tab strip. */
private class NavGroup(val label: String, val icon: ImageVector, val screens: List<Screen>)

private val DeviceGroups = listOf(
    NavGroup("Logcat", Icons.Filled.Article, listOf(Screen.Logcat)),
    NavGroup("Apps", Icons.Filled.Apps, listOf(Screen.Apps, Screen.Deeplinks)),
    NavGroup("Files", Icons.Filled.Folder, listOf(Screen.Files)),
    NavGroup("Screen", Icons.Filled.Screenshot, listOf(Screen.Capture)),
    NavGroup("Shell", Icons.Filled.Terminal, listOf(Screen.Shell, Screen.Batch)),
    NavGroup("Device", Icons.Filled.PhoneAndroid, listOf(Screen.Device)),
)

// Settings and Help work without a device, so they sit apart as small buttons at the bottom of the rail.
private val SettingsGroup = NavGroup("Settings", Icons.Filled.Settings, listOf(Screen.Settings))
private val HelpGroup = NavGroup("Help", Icons.Filled.HelpOutline, listOf(Screen.Help))

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<AdbDevice?>(null) }
    var selectedGroup by remember { mutableStateOf(DeviceGroups.first()) }
    // Remembers the last sub-tab per group, so switching back lands where you left off
    val lastScreen = remember { mutableStateMapOf<NavGroup, Screen>() }
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

    // Polls `adb devices` so plugging/unplugging a device or starting an emulator
    // updates the device bar without a manual refresh.
    LaunchedEffect(Unit) {
        isLoadingDevices = true
        AdbService.deviceTrackFlow().collect { list ->
            devices = list
            if (selectedDevice == null || selectedDevice !in devices) {
                selectedDevice = devices.firstOrNull()
            }
            isLoadingDevices = false
        }
    }

    val screen = lastScreen[selectedGroup] ?: selectedGroup.screens.first()

    MaterialTheme(colorScheme = AppDarkColorScheme, shapes = AppShapes, typography = AppTypography) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(modifier = Modifier.fillMaxSize()) {
                AppNavigationRail(selected = selectedGroup, onSelect = { selectedGroup = it })

                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                Column(modifier = Modifier.weight(1f)) {
                    if (screen.needsDevice) {
                        DeviceBar(
                            devices = devices,
                            selected = selectedDevice,
                            isLoading = isLoadingDevices,
                            onSelect = { selectedDevice = it },
                            onRefresh = { refreshDevices() }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (selectedGroup.screens.size > 1) {
                        SubTabStrip(
                            screens = selectedGroup.screens,
                            selected = screen,
                            onSelect = { lastScreen[selectedGroup] = it },
                        )
                    }

                    val device = selectedDevice
                    Crossfade(targetState = screen, animationSpec = tween(180)) { current ->
                        when {
                            current == Screen.Settings -> SettingsScreen()
                            current == Screen.Help -> HelpScreen()
                            device == null -> NoDevicePlaceholder()
                            else -> when (current) {
                                Screen.Logcat -> LogcatScreen(device)
                                Screen.Apps -> AppsScreen(device)
                                Screen.Deeplinks -> DeeplinkScreen(device)
                                Screen.Files -> FilesScreen(device)
                                Screen.Capture -> CaptureScreen(device)
                                Screen.Shell -> ShellScreen(device)
                                Screen.Batch -> BatchScreen(device)
                                Screen.Device -> DeviceScreen(device)
                                Screen.Settings, Screen.Help -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavigationRail(selected: NavGroup, onSelect: (NavGroup) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(76.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "adb",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = AppMonoFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        DeviceGroups.forEach { group ->
            NavRailItem(group, selected = selected == group, onClick = { onSelect(group) })
        }
        Spacer(Modifier.weight(1f))
        listOf(SettingsGroup, HelpGroup).forEach { group ->
            NavRailIconItem(group, selected = selected == group, onClick = { onSelect(group) })
        }
    }
}

/** Amber bar on the rail edge marking the active entry. */
@Composable
private fun BoxScope.SelectionBar(height: Dp) {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .width(3.dp)
            .height(height)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
    )
}

@Composable
private fun navContentColor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun NavRailItem(group: NavGroup, selected: Boolean, onClick: () -> Unit) {
    val content = navContentColor(selected)
    Box(modifier = Modifier.fillMaxWidth().height(54.dp)) {
        if (selected) SelectionBar(28.dp)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(64.dp)
                .fillMaxHeight()
                .padding(vertical = 3.dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Icon(group.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Text(group.label, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavRailIconItem(group: NavGroup, selected: Boolean, onClick: () -> Unit) {
    val content = navContentColor(selected)
    TooltipArea(
        tooltip = {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(group.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        tooltipPlacement = TooltipPlacement.ComponentRect(anchor = Alignment.CenterEnd, alignment = Alignment.CenterEnd, offset = DpOffset(6.dp, 0.dp)),
    ) {
        Box(modifier = Modifier.width(76.dp).height(40.dp)) {
            if (selected) SelectionBar(20.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(group.icon, contentDescription = group.label, modifier = Modifier.size(18.dp), tint = content)
            }
        }
    }
}

/** Text tabs for groups that hold more than one screen. */
@Composable
private fun SubTabStrip(screens: List<Screen>, selected: Screen, onSelect: (Screen) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        screens.forEach { s ->
            val isSelected = s == selected
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(s) })
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = navContentColor(isSelected),
                    modifier = Modifier.align(Alignment.Center),
                )
                if (isSelected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun NoDevicePlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Filled.Usb,
            title = "No device connected",
            subtitle = "Plug in a device with USB debugging on, or start an emulator.",
        )
    }
}

private fun connectionKind(serial: String): String = when {
    serial.startsWith("emulator-") -> "Emulator"
    ':' in serial || "adb-tls-connect" in serial -> "Wi-Fi"
    else -> "USB"
}

@Composable
fun DeviceBar(
    devices: List<AdbDevice>,
    selected: AdbDevice?,
    isLoading: Boolean,
    onSelect: (AdbDevice) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var batteryLevel by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(selected?.serial) {
        batteryLevel = null
        selected?.let { batteryLevel = runCatching { AdbService.batteryLevel(it.serial) }.getOrNull() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box {
            DevicePlate(
                device = selected,
                canSwitch = devices.size > 1 || selected == null,
                onClick = { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (devices.isEmpty()) {
                    DropdownMenuItem(text = { Text("No devices found") }, onClick = {}, enabled = false)
                } else {
                    devices.forEach { device ->
                        DropdownMenuItem(
                            leadingIcon = {
                                if (device == selected) {
                                    Icon(Icons.Filled.Check, "Selected", modifier = Modifier.size(16.dp))
                                } else {
                                    Spacer(Modifier.size(16.dp))
                                }
                            },
                            text = {
                                Column {
                                    Text(device.model ?: device.serial, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${device.serial}  ${connectionKind(device.serial)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = AppMonoFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = { onSelect(device); expanded = false }
                        )
                    }
                }
            }
        }
        batteryLevel?.let { BatteryGauge(it) }
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

/** The connected device as a nameplate: status light, model, serial, link type. */
@Composable
private fun DevicePlate(device: AdbDevice?, canSwitch: Boolean, onClick: () -> Unit) {
    val (hoverSource, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(if (hovered.value) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
            .hoverable(hoverSource)
            .clickable(interactionSource = hoverSource, indication = LocalIndication.current, role = Role.DropdownList, onClick = onClick)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusLight(online = device != null)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                device?.model ?: "No device",
                style = MaterialTheme.typography.titleMedium,
                color = if (device != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.serial,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = AppMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        connectionKind(device.serial),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Icon(
            Icons.Filled.UnfoldMore,
            contentDescription = if (canSwitch) "Switch device" else "Device list",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLight(online: Boolean) {
    val signal = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(if (online) signal.copy(alpha = 0.18f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .then(
                    if (online) Modifier.background(signal, CircleShape)
                    else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                )
        )
    }
}

/** Battery as a small cell outline with a proportional fill. */
@Composable
private fun BatteryGauge(level: Int) {
    val low = level <= 15
    val fill = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(3.dp))
                    .padding(2.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(level.coerceIn(0, 100) / 100f)
                        .background(fill, RoundedCornerShape(1.dp))
                )
            }
            Box(
                Modifier
                    .width(2.dp)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp))
            )
        }
        Text(
            "$level%",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = AppMonoFamily,
            color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Battery $level percent" },
        )
    }
}
