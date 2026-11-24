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
        versionCode = 1
        versionName = "1.0"
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
