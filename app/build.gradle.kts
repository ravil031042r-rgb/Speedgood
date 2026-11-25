plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.speedgod.module"
    compileSdk = 34
    ndkVersion = "26.3.11579264"
    defaultConfig {
        applicationId = "com.speedgod.module"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-O2", "-fvisibility=hidden"); arguments += listOf("-DANDROID_STL=c++_static") } }
    }
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "speedgod123"
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "speedgod"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "speedgod123"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")
            signingConfig = if (!ksPath.isNullOrBlank() && file(ksPath).exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
    packaging { jniLibs { useLegacyPackaging = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    compileOnly(project(":xposed-stub"))
    implementation("com.bytedance.android:shadowhook:1.0.10")
}
