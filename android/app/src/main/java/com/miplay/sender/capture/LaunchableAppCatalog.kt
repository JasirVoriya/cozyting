package com.miplay.sender.capture

/** 可启动 App 列表规则：MAIN/LAUNCHER，不申请 QUERY_ALL_PACKAGES，内置主流音乐/音频应用白名单与最近使用分组。 */
object LaunchableAppCatalog {
    val KNOWN_MUSIC_PACKAGES: Set<String> = setOf(
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
        "com.kugou.android",
        "cn.kuwo.player",
        "com.luna.music",
        "com.huawei.music",
        "com.android.mediacenter",
        "com.miui.player",
        "com.apple.android.music",
        "com.spotify.music",
        "cn.wenyu.bodian",
        "cmccwm.mobilemusic",
        "com.salt.music",
        "io.github.moriafly.saltplayer",
        "com.ximalaya.ting.android",
        "com.ruguoapp.jike.podcast",
        "fm.qingting.qtradior",
        "com.dragon.read",
        "tv.danmaku.bili",
        "com.danmaku.bili",
        "com.google.android.apps.youtube.music",
        "com.maxmpz.audioplayer",
        "org.videolan.vlc",
        "com.foobar2000.foobar2000",
    )

    private val MUSIC_LABEL_KEYWORDS: List<String> = listOf(
        "音乐",
        "music",
        "播客",
        "podcast",
        "电台",
        "听书",
        "畅听",
        "喜马拉雅",
        "小宇宙",
        "spotify",
        "player",
    )

    data class CategorizedApps(
        val recent: List<LaunchableApp>,
        val recommendedMusic: List<LaunchableApp>,
        val others: List<LaunchableApp>,
    )

    fun filterLaunchable(
        apps: List<LaunchableApp>,
        selfPackage: String? = null,
    ): List<LaunchableApp> =
        apps.filter { it.hasMainLauncher && (selfPackage == null || it.packageName != selfPackage) }
            .sortedBy { it.label.lowercase() }

    fun isRecommendedMusicApp(app: LaunchableApp): Boolean {
        if (app.packageName in KNOWN_MUSIC_PACKAGES) return true
        val lowerLabel = app.label.lowercase()
        return MUSIC_LABEL_KEYWORDS.any { kw -> lowerLabel.contains(kw) }
    }

    fun categorize(
        apps: List<LaunchableApp>,
        recentPackages: List<String> = emptyList(),
        selfPackage: String? = null,
    ): CategorizedApps {
        val valid = filterLaunchable(apps, selfPackage = selfPackage)
        val byPkg = valid.associateBy { it.packageName }
        val recent = recentPackages.distinct().mapNotNull { byPkg[it] }
        val recentSet = recent.map { it.packageName }.toSet()
        val recommendedMusic = valid.filter { it.packageName !in recentSet && isRecommendedMusicApp(it) }
        val musicSet = recommendedMusic.map { it.packageName }.toSet()
        val others = valid.filter { it.packageName !in recentSet && it.packageName !in musicSet }
        return CategorizedApps(
            recent = recent,
            recommendedMusic = recommendedMusic,
            others = others,
        )
    }

    fun quickSwitchCandidates(
        apps: List<LaunchableApp>,
        recentPackages: List<String> = emptyList(),
        currentPackage: String? = null,
        limit: Int = 4,
        selfPackage: String? = null,
    ): List<LaunchableApp> {
        val cap = limit.coerceAtLeast(0)
        if (cap == 0) return emptyList()
        val categorized = categorize(apps, recentPackages = recentPackages, selfPackage = selfPackage)
        val validByPkg = (categorized.recent + categorized.recommendedMusic + categorized.others)
            .associateBy { it.packageName }
        val result = mutableListOf<LaunchableApp>()
        if (!currentPackage.isNullOrBlank()) {
            validByPkg[currentPackage]?.let { result += it }
        }
        for (app in categorized.recent + categorized.recommendedMusic) {
            if (result.size >= cap) break
            if (result.none { it.packageName == app.packageName }) {
                result += app
            }
        }
        return result.take(cap)
    }

    fun requiredManifestQueriesActions(): List<String> =
        listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER")

    fun requestsQueryAllPackages(): Boolean = false
}
