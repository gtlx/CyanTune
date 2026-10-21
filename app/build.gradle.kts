plugins {
    id("com.android.application")
    kotlin("android") version "1.9.22"
}

android {
    namespace = "com.gtlx.cyantune"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gtlx.cyantune"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    // Xposed API 82 —— compileOnly，不打进 apk，由框架在运行时提供
    compileOnly(files("libs/xposed-api-82.jar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}
