package com.miaocast.app.capture

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

    data class FavoriteCategorizedApps(
        val favorites: List<Pair<LaunchableApp, FavoriteAudioSource>>,
        val others: List<LaunchableApp>,
    )

    fun defaultFavoriteSources(
        apps: List<LaunchableApp>,
        selfPackage: String? = null,
    ): List<FavoriteAudioSource> =
        filterLaunchable(apps, selfPackage = selfPackage)
            .filter { isRecommendedMusicApp(it) }
            .map { FavoriteAudioSource(packageName = it.packageName, autoDetectEnabled = true) }

    fun categorizeWithFavorites(
        apps: List<LaunchableApp>,
        favoriteSources: List<FavoriteAudioSource>,
        selfPackage: String? = null,
    ): FavoriteCategorizedApps {
        val valid = filterLaunchable(apps, selfPackage = selfPackage)
        val byPkg = valid.associateBy { it.packageName }
        val favorites = mutableListOf<Pair<LaunchableApp, FavoriteAudioSource>>()
        val favSet = mutableSetOf<String>()
        favoriteSources.forEach { fav ->
            val app = byPkg[fav.packageName]
            if (app != null && favSet.add(fav.packageName)) {
                favorites += app to fav
            }
        }
        val others = valid.filter { it.packageName !in favSet }
        return FavoriteCategorizedApps(favorites = favorites, others = others)
    }

    /**
     * 将常用音源名单中的 [fromPackage] 移动到 [toPackage] 所在位置，保留其余条目与各自的自动识别开关状态。
     * 若任一包名不存在或相同则原样返回。
     */
    fun reorderFavoriteSources(
        sources: List<FavoriteAudioSource>,
        fromPackage: String,
        toPackage: String,
    ): List<FavoriteAudioSource> {
        val from = fromPackage.trim()
        val to = toPackage.trim()
        if (from.isEmpty() || to.isEmpty() || from == to) return sources
        val fromIdx = sources.indexOfFirst { it.packageName == from }
        val toIdx = sources.indexOfFirst { it.packageName == to }
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return sources
        val updated = sources.toMutableList()
        val moved = updated.removeAt(fromIdx)
        updated.add(toIdx, moved)
        return updated
    }

    fun quickSwitchCandidatesFromFavorites(
        apps: List<LaunchableApp>,
        favoriteSources: List<FavoriteAudioSource>,
        currentPackage: String? = null,
        limit: Int = 4,
        selfPackage: String? = null,
    ): List<LaunchableApp> {
        val cap = limit.coerceAtLeast(0)
        if (cap == 0) return emptyList()
        val valid = filterLaunchable(apps, selfPackage = selfPackage)
        val byPkg = valid.associateBy { it.packageName }
        val favApps = mutableListOf<LaunchableApp>()
        val favPkgSet = mutableSetOf<String>()
        for (fav in favoriteSources) {
            val app = byPkg[fav.packageName] ?: continue
            if (favPkgSet.add(app.packageName)) {
                favApps += app
            }
        }
        val result = mutableListOf<LaunchableApp>()
        // 若当前选中音源不在常用名单内（例如临时点了其他应用），置前提示；
        // 若已在常用名单内，则严格保留用户拖动排好的常用音源顺序。
        if (!currentPackage.isNullOrBlank() && currentPackage !in favPkgSet) {
            byPkg[currentPackage]?.let { result += it }
        }
        for (app in favApps) {
            if (result.size >= cap) break
            result += app
        }
        return result.take(cap)
    }

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
