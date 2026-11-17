// Rust 核心先编译进 jniLibs：
//   cd .. && cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build --release -p miplay-sender-core --features jni
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miplay.sender"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.miplay.sender"
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-app-capture"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { isDebuggable = true }
        // 正式版：R8 压缩+混淆；包名加 .release，可与已装的 debug 版并存。
        // 本环境只产出 debug / unsigned release，不创建新签名密钥。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".release"
            versionNameSuffix = "-release"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
