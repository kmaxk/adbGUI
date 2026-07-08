import adb.AdbService
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import settings.AppSettings
import ui.App

fun main() {
    AppSettings.adbPath.takeIf { it.isNotEmpty() }?.let { AdbService.adbPath = it }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "ADB GUI",
            state = rememberWindowState(size = DpSize(1280.dp, 840.dp))
        ) {
            App()
        }
    }
}
