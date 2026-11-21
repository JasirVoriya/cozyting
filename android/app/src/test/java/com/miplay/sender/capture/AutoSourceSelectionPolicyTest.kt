package com.miplay.sender.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSourceSelectionPolicyTest {

    private val favorites = listOf(
        FavoriteAudioSource("com.example.music", autoDetectEnabled = true),
        FavoriteAudioSource("com.example.podcast", autoDetectEnabled = false),
        FavoriteAudioSource("com.example.hifi", autoDetectEnabled = true),
    )

    private fun baseInput(
        globalEnabled: Boolean = true,
        isCasting: Boolean = false,
        sourceMode: String = "app",
        currentSelected: String = "com.example.hifi",
        nowPlayingPkg: String? = "com.example.music",
        nowPlayingIsPlaying: Boolean? = true,
        favs: List<FavoriteAudioSource> = favorites,
        blockedPkg: String? = null,
    ) = AutoSourceSelectionPolicy.Input(
        globalAutoDetectEnabled = globalEnabled,
        isCasting = isCasting,
        sourceMode = sourceMode,
        currentSelectedPackage = currentSelected,
        selfPackage = "com.miplay.sender",
        nowPlayingPackage = nowPlayingPkg,
        nowPlayingIsPlaying = nowPlayingIsPlaying,
        favoriteSources = favs,
        manualOverrideBlockedPackage = blockedPkg,
    )

    @Test
    fun selectsPlayingFavoriteWithAutoDetectEnabled() {
        val d = AutoSourceSelectionPolicy.decide(baseInput())
        assertEquals(AutoSourceSelectionPolicy.Decision.AutoSelect("com.example.music"), d)
    }

    @Test
    fun skipsAppOutsideFavoritesListEvenWhenPlaying() {
        val d = AutoSourceSelectionPolicy.decide(
            baseInput(nowPlayingPkg = "com.example.video"),
        )
        assertEquals(AutoSourceSelectionPolicy.Decision.Skip("not_in_favorites"), d)
    }

    @Test
    fun skipsFavoriteWhenPerAppAutoDetectToggleIsOff() {
        val d = AutoSourceSelectionPolicy.decide(
            baseInput(nowPlayingPkg = "com.example.podcast"),
        )
        assertEquals(AutoSourceSelectionPolicy.Decision.Skip("app_auto_detect_disabled"), d)
    }

    @Test
    fun skipsWhenGlobalToggleDisabled() {
        val d = AutoSourceSelectionPolicy.decide(baseInput(globalEnabled = false))
        assertEquals(AutoSourceSelectionPolicy.Decision.Skip("global_disabled"), d)
    }

    @Test
    fun switchesToPlayingFavoriteWhileCasting() {
        val d = AutoSourceSelectionPolicy.decide(baseInput(isCasting = true, nowPlayingIsPlaying = true))
        assertEquals(AutoSourceSelectionPolicy.Decision.AutoSelect("com.example.music"), d)
    }

    @Test
    fun neverSwitchesToPausedFavoriteWhileCastingEvenIfCurrentBlank() {
        val d = AutoSourceSelectionPolicy.decide(
            baseInput(isCasting = true, currentSelected = "", nowPlayingIsPlaying = false),
        )
        assertEquals(AutoSourceSelectionPolicy.Decision.Skip("not_actively_playing"), d)
    }

    @Test
    fun skipsNonFavoriteOrDisabledAppWhileCasting() {
        assertEquals(
            AutoSourceSelectionPolicy.Decision.Skip("not_in_favorites"),
            AutoSourceSelectionPolicy.decide(baseInput(isCasting = true, nowPlayingPkg = "com.example.video")),
        )
        assertEquals(
            AutoSourceSelectionPolicy.Decision.Skip("app_auto_detect_disabled"),
            AutoSourceSelectionPolicy.decide(baseInput(isCasting = true, nowPlayingPkg = "com.example.podcast")),
        )
    }

    @Test
    fun skipsInSilentSourceMode() {
        val d = AutoSourceSelectionPolicy.decide(baseInput(sourceMode = "silent"))
        assertEquals(AutoSourceSelectionPolicy.Decision.Skip("non_app_source_mode"), d)
    }

    @Test
    fun skipsPausedSessionWhenAnotherAppAlreadySelected() {
        val d = AutoSourceSelectionPolicy.decide(
            baseInput(nowPlayingIsPlaying = false),
        )
        assertEquals(AutoSourceSelectionPolicy.Decision.Skip("not_actively_playing"), d)
    }

    @Test
    fun acceptsPausedFavoriteSessionWhenNothingSelectedYet() {
        val d = AutoSourceSelectionPolicy.decide(
            baseInput(currentSelected = "", nowPlayingIsPlaying = false),
        )
        assertEquals(AutoSourceSelectionPolicy.Decision.AutoSelect("com.example.music"), d)
    }

    @Test
    fun respectsManualOverrideUntilDifferentAppPlays() {
        val blocked = AutoSourceSelectionPolicy.decide(
            baseInput(
                currentSelected = "com.example.hifi",
                nowPlayingPkg = "com.example.music",
                blockedPkg = "com.example.music",
            ),
        )
        assertEquals(AutoSourceSelectionPolicy.Decision.Skip("manual_override_active"), blocked)

        val stillBlocked = AutoSourceSelectionPolicy.nextManualOverrideBlockedPackage(
            currentBlockedPackage = "com.example.music",
            nowPlayingPackage = "com.example.music",
        )
        assertEquals("com.example.music", stillBlocked)

        val cleared = AutoSourceSelectionPolicy.nextManualOverrideBlockedPackage(
            currentBlockedPackage = "com.example.music",
            nowPlayingPackage = "com.example.podcast",
        )
        assertNull(cleared)
    }

    @Test
    fun favoriteCatalogCategorizesAndBuildsQuickSwitchFromEditableFavorites() {
        val apps = listOf(
            LaunchableApp("com.example.music", "云音乐", true),
            LaunchableApp("com.example.podcast", "播客电台", true),
            LaunchableApp("com.example.hifi", "无损播放器", true),
            LaunchableApp("com.example.browser", "浏览器", true),
        )
        val cat = LaunchableAppCatalog.categorizeWithFavorites(
            apps = apps,
            favoriteSources = listOf(
                FavoriteAudioSource("com.example.podcast", autoDetectEnabled = false),
                FavoriteAudioSource("com.example.music", autoDetectEnabled = true),
            ),
            selfPackage = "com.miplay.sender",
        )
        assertEquals(listOf("com.example.podcast", "com.example.music"), cat.favorites.map { it.first.packageName })
        assertEquals(listOf(false, true), cat.favorites.map { it.second.autoDetectEnabled })
        assertEquals(listOf("com.example.hifi", "com.example.browser"), cat.others.map { it.packageName })

        // 当前选中已在常用名单内：严格保留用户自定义排序，不被强行置顶打乱
        val quick = LaunchableAppCatalog.quickSwitchCandidatesFromFavorites(
            apps = apps,
            favoriteSources = listOf(
                FavoriteAudioSource("com.example.podcast", autoDetectEnabled = false),
                FavoriteAudioSource("com.example.music", autoDetectEnabled = true),
            ),
            currentPackage = "com.example.music",
            limit = 4,
            selfPackage = "com.miplay.sender",
        )
        assertEquals(listOf("com.example.podcast", "com.example.music"), quick.map { it.packageName })

        // 当前选中不在常用名单内（临时点了其他应用）：置前提示，其后按常用排序
        val quickWithNonFavoriteCurrent = LaunchableAppCatalog.quickSwitchCandidatesFromFavorites(
            apps = apps,
            favoriteSources = listOf(
                FavoriteAudioSource("com.example.podcast", autoDetectEnabled = false),
                FavoriteAudioSource("com.example.music", autoDetectEnabled = true),
            ),
            currentPackage = "com.example.browser",
            limit = 4,
            selfPackage = "com.miplay.sender",
        )
        assertEquals(
            listOf("com.example.browser", "com.example.podcast", "com.example.music"),
            quickWithNonFavoriteCurrent.map { it.packageName },
        )
    }

    @Test
    fun reorderFavoriteSourcesMovesItemPreservingAutoDetectAndMissingPackages() {
        val initial = listOf(
            FavoriteAudioSource("com.example.a", autoDetectEnabled = true),
            FavoriteAudioSource("com.example.b", autoDetectEnabled = false),
            FavoriteAudioSource("com.example.c", autoDetectEnabled = true),
            FavoriteAudioSource("com.example.uninstalled", autoDetectEnabled = false),
        )
        // 把 c 拖到 a 位置（向上拖）
        val movedUp = LaunchableAppCatalog.reorderFavoriteSources(initial, "com.example.c", "com.example.a")
        assertEquals(
            listOf("com.example.c", "com.example.a", "com.example.b", "com.example.uninstalled"),
            movedUp.map { it.packageName },
        )
        assertEquals(listOf(true, true, false, false), movedUp.map { it.autoDetectEnabled })

        // 把 c 拖回 b 位置（向下拖）
        val movedDown = LaunchableAppCatalog.reorderFavoriteSources(movedUp, "com.example.c", "com.example.b")
        assertEquals(
            listOf("com.example.a", "com.example.b", "com.example.c", "com.example.uninstalled"),
            movedDown.map { it.packageName },
        )
        assertEquals(initial, movedDown)

        // 不存在或相同包名时原样返回
        assertEquals(initial, LaunchableAppCatalog.reorderFavoriteSources(initial, "com.example.a", "com.example.a"))
        assertEquals(initial, LaunchableAppCatalog.reorderFavoriteSources(initial, "com.example.missing", "com.example.a"))
    }
}
