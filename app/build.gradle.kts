@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fi.tunturi.rows"
    // Compose BOM 2026.09 requires compiling against API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "fi.tunturi.rows"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.0"
    }

    // Release signing comes from the environment (CI secrets) or local.properties.
    // Without either, release builds fall back to the debug key so the build still
    // produces an installable APK instead of failing.
    val keystorePath = (System.getenv("KEYSTORE_PATH")
        ?: project.findProperty("KEYSTORE_PATH") as String?)?.takeIf { it.isNotBlank() }
    val keystoreFile = keystorePath?.let { file(it) }?.takeIf { it.isFile }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASS") ?: project.findProperty("KEYSTORE_PASS") as String?
                keyAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS") as String?
                keyPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Separate modules: without these, .m3u8/.mpd streams crash at playback time.
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    // Resolves YouTube links to stream URLs for playback and offline saving.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    testImplementation("junit:junit:4.13.2")
}
