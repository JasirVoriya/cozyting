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
        versionCode = 11
        versionName = "0.9.2-cast-panel"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "IS_ACCEPTANCE", "false")
    }

    signingConfigs {
        create("acceptance") {
            val ks = file("acceptance.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "miplay-acceptance"
                keyAlias = "acceptance"
                keyPassword = "miplay-acceptance"
            }
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("standard") {
            dimension = "channel"
            isDefault = true
            buildConfigField("boolean", "IS_ACCEPTANCE", "false")
        }
        create("acceptance") {
            dimension = "channel"
            applicationId = "com.miplay.sender.acceptance"
            versionNameSuffix = "-acceptance"
            buildConfigField("boolean", "IS_ACCEPTANCE", "true")
            signingConfig = signingConfigs.getByName("acceptance")
        }
    }

    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".release"
            versionNameSuffix = "-release"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

androidComponents {
    onVariants(selector().withFlavor("channel" to "acceptance")) { variant ->
        val cfg = android.signingConfigs.getByName("acceptance")
        // AGP 8：通过 packaging/signing 由 flavor.signingConfig 提供；若 debug 覆盖则构建后 apksigner 重签
        variant.signingConfig.setConfig(cfg)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    // 单元测试使用真实 org.json（平台 stub 会抛 Method not mocked）
    testImplementation("org.json:json:20240303")
}
