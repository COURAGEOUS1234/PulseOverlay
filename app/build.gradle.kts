plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pulse.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pulse.overlay"
        minSdk = 29          // AudioPlaybackCapture needs Android 10
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    // Fixed debug key committed to the repo so each CI build installs over the last one.
    // It's the standard public debug key format (password "android"): not a secret, not for Play.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// No library dependencies: framework APIs only.
