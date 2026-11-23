package com.miaocast.app.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingInfoTest {
    @Test
    fun placeholdersAreHonestNotFakeSongNames() {
        val u = NowPlayingInfo.unavailable("未取得正在播放")
        assertFalse(u.available)
        assertEquals("未知标题", NowPlayingInfo.displayTitle(u))
        assertTrue(NowPlayingInfo.displayArtist(u).contains("未取得"))
        // 禁止把占位伪装成具体曲名
        assertFalse(NowPlayingInfo.displayTitle(u).contains("Deadman"))
    }

    @Test
    fun formatTimeUnknownIsDash() {
        assertEquals("—:—", NowPlayingInfo.formatTime(null))
        assertEquals("01:23", NowPlayingInfo.formatTime(83_000))
        assertEquals("03:55", NowPlayingInfo.formatTime(235_000))
    }
}
