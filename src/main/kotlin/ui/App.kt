package ui

import adb.AdbDevice
import adb.AdbService
import device.Device
import device.Platform
import ios.IosDevice
import ios.IosService
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private val AllPlatforms = setOf(Platform.Android, Platform.IOS)

/**
 * Every screen the app can show. [needsDevice] screens get the device bar;
 * [platforms] decides which device types see the screen at all.
 */
private enum class Screen(
    val label: String,
    val needsDevice: Boolean = true,
    val platforms: Set<Platform> = AllPlatforms,
) {
    Logcat("Logs"),
    Apps("Apps"),
    Deeplinks("Deeplinks"),
    Files("Files"),
    Capture("Screen"),
    Shell("Shell", platforms = setOf(Platform.Android)),
    Batch("Batch", platforms = setOf(Platform.Android)),
    Device("Device"),
    Settings("Settings", needsDevice = false),
    Help("Help", needsDevice = false),
}

/** One rail entry; groups with several screens show a sub-tab strip. */
private class NavGroup(val label: String, val icon: ImageVector, val screens: List<Screen>) {
    /** Screens available for [platform]; all of them when no device is selected. */
    fun screensFor(platform: Platform?) =
        if (platform == null) screens else screens.filter { platform in it.platforms }
}

private val DeviceGroups = listOf(
    NavGroup("Logs", Icons.Filled.Article, listOf(Screen.Logcat)),
    NavGroup("Apps", Icons.Filled.Apps, listOf(Screen.Apps, Screen.Deeplinks)),
    NavGroup("Files", Icons.Filled.Folder, listOf(Screen.Files)),
    NavGroup("Screen", Icons.Filled.Screenshot, listOf(Screen.Capture)),
    NavGroup("Shell", Icons.Filled.Terminal, listOf(Screen.Shell, Screen.Batch)),
    NavGroup("Device", Icons.Filled.Smartphone, listOf(Screen.Device)),
)

