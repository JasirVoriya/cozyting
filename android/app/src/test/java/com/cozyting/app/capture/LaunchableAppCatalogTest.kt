package com.cozyting.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仅枚举同用户可见、可启动 App；不依赖 QUERY_ALL_PACKAGES。
 * 支持最近使用置顶与常用音乐应用白名单分类。
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
            LaunchableApp(packageName = "com.cozyting.app", label = "自己", hasMainLauncher = true),
            LaunchableApp(packageName = "com.other", label = "其他", hasMainLauncher = true),
        )
        val listed = LaunchableAppCatalog.filterLaunchable(apps, selfPackage = "com.cozyting.app")
        assertEquals(listOf("com.other"), listed.map { it.packageName })
    }

    @Test
    fun queriesDeclaration_doesNotUseQueryAllPackages() {
        val decl = LaunchableAppCatalog.requiredManifestQueriesActions()
        assertTrue(decl.contains("android.intent.action.MAIN"))
        assertTrue(decl.contains("android.intent.category.LAUNCHER"))
        assertFalse(LaunchableAppCatalog.requestsQueryAllPackages())
    }

    @Test
    fun categorize_separatesRecentRecommendedMusicAndOthers() {
        val apps = listOf(
            LaunchableApp(packageName = "com.tencent.mm", label = "微信", hasMainLauncher = true),
            LaunchableApp(packageName = "com.netease.cloudmusic", label = "网易云音乐", hasMainLauncher = true),
            LaunchableApp(packageName = "com.tencent.qqmusic", label = "QQ音乐", hasMainLauncher = true),
            LaunchableApp(packageName = "com.huawei.music", label = "华为音乐", hasMainLauncher = true),
        )
        val cat = LaunchableAppCatalog.categorize(
            apps = apps,
            recentPackages = listOf("com.tencent.qqmusic"),
        )
        assertEquals(listOf("com.tencent.qqmusic"), cat.recent.map { it.packageName })
        assertEquals(
            listOf("com.huawei.music", "com.netease.cloudmusic"),
            cat.recommendedMusic.map { it.packageName },
        )
        assertEquals(listOf("com.tencent.mm"), cat.others.map { it.packageName })
    }

    @Test
    fun quickSwitchCandidates_prioritizesCurrentThenRecentThenRecommended() {
        val apps = listOf(
            LaunchableApp(packageName = "com.netease.cloudmusic", label = "网易云音乐", hasMainLauncher = true),
            LaunchableApp(packageName = "com.tencent.qqmusic", label = "QQ音乐", hasMainLauncher = true),
            LaunchableApp(packageName = "com.huawei.music", label = "华为音乐", hasMainLauncher = true),
            LaunchableApp(packageName = "com.luna.music", label = "汽水音乐", hasMainLauncher = true),
        )
        val quick = LaunchableAppCatalog.quickSwitchCandidates(
            apps = apps,
            recentPackages = listOf("com.tencent.qqmusic", "com.netease.cloudmusic"),
            currentPackage = "com.huawei.music",
            limit = 3,
        )
        assertEquals(
            listOf("com.huawei.music", "com.tencent.qqmusic", "com.netease.cloudmusic"),
            quick.map { it.packageName },
        )
    }
}
