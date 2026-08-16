import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Backend URLs come from local.properties so that no machine-specific
// address (or deployed host) is baked into the repository. Both have
// working defaults, so a fresh clone builds without any setup.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Trailing slash is required — Retrofit resolves relative paths against it,
// and without one the last path segment is silently dropped.
val localBaseUrl: String =
    (localProps.getProperty("convoy.baseUrl") ?: "http://10.0.2.2:3000/")
        .let { if (it.endsWith("/")) it else "$it/" }

val releaseBaseUrl: String =
    (localProps.getProperty("convoy.releaseBaseUrl") ?: "https://api.convoy.app/")
        .let { if (it.endsWith("/")) it else "$it/" }

android {
    namespace = "com.convoy.mobile"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.convoy.mobile"
        // 26 gives us foreground services, adaptive icons and the modern
        // location APIs without carrying compatibility shims for Android 7.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            // Where the app looks for the backend.
            //
            // 10.0.2.2 is a special alias meaning "the host machine" that
            // ONLY the emulator understands — on a real phone it resolves to
            // nothing, which is why a debug APK installed over USB cannot
            // reach a server running on the laptop.
            //
            // So this is read from local.properties (which is never
            // committed) and falls back to the emulator alias:
            //
            //   convoy.baseUrl=http://192.168.1.8:3000/
            //
            // Keeping it out of the repo matters — a hardcoded LAN IP is
            // both useless to anyone else and a small leak of your network.
            buildConfigField("String", "BASE_URL", "\"${localBaseUrl}\"")
            buildConfigField("String", "SOCKET_URL", "\"${localBaseUrl.trimEnd('/')}\"")
            isDebuggable = true
        }
        release {
            buildConfigField("String", "BASE_URL", "\"${releaseBaseUrl}\"")
            buildConfigField("String", "SOCKET_URL", "\"${releaseBaseUrl.trimEnd('/')}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Java 8 bytecode, matching the other Android project on this machine.
    // Android Studio builds this with JDK 17; the command-line script uses
    // JDK 11 to sidestep a loopback issue in sandboxed shells, and JDK 11
    // cannot emit Java 17 bytecode. Targeting 8 keeps both paths working.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        // Must match Kotlin 1.9.22.
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    // AGP 7.4 spells this `packagingOptions`; `packaging` arrived in AGP 8.
    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ── Core ────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // ── Compose ─────────────────────────────────────────────────
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ── Hilt ────────────────────────────────────────────────────
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // ── Network ─────────────────────────────────────────────────
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // ── Live layer ──────────────────────────────────────────────
    // Matches the Socket.IO 4.x server in the backend.
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }

    // ── Map ─────────────────────────────────────────────────────
    // MapLibre: no API key, no account, no per-request billing, and we
    // style both the day and night maps ourselves.
    implementation("org.maplibre.gl:android-sdk:11.0.0")

    // ── Location ────────────────────────────────────────────────
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // ── Coroutines ──────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ── Images ──────────────────────────────────────────────────
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
}
