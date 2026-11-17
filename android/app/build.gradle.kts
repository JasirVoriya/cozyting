// Rust 核心先编译进 jniLibs：
//   cd ../core && cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release --features jni
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miplay.sender"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.miplay.sender"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
        debug { isDebuggable = true }
        // 正式版：R8 压缩+混淆；包名加 .release，可与已装的 debug 版并存（签名不同，不能覆盖安装）。
        // Gradle 只产出未签名 APK，签名在 box 上用 /home/box/.secrets/miplay-release.jks 做（keystore 不出 box）。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".release"
            versionNameSuffix = "-release"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
