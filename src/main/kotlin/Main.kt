import adb.AdbService
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import settings.AppSettings
import ui.App

fun main() {
    if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        // Dark appearance so the title text and traffic lights stay legible on the dark title bar
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    }
    AppSettings.adbPath.takeIf { it.isNotEmpty() }?.let { AdbService.adbPath = it }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "ADB GUI",
            icon = painterResource("AppIcon.png"),
            state = rememberWindowState(size = DpSize(1280.dp, 840.dp))
        ) {
            if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.background = java.awt.Color(0x0D, 0x11, 0x17)
            }
            App()
        }
    }
}