// Settings and Help work without a device, so they sit apart as small buttons at the bottom of the rail.
private val SettingsGroup = NavGroup("Settings", Icons.Filled.Settings, listOf(Screen.Settings))
private val HelpGroup = NavGroup("Help", Icons.Filled.HelpOutline, listOf(Screen.Help))
private val AppGroups = listOf(SettingsGroup, HelpGroup)

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var selectedGroup by remember { mutableStateOf(DeviceGroups.first()) }
    // Remembers the last sub-tab per group, so switching back lands where you left off
    val lastScreen = remember { mutableStateMapOf<NavGroup, Screen>() }
    var isLoadingDevices by remember { mutableStateOf(false) }

    // Keeps the selection by id, so a simulator going from Booting to Booted stays selected
    fun applyDevices(list: List<Device>) {
        devices = sortDevices(list)
        selectedDevice = devices.find { it.id == selectedDevice?.id }
            ?: devices.firstOrNull { it.isReady }
            ?: devices.firstOrNull()
    }

    fun refreshDevices() {
        scope.launch {
            isLoadingDevices = true
            applyDevices(AdbService.devices() + IosService.devices())
            isLoadingDevices = false
        }
    }

    // Polls adb, simctl and devicectl so plugging in a device or booting a simulator
    // updates the device bar without a manual refresh.
    LaunchedEffect(Unit) {
        isLoadingDevices = true
        combine(AdbService.deviceTrackFlow(), IosService.deviceTrackFlow()) { android, ios -> android + ios }
            .collect { list ->
                applyDevices(list)
                isLoadingDevices = false
            }
    }

    val platform = selectedDevice?.platform
    val visibleGroups = DeviceGroups.filter { it.screensFor(platform).isNotEmpty() }
    // Fall back when the selected device's platform has no screen in the chosen group (e.g. Shell on iOS)
    val group = if (selectedGroup in visibleGroups || selectedGroup in AppGroups) selectedGroup else visibleGroups.first()
    val groupScreens = group.screensFor(platform)
    val screen = lastScreen[group]?.takeIf { it in groupScreens } ?: groupScreens.first()

    MaterialTheme(colorScheme = AppDarkColorScheme, shapes = AppShapes, typography = AppTypography) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(modifier = Modifier.fillMaxSize()) {
                AppNavigationRail(groups = visibleGroups, selected = group, onSelect = { selectedGroup = it })

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
                    if (groupScreens.size > 1) {
                        SubTabStrip(
                            screens = groupScreens,
                            selected = screen,
                            onSelect = { lastScreen[group] = it },
                        )
                    }

                    val device = selectedDevice
                    Crossfade(targetState = screen, animationSpec = tween(180)) { current ->
                        when {
                            current == Screen.Settings -> SettingsScreen()
                            current == Screen.Help -> HelpScreen()
                            device == null -> NoDevicePlaceholder()
                            device is AdbDevice -> AndroidScreen(current, device)
                            device is IosDevice && !device.isReady -> SimulatorBootPrompt(device)
                            device is IosDevice -> IosScreen(current, device)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidScreen(screen: Screen, device: AdbDevice) {
    when (screen) {
        Screen.Logcat -> LogcatScreen(device)
        Screen.Apps -> AppsScreen(device)
        Screen.Deeplinks -> DeeplinkScreen(openUrl = { AdbService.openUrl(device.serial, it) })
        Screen.Files -> FilesScreen(device)
        Screen.Capture -> CaptureScreen(device)
        Screen.Shell -> ShellScreen(device)
        Screen.Batch -> BatchScreen(device)
        Screen.Device -> DeviceScreen(device)
        Screen.Settings, Screen.Help -> Unit
    }
}

@Composable
private fun IosScreen(screen: Screen, device: IosDevice) {
    when (screen) {
        Screen.Logcat -> IosLogsScreen(device)
        Screen.Apps -> IosAppsScreen(device)
        Screen.Deeplinks -> DeeplinkScreen(openUrl = { IosService.openUrl(device, it) })
        Screen.Files -> IosFilesScreen(device)
        Screen.Capture -> IosCaptureScreen(device)
        Screen.Device -> IosDeviceScreen(device)
        Screen.Shell, Screen.Batch, Screen.Settings, Screen.Help -> Unit
    }
}

/** Android first, then running iOS targets, shut-down simulators last. */
private fun sortDevices(list: List<Device>): List<Device> = list.sortedWith(
    compareBy<Device>({ it.platform != Platform.Android }, { !it.isReady }, { (it as? IosDevice)?.isSimulator == true }, { it.name })
)

/** Replaces device screens while the selected simulator is shut down. */
@Composable
private fun SimulatorBootPrompt(device: IosDevice) {
    val scope = rememberCoroutineScope()
    var isBooting by remember(device.udid) { mutableStateOf(false) }
    var error by remember(device.udid) { mutableStateOf<String?>(null) }
    val booting = isBooting || device.state == "Booting"
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EmptyState(
                icon = Icons.Filled.PowerSettingsNew,
                title = if (booting) "Booting ${device.name}" else "${device.name} is shut down",
                subtitle = if (booting) "This takes a few seconds." else "Boot the simulator to use it.",
            )
            if (booting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Button(onClick = {
                    scope.launch {
                        isBooting = true
                        error = null
                        IosService.boot(device)
                            .onSuccess { IosService.openSimulatorApp(device) }
                            .onFailure { error = it.message; isBooting = false }
                        // The device poll flips the state to Booted and swaps this prompt out
                    }
                }) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Boot simulator")
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AppNavigationRail(groups: List<NavGroup>, selected: NavGroup, onSelect: (NavGroup) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(76.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppLogo(size = 36.dp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
        groups.forEach { group ->
            NavRailItem(group, selected = selected == group, onClick = { onSelect(group) })
        }
        Spacer(Modifier.weight(1f))
        AppGroups.forEach { group ->
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
                // Intrinsic width keeps the fillMaxWidth underline from stretching the tab
                modifier = Modifier
                    .width(IntrinsicSize.Max)
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
            subtitle = "Plug in a device with USB debugging on, or start an emulator or simulator.",
        )
    }
}

private fun connectionKind(serial: String): String = when {
    serial.startsWith("emulator-") -> "Emulator"
    ':' in serial || "adb-tls-connect" in serial -> "Wi-Fi"
    else -> "USB"
}

/** Platform and link tags shown next to the device id. */
private fun deviceTags(device: Device): List<String> = when (device) {
    is AdbDevice -> listOf("Android", connectionKind(device.serial))
    is IosDevice -> listOf(
        "iOS ${device.osVersion}".trim(),
        when {
            device.isSimulator -> "Simulator"
            device.transport == "wired" -> "USB"
            else -> "Wi-Fi"
        },
    )
    else -> emptyList()
}

@Composable
fun DeviceBar(
    devices: List<Device>,
    selected: Device?,
    isLoading: Boolean,
    onSelect: (Device) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var batteryLevel by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(selected?.id) {
        batteryLevel = null
        (selected as? AdbDevice)?.let { batteryLevel = runCatching { AdbService.batteryLevel(it.serial) }.getOrNull() }
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
                                if (device.id == selected?.id) {
                                    Icon(Icons.Filled.Check, "Selected", modifier = Modifier.size(16.dp))
                                } else {
                                    Spacer(Modifier.size(16.dp))
                                }
                            },
                            text = {
                                // Shut-down simulators are listed dimmed; selecting one offers to boot it
                                val dim = if (device.isReady) 1f else 0.55f
                                Column {
                                    Text(
                                        device.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                                    )
                                    Text(
                                        (listOf(device.id) + deviceTags(device)).joinToString("  "),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = AppMonoFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
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
private fun DevicePlate(device: Device?, canSwitch: Boolean, onClick: () -> Unit) {
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
        StatusLight(online = device?.isReady == true)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                device?.name ?: "No device",
                style = MaterialTheme.typography.titleMedium,
                color = if (device != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.id,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = AppMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    deviceTags(device).forEach { tag ->
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
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
