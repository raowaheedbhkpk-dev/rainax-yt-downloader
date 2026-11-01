import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// RAINAX for Windows: Compose Multiplatform (the desktop twin of the Jetpack Compose the phone app uses).
// Built on GitHub (Actions > "Build RAINAX for Windows"): the run's artifact holds the .msi and .exe installers.

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
}

group = "com.rainax"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // the same extractor and version as the phone app
    implementation("com.github.TeamNewPipe:NewPipeExtractor:13a655fe53e0c3065f88725fc1fb594c3ede0169")
    // Dispatchers.Main on the desktop (the window's thread)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "rainax.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "RAINAX Tube"
            packageVersion = "1.0.0"
            description = "Watch and download videos"
            vendor = "RAINAX"
            copyright = "RAINAX"
            // the extractor needs parts of Java that the trimmed runtime leaves out: ship all of them
            includeAllModules = true
            // ffmpeg.exe (put here by the build workflow) joins two parts into one video and makes MP3s
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "RAINAX"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // keep this the same forever: Windows uses it to install new versions over old ones
                upgradeUuid = "6b4f3d1e-6a2c-4b9e-9a77-3c2e1f0d8a51"
            }
        }
    }
}
