plugins {
    // 8.7.3 可编译 API 36，但官方兼容表测试上限为 35；升级到已验证组合前保留并在交付报告列缺口
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
