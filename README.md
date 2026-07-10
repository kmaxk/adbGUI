# adbGUI

A desktop GUI for the Android Debug Bridge (adb), built with Kotlin and Compose for Desktop.

adbGUI wraps the most common adb workflows — logcat, app management, file browsing, screenshots, screen recording, shell access and device configuration — in a single dark-themed desktop app, so you don't have to remember or retype adb commands.

## Features

The app is organized into tabs, with a device selector bar (including live battery level) at the top. Multiple connected devices and emulators are supported; all actions target the currently selected device.

### Logcat
- Live logcat stream (`adb logcat -v time`) with auto-scroll
- Filter by running app/process (`--pid`)
- Clear the log buffer

### Apps
- List all installed packages
- Install APKs (`adb install -r`)
- Launch, force-stop, uninstall apps and clear app data
- Detailed app info: version, min/target SDK, install dates, installer, APK path, data directory

### Files
- Browse the device file system with directory/symlink awareness, file sizes and modification dates
- Pull files to the local machine
- Push local files to the device
- Delete files and directories (recursive)

### Screen
- Take screenshots (`screencap` via `exec-out`) with live preview and PNG export
- Record the screen (`screenrecord`) and pull the MP4 when stopped
- Launch [scrcpy](https://github.com/Genymobile/scrcpy) for live screen mirroring (if installed)
- Send text input and key events to the device

### Shell
- Run arbitrary `adb shell` commands with output display

### Device
- Device details: model, manufacturer, Android version, SDK level, build, ABI, resolution, IP address, battery status/temperature/health
- Reboot into system, recovery or bootloader
- Wireless debugging: enable TCP/IP mode (`adb tcpip 5555`), connect/disconnect by `ip:port`, pair via pairing code (Android 11+)
- Port forwarding: list, add and remove `adb forward` rules
- Display overrides: change screen size (`wm size`) and density (`wm density`), with reset
- Open deeplinks/URLs on the device (`am start -a android.intent.action.VIEW`)

### Settings
- Configure the path to the adb binary manually, or use auto-detection (checks `PATH`, Homebrew locations and `~/Library/Android/sdk/platform-tools/adb`)

## Requirements

- **JDK 17** (to build and run from source)
- **adb** (Android platform-tools) installed and reachable — auto-detected or configurable in Settings
- **scrcpy** (optional) for screen mirroring: `brew install scrcpy`

## Getting Started

### Run from source

```bash
./gradlew run
```

### Build native packages

```bash
./gradlew packageDmg   # macOS (.dmg)
./gradlew packageDeb   # Linux (.deb)
./gradlew packageRpm   # Linux (.rpm)
./gradlew packagePkg   # macOS (.pkg)
```

Packages are written to `build/compose/binaries/main/<format>/`.

> **Note (macOS):** `packageDmg` requires a JDK that ships `jpackage` (e.g. a Temurin/JBR 17 full JDK). The JBR bundled with Android Studio does not include it.

### Releases

GitHub Actions builds `.dmg` (macOS) and `.deb` (Linux) packages on every push to `master`. Pushing a tag matching `v*` attaches the packages to a GitHub release. See [`.github/workflows/build-packages.yml`](.github/workflows/build-packages.yml).

## Tech Stack

- [Kotlin](https://kotlinlang.org/) 2.0 (JVM)
- [Compose for Desktop](https://www.jetbrains.com/compose-multiplatform/) with Material 3
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) for async adb calls and logcat streaming
- No adb client library — the app shells out to the `adb` binary via `ProcessBuilder`

## Project Structure

```
src/main/kotlin/
├── Main.kt                  # Application entry point / window setup
├── adb/
│   └── AdbService.kt        # All adb interaction: process execution, parsing, flows
├── settings/
│   └── AppSettings.kt       # Persistent settings (java.util.prefs)
└── ui/
    ├── App.kt               # Root layout, navigation rail, device selector bar
    ├── Theme.kt             # Dark color scheme, shapes, typography
    ├── LogcatScreen.kt      # Live log viewer
    ├── AppsScreen.kt        # Package management
    ├── FilesScreen.kt       # File browser
    ├── CaptureScreen.kt     # Screenshot / recording / scrcpy / input
    ├── ShellScreen.kt       # Interactive shell
    ├── DeviceScreen.kt      # Device info, reboot, wireless, forwards, display
    └── SettingsScreen.kt    # ADB path configuration
```

## Security

See [SECURITY.md](SECURITY.md) for the security policy.