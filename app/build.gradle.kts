plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jianmu.auto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jianmu.auto"
        minSdk = 30            // AccessibilityService.takeScreenshot 需要 Android 11
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"

        ndk {
            // OpenCV 的原生库每个 CPU 架构约十几 MB；现在的手机基本都是 arm64，只打这一个
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // OpenCV 官方安卓包（Maven Central，4.9.0 起提供）；用之前要先调用 OpenCVLoader.initLocal()
    implementation("org.opencv:opencv:4.9.0")
}
