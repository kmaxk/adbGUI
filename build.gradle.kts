import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

group = "de.adbgui"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin")
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "adbGUI"
            packageVersion = "1.0.0"
            description = "ADB device manager for Android developers"
            copyright = "© 2024 adbGUI"
            vendor = "adbGUI"

            macOS {
                bundleID = "de.adbgui"
                minimumSystemVersion = "12.0"
                dockName = "adbGUI"
                iconFile.set(project.file("src/main/resources/AppIcon.icns"))
            }

            linux {
                iconFile.set(project.file("src/main/resources/AppIcon.png"))
            }
        }
        buildTypes.release.proguard {
            isEnabled = false
        }
    }
}
