package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class HelpSection(
    val title: String,
    val icon: ImageVector,
    val points: List<String>,
)

private val HELP_SECTIONS = listOf(
    HelpSection(
        "Logcat", Icons.Filled.Article,
        listOf(
            "Live logcat stream (`adb logcat -v time`) with auto-scroll and jump-to-bottom",
            "Filter by running app/process (--pid), or type a search term",
            "Toggle regex filtering; toggle highlight-only mode to mark matches without hiding other lines",
            "Set minimum log level (V/D/I/W/E/F), color-coded by severity",
            "Pause/resume the stream, clear the device log buffer, export visible lines to a file",
        )
    ),
    HelpSection(
        "Apps", Icons.Filled.Apps,
        listOf(
            "List all installed packages",
            "Install APKs (adb install -r)",
            "Launch, force-stop, uninstall apps and clear app data",
            "View detailed app info: version, min/target SDK, install dates, installer, APK path, data directory",
        )
    ),
    HelpSection(
        "Files", Icons.Filled.Folder,
        listOf(
            "Browse the device file system with directory/symlink awareness, sizes and modification dates",
            "Pull files to the local machine, push local files to the device",
            "Delete files and directories (recursive)",
        )
    ),
    HelpSection(
        "Screen", Icons.Filled.Screenshot,
        listOf(
            "Take screenshots (screencap via exec-out) with live preview and PNG export",
            "Record the screen (screenrecord) and pull the MP4 when stopped",
            "Launch scrcpy for live screen mirroring (if installed)",
            "Send text input and key events to the device",
        )
    ),
    HelpSection(
        "Shell", Icons.Filled.Terminal,
        listOf(
            "Run arbitrary adb shell commands and view the output",
        )
    ),
    HelpSection(
        "Device", Icons.Filled.PhoneAndroid,
        listOf(
            "View device details: model, manufacturer, Android version, SDK level, build, ABI, resolution, IP, battery",
            "Reboot into system, recovery or bootloader",
            "Wireless debugging: enable TCP/IP mode, connect/disconnect by ip:port, pair via pairing code (Android 11+)",
            "Port forwarding: list, add and remove adb forward rules",
            "Display overrides: change screen size (wm size) and density (wm density), with reset",
            "Open deeplinks/URLs on the device",
        )
    ),
    HelpSection(
        "Settings", Icons.Filled.Settings,
        listOf(
            "Configure the path to the adb binary manually, or use auto-detection",
        )
    ),
    HelpSection(
        "Device bar", Icons.Filled.PhoneAndroid,
        listOf(
            "Connected devices/emulators are detected automatically — no manual refresh needed",
            "Switch between multiple connected devices; battery level shown for the selected device",
        )
    ),
)

@Composable
fun HelpScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
                Text("Help", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(HELP_SECTIONS) { section ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(section.icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        section.points.forEach { point ->
                            Row(modifier = Modifier.padding(bottom = 4.dp)) {
                                Text("•  ", style = MaterialTheme.typography.bodySmall)
                                Text(point, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
