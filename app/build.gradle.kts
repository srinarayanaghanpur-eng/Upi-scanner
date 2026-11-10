plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.upiscan"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.upiscan"
        minSdk = 28          // physical-camera selection API (OutputConfiguration.setPhysicalCameraId)
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += "arm64-v8a" }   // Galaxy M51 is arm64; keeps APK small
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs for personal use.
            // For distribution, replace with your own keystore (see README).
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("com.google.mlkit:barcode-scanning:17.3.0")   // bundled model: fully on-device
}
