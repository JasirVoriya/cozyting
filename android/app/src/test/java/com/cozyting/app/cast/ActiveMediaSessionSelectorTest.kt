package com.cozyting.app.cast

import com.cozyting.app.capture.FavoriteAudioSource
import com.cozyting.app.cast.ActiveMediaSessionSelector.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMediaSessionSelectorTest {

    private val favorites = listOf(
        FavoriteAudioSource("com.example.appA", autoDetectEnabled = true),
        FavoriteAudioSource("com.example.appB", autoDetectEnabled = true),
        FavoriteAudioSource("com.example.podcast", autoDetectEnabled = false),
    )

    @Test
    fun castingAppAUnclosed_switchingToPlayingAppB_selectsAppBForDisplayAndAutoSwitch() {
        // 软件 A 未关闭（仍在活跃会话列表且排在第 0 位，但已暂停），软件 B 开始播放
        val sessions = listOf(
            SessionSnapshot("tokA", "com.example.appA", isPlaying = false, lastBecamePlayingSeq = 1L, listOrderIndex = 0),
            SessionSnapshot("tokB", "com.example.appB", isPlaying = true, lastBecamePlayingSeq = 2L, listOrderIndex = 1),
        )

        val display = ActiveMediaSessionSelector.selectDisplaySession(
            sessions = sessions,
            preferredPackage = "com.example.appA",
            isCasting = true,
            selfPackage = "com.cozyting.app",
        )
        assertEquals("com.example.appB", display?.packageName)

        val autoCandidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = sessions,
            favoriteSources = favorites,
            isCasting = true,
            currentSelectedPackage = "com.example.appA",
            selfPackage = "com.cozyting.app",
        )
        assertEquals("com.example.appB", autoCandidate?.packageName)
    }

    @Test
    fun bothAppAAndAppBPlaying_newerRisingEdgeAppBWinsOverAppA() {
        // 音频焦点切换瞬间 A 尚未上报 PAUSED，B 刚刚进入 PLAYING（seq 更高）
        val sessions = listOf(
            SessionSnapshot("tokA", "com.example.appA", isPlaying = true, lastBecamePlayingSeq = 1L, listOrderIndex = 0),
            SessionSnapshot("tokB", "com.example.appB", isPlaying = true, lastBecamePlayingSeq = 2L, listOrderIndex = 1),
        )

        val display = ActiveMediaSessionSelector.selectDisplaySession(
            sessions = sessions,
            preferredPackage = "com.example.appA",
            isCasting = true,
            selfPackage = "com.cozyting.app",
        )
        assertEquals("com.example.appB", display?.packageName)

        val autoCandidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = sessions,
            favoriteSources = favorites,
            isCasting = true,
            currentSelectedPackage = "com.example.appA",
            selfPackage = "com.cozyting.app",
        )
        assertEquals("com.example.appB", autoCandidate?.packageName)
    }

    @Test
    fun switchBackFromAppBToAppA_unclosedAppB_selectsAppA() {
        val sessions = listOf(
            SessionSnapshot("tokB", "com.example.appB", isPlaying = false, lastBecamePlayingSeq = 2L, listOrderIndex = 0),
            SessionSnapshot("tokA", "com.example.appA", isPlaying = true, lastBecamePlayingSeq = 3L, listOrderIndex = 1),
        )

        val autoCandidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = sessions,
            favoriteSources = favorites,
            isCasting = true,
            currentSelectedPackage = "com.example.appB",
            selfPackage = "com.cozyting.app",
        )
        assertEquals("com.example.appA", autoCandidate?.packageName)
    }

    @Test
    fun nonFavoriteOrDisabledAppPlayingWhileCasting_doesNotHijackAutoSwitch() {
        val sessions = listOf(
            SessionSnapshot("tokC", "com.example.video", isPlaying = true, lastBecamePlayingSeq = 5L, listOrderIndex = 0),
            SessionSnapshot("tokP", "com.example.podcast", isPlaying = true, lastBecamePlayingSeq = 4L, listOrderIndex = 1),
            SessionSnapshot("tokA", "com.example.appA", isPlaying = false, lastBecamePlayingSeq = 1L, listOrderIndex = 2),
        )

        val autoCandidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = sessions,
            favoriteSources = favorites,
            isCasting = true,
            currentSelectedPackage = "com.example.appA",
            selfPackage = "com.cozyting.app",
        )
        assertNull(autoCandidate)
    }

    @Test
    fun allSessionsPausedWhileCasting_displayStaysOnPreferredAndAutoSwitchReturnsNull() {
        val sessions = listOf(
            SessionSnapshot("tokB", "com.example.appB", isPlaying = false, lastBecamePlayingSeq = 2L, listOrderIndex = 0),
            SessionSnapshot("tokA", "com.example.appA", isPlaying = false, lastBecamePlayingSeq = 1L, listOrderIndex = 1),
        )

        val display = ActiveMediaSessionSelector.selectDisplaySession(
            sessions = sessions,
            preferredPackage = "com.example.appA",
            isCasting = true,
            selfPackage = "com.cozyting.app",
        )
        assertEquals("com.example.appA", display?.packageName)

        val autoCandidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = sessions,
            favoriteSources = favorites,
            isCasting = true,
            currentSelectedPackage = "com.example.appA",
            selfPackage = "com.cozyting.app",
        )
        assertNull(autoCandidate)
    }

    @Test
    fun alreadyPlayingOtherAppBlockedAtStart_unblocksAfterPauseOrReplay() {
        val initialSessions = listOf(
            SessionSnapshot("tokB", "com.example.appB", isPlaying = true, lastBecamePlayingSeq = 2L, listOrderIndex = 0),
            SessionSnapshot("tokA", "com.example.appA", isPlaying = true, lastBecamePlayingSeq = 1L, listOrderIndex = 1),
        )
        val blocked = mapOf("com.example.appB" to 2L)

        // 起投时 B 已在后台播放（seq=2），不被立刻抢走
        val blockedCandidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = initialSessions,
            favoriteSources = favorites,
            isCasting = true,
            currentSelectedPackage = "com.example.appA",
            selfPackage = "com.cozyting.app",
            blockedUntilNewPlaySeq = blocked,
        )
        assertEquals("com.example.appA", blockedCandidate?.packageName)

        // B 暂停后，屏蔽记录被清理
        val pausedB = listOf(
            SessionSnapshot("tokB", "com.example.appB", isPlaying = false, lastBecamePlayingSeq = 2L, listOrderIndex = 0),
            SessionSnapshot("tokA", "com.example.appA", isPlaying = true, lastBecamePlayingSeq = 1L, listOrderIndex = 1),
        )
        val pruned = ActiveMediaSessionSelector.pruneBlockedPackages(blocked, pausedB)
        assertTrue(pruned.isEmpty())

        // B 重新起播（seq=3 > 2），顺利切向 B
        val replayedB = listOf(
            SessionSnapshot("tokB", "com.example.appB", isPlaying = true, lastBecamePlayingSeq = 3L, listOrderIndex = 0),
            SessionSnapshot("tokA", "com.example.appA", isPlaying = false, lastBecamePlayingSeq = 1L, listOrderIndex = 1),
        )
        val switchedCandidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = replayedB,
            favoriteSources = favorites,
            isCasting = true,
            currentSelectedPackage = "com.example.appA",
            selfPackage = "com.cozyting.app",
            blockedUntilNewPlaySeq = blocked,
        )
        assertEquals("com.example.appB", switchedCandidate?.packageName)
    }

    @Test
    fun standbyWithNoSelection_fallsBackToPausedFavoriteSession() {
        val sessions = listOf(
            SessionSnapshot("tokC", "com.example.video", isPlaying = false, lastBecamePlayingSeq = 0L, listOrderIndex = 0),
            SessionSnapshot("tokB", "com.example.appB", isPlaying = false, lastBecamePlayingSeq = 0L, listOrderIndex = 1),
        )
        val candidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = sessions,
            favoriteSources = favorites,
            isCasting = false,
            currentSelectedPackage = "",
            selfPackage = "com.cozyting.app",
        )
        assertEquals("com.example.appB", candidate?.packageName)
    }
}
