// Rust 核心先编译进 jniLibs：
//   cd .. && cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build --release -p cozyting-core --features jni
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProps = Properties().apply {
    val propFile = rootProject.file("keystore.properties").let {
        if (it.exists()) it else file("keystore.properties")
    }
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

fun signingProp(envKey: String, propKey: String, default: String = ""): String =
    System.getenv(envKey)?.takeIf { it.isNotEmpty() }
        ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotEmpty() }
        ?: default

android {
    namespace = "com.cozyting.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cozyting.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 33
        versionName = "0.9.11-rc.1"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "IS_ACCEPTANCE", "false")
        buildConfigField("boolean", "ENABLE_SPONSOR_ENTRY", "true")
    }

    signingConfigs {
        create("acceptance") {
            val ks = file("acceptance.keystore")
            if (ks.exists()) {
                storeFile = ks
                val sp = signingProp("MIPLAY_ACCEPTANCE_STORE_PASSWORD", "acceptance.storePassword")
                storePassword = sp
                keyAlias = signingProp("MIPLAY_ACCEPTANCE_KEY_ALIAS", "acceptance.keyAlias", "acceptance")
                keyPassword = signingProp("MIPLAY_ACCEPTANCE_KEY_PASSWORD", "acceptance.keyPassword", sp)
            }
        }
        create("release") {
            val ksPath = signingProp("MIPLAY_RELEASE_STORE_FILE", "release.storeFile", "release.keystore")
            val ks = file(ksPath)
            val sp = signingProp("MIPLAY_RELEASE_STORE_PASSWORD", "release.storePassword")
            if (ks.exists() && sp.isNotEmpty()) {
                storeFile = ks
                storePassword = sp
                keyAlias = signingProp("MIPLAY_RELEASE_KEY_ALIAS", "release.keyAlias", "release")
                keyPassword = signingProp("MIPLAY_RELEASE_KEY_PASSWORD", "release.keyPassword", sp)
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
            applicationId = "com.cozyting.app.acceptance"
            versionNameSuffix = "-acceptance"
            buildConfigField("boolean", "IS_ACCEPTANCE", "true")
            val acceptanceSigning = signingConfigs.getByName("acceptance")
            if (acceptanceSigning.storeFile?.exists() == true) {
                signingConfig = acceptanceSigning
            }
        }
    }

    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            versionNameSuffix = "-release"
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true && !releaseSigning.storePassword.isNullOrEmpty()) {
                signingConfig = releaseSigning
            }
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
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "cozyting.uiPreviewDir",
                System.getProperty("cozyting.uiPreviewDir")
                    ?: layout.buildDirectory.dir("ui-previews").get().asFile.absolutePath,
            )
            System.getProperty("cozyting.uiPreviewLocale")?.let { loc ->
                it.systemProperty("cozyting.uiPreviewLocale", loc)
            }
            // Robolectric Native Graphics 在 JDK 17+ 需要开放部分模块访问
            it.jvmArgs("-Xmx2g")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        // API29 兼容硬门槛：NewApi 视为 fatal；ResourceType 为既有程序化 view id，不挡构建
        fatal += "NewApi"
        enable += setOf("NewApi", "InlinedApi")
        disable += setOf(
            "ResourceType",
            "GradleDependency",
            "AndroidGradlePluginVersion",
        )
    }
}

androidComponents {
    onVariants(selector().withFlavor("channel" to "acceptance")) { variant ->
        val cfg = android.signingConfigs.getByName("acceptance")
        // AGP 8：通过 packaging/signing 由 flavor.signingConfig 提供；若 debug 覆盖则构建后 apksigner 重签
        if (cfg.storeFile?.exists() == true) {
            variant.signingConfig.setConfig(cfg)
        }
    }
}

dependencies {
    compileOnly(project(":hiddenapi-stubs"))
    implementation("androidx.core:core-ktx:1.13.1")
    // 纯 Java/Dex 放行 android.media.audiopolicy 等隐藏系统 API（无任何 native .so，保持 16KB 页对齐）
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    testImplementation("junit:junit:4.13.2")
    // 单元测试使用真实 org.json（平台 stub 会抛 Method not mocked）
    testImplementation("org.json:json:20240303")
    // JVM 本地 UI 截图预览与界面冒烟渲染（仅测试依赖，不打入正式 APK）
    testImplementation("org.robolectric:robolectric:4.14.1")
}
