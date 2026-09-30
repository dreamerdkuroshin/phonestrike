plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.heystrike.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.heystrike.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }
    signingConfigs {
        // Pinned throwaway key (HeyStrike/keystore/release.p12) so every CI
        // build shares one signature and updates install cleanly. NOT a
        // production secret — debug-grade app key only.
        create("fixed") {
            storeFile = rootProject.file("keystore/release.p12")
            storePassword = "heystrike123"
            keyAlias = "1"
            keyPassword = "heystrike123"
        }
    }
    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Offline wake-word gate + command ASR (no cloud, no popup)
    implementation("com.alphacephei:vosk-android:0.3.75")
}
