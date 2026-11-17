package com.miplay.sender.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仅枚举同用户可见、可启动 App；不依赖 QUERY_ALL_PACKAGES。
 * 列表不是全部安装包。
 */
class LaunchableAppCatalogTest {
    @Test
    fun filtersToLaunchableMainLauncherOnly() {
        val apps = listOf(
            LaunchableApp(packageName = "com.a", label = "A", hasMainLauncher = true),
            LaunchableApp(packageName = "com.b", label = "B", hasMainLauncher = false),
            LaunchableApp(packageName = "com.c", label = "C", hasMainLauncher = true),
        )
        val listed = LaunchableAppCatalog.filterLaunchable(apps)
        assertEquals(listOf("com.a", "com.c"), listed.map { it.packageName })
    }

    @Test
    fun excludesSelfPackage() {
        val apps = listOf(
            LaunchableApp(packageName = "com.miplay.sender", label = "自己", hasMainLauncher = true),
            LaunchableApp(packageName = "com.other", label = "其他", hasMainLauncher = true),
        )
        val listed = LaunchableAppCatalog.filterLaunchable(apps, selfPackage = "com.miplay.sender")
        assertEquals(listOf("com.other"), listed.map { it.packageName })
    }

    @Test
    fun queriesDeclaration_doesNotUseQueryAllPackages() {
        val decl = LaunchableAppCatalog.requiredManifestQueriesActions()
        assertTrue(decl.contains("android.intent.action.MAIN"))
        assertTrue(decl.contains("android.intent.category.LAUNCHER"))
        assertFalse(LaunchableAppCatalog.requestsQueryAllPackages())
    }
}
