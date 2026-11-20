plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miplay.testtone"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // 测试工具仅 arm64，与发送端一致
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    flavorDimensions += "tone"
    productFlavors {
        create("a") {
            dimension = "tone"
            applicationId = "com.miplay.testtone.a"
            buildConfigField("double", "TONE_HZ", "1000.0")
            buildConfigField("boolean", "ALLOW_CAPTURE", "true")
            resValue("string", "app_name", "受控音源A·1kHz")
            resValue("string", "tone_desc", "可捕获 · 1000Hz 正弦 · 独立UID")
            manifestPlaceholders["allowCapture"] = "true"
        }
        create("b") {
            dimension = "tone"
            applicationId = "com.miplay.testtone.b"
            buildConfigField("double", "TONE_HZ", "2000.0")
            buildConfigField("boolean", "ALLOW_CAPTURE", "true")
            resValue("string", "app_name", "受控音源B·2kHz")
            resValue("string", "tone_desc", "可捕获 · 2000Hz 正弦 · 独立UID")
            manifestPlaceholders["allowCapture"] = "true"
        }
        create("deny") {
            dimension = "tone"
            applicationId = "com.miplay.testtone.deny"
            buildConfigField("double", "TONE_HZ", "1500.0")
            buildConfigField("boolean", "ALLOW_CAPTURE", "false")
            resValue("string", "app_name", "受控音源·禁止捕获")
            resValue("string", "tone_desc", "allowAudioPlaybackCapture=false · 1500Hz")
            manifestPlaceholders["allowCapture"] = "false"
        }
    }
    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
