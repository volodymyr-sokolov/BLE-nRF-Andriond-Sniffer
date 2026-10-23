plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Copies the built DFU package into the APK so the board can be updated with no
 * file to find first.
 *
 * Wired into the build rather than checked in as a binary: an embedded firmware
 * that has to be refreshed by hand is one that silently ships stale. The version
 * is lifted from the firmware source at the same moment, so what the app offers
 * and what it installs cannot disagree.
 *
 * A missing package is not an error - the app simply hides the bundled option -
 * because the Android project must stay buildable on a machine that has never
 * run PlatformIO.
 */
val firmwareAssets = layout.buildDirectory.dir("generated/firmwareAssets")

val bundleFirmware = tasks.register("bundleFirmware") {
    val packageFile = rootProject.file("../firmware/.pio/build/adafruit_feather_nrf52840/firmware.zip")
    val sourceFile = rootProject.file("../firmware/src/main.cpp")
    val outputDir = firmwareAssets

    inputs.files(files(packageFile, sourceFile)).withPropertyName("firmwareInputs")
    outputs.dir(outputDir).withPropertyName("firmwareAssets")

    doLast {
        val target = outputDir.get().asFile.resolve("firmware")
        target.deleteRecursively()
        target.mkdirs()

        if (!packageFile.isFile) {
            logger.lifecycle("No firmware package at $packageFile - the bundled update will be unavailable.")
            return@doLast
        }

        packageFile.copyTo(target.resolve("firmware.zip"), overwrite = true)

        val version = sourceFile.takeIf { it.isFile }
            ?.readLines()
            ?.firstNotNullOfOrNull { line ->
                Regex("""FW_VERSION\[]\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            }
            ?: "unknown"

        target.resolve("version.txt").writeText(version)
        logger.lifecycle("Bundled nRF52840 firmware $version (${packageFile.length()} bytes)")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(bundleFirmware) }

android {
    namespace = "com.blesniffer"
    compileSdk = 36

    sourceSets["main"].assets.srcDir(firmwareAssets)

    defaultConfig {
        applicationId = "com.blesniffer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// AndroidX releases from 2026 onwards require AGP 9.1+ and compileSdk 37. This
// project is on AGP 8.13.2, whose maximum supported compileSdk is 36, so the
// dependencies below are pinned to the last generation built against API 36.
// Raising them means moving AGP and Gradle forward together.
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is stub-only; the real implementation lets the
    // export/import round-trip run as a plain JVM test.
    testImplementation("org.json:json:20250107")
}
