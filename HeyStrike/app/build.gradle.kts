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
	versionCode = 19
	versionName = "2.5.4"
        // F23 is arm64; strip other ABIs from the 44MB multi-arch sherpa AAR
        ndk { abiFilters += setOf("arm64-v8a") }
    }
    signingConfigs {
        // Pinned throwaway key (HeyStrike/keystore/release.p12) so every CI
        // build shares one signature and updates install cleanly. NOT a
        // production secret â€” debug-grade app key only.
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
    packaging {
        // sherpa AAR ships desktop natives (osx/win dylibs+dlls, ~110MB) â€”
        // only lib/arm64-v8a/*.so matters on the phone
        resources.excludes += "sherpa-onnx/native/**"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Offline wake-word gate + command ASR (no cloud, no popup)
    implementation("com.alphacephei:vosk-android:0.3.75")
    // Streaming command ASR: Zipformer 20M int8 (sherpa-onnx, ARM64).
    // The JitPack aggregator POM also pulls the desktop JVM jar â€” exclude it
    // (its classes duplicate the AAR and break dexing).
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.8") {
        exclude(group = "com.github.k2-fsa", module = "sherpa-onnx-jvm")
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
    // Vision capture (spec 11): CameraX preview + one-shot stills, camera2 backend
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
}
