# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

adbGUI — desktop GUI for Android Debug Bridge. Single-module Kotlin/JVM app, Compose for Desktop 1.6.11 + Material 3, Kotlin 2.0, JDK 17 toolchain. Not actually multiplatform despite the `KMP` parent folder: only `src/main/kotlin`, one `build.gradle.kts`.

## Commands

```bash
./gradlew run            # launch app from source
./gradlew build          # compile + checks (what CI's validate job runs)
./gradlew packageDmg     # macOS; also packagePkg, packageDeb, packageRpm, packageMsi, packageExe
```

Packages land in `build/compose/binaries/main/<format>/`. There are no tests in the repo.

- `packageDmg` needs a JDK with `jpackage` (JBR 17 full / Temurin 17). Android Studio's bundled JBR lacks it; Homebrew JDK breaks Compose `checkRuntime`. Locally this is pinned via `org.gradle.java.home` in `gradle.properties`, which is **gitignored** (machine-specific) — don't commit it.
- Version is duplicated in `build.gradle.kts`: `version` and `nativeDistributions.packageVersion` — bump both.

## CI / Releases

`.github/workflows/build-packages.yml`: `validate` (`./gradlew build`) → matrix `build` (dmg on macOS, deb on Ubuntu, msi on Windows) → `release` job only on `v*` tags, attaches artifacts to a GitHub release. Triggers on push/PR to `master`.

## Architecture

Three layers, no DI, no ViewModels:

- **`adb/AdbService.kt`** — singleton `object`, the only place that talks to adb. Shells out to the `adb` binary via `ProcessBuilder` (no adb client library). Every call takes the device `serial` and runs `adb -s <serial> ...`. Conventions:
  - one-shot calls are `suspend fun ... = withContext(Dispatchers.IO)`, returning `Result<T>` when failure matters to the UI;
  - streaming (logcat, device polling via `deviceTrackFlow` at 1 s interval) is exposed as cold `Flow`s;
  - long-running processes (screen recording) are held in a per-serial map;
  - paths passed into `adb shell` must go through `shellQuote`;
  - `adbPath` is auto-detected (`PATH`, Homebrew, `~/Library/Android/sdk/platform-tools`) and overridden at startup in `Main.kt` from settings.
- **`settings/AppSettings.kt`** — persistence via `java.util.prefs.Preferences` (adb path, deeplink history, saved batch scripts). No files/DB.
- **`ui/`** — one `@Composable` screen per tab, state held locally with `remember`/`rememberCoroutineScope`, calling `AdbService` directly. `Theme.kt` defines the dark-only color scheme, shapes and typography (bundled JetBrains Mono in `src/main/resources/fonts`).

`ui/App.kt` is the root: navigation rail + device bar + optional sub-tab strip + screen content. Navigation is data-driven: the `Screen` enum lists every screen (`needsDevice = false` hides the device bar, e.g. Settings/Help), and `NavGroup`s map rail entries to one or more screens — groups with several screens (Apps → Apps/Deeplinks, Shell → Shell/Batch) get a sub-tab strip. Adding a screen: add an enum entry, put it in a group, and add its branch to the `when` in `App()`. Device-dependent screens receive the selected `AdbDevice` and are only shown when one exists.

`BatchScreen` runs line-based scripts: keywords `tap`, `swipe`, `text`, `key`, `wait`, `monkey` are handled in `runStep`; any other line is passed to `adb shell`.

## Notes

- README's "Project Structure" section is outdated (missing `DeeplinkScreen`, `BatchScreen`, `HelpScreen`); update it when touching structure.
- `HelpScreen` documents features in-app — keep it in sync when adding user-facing features.
