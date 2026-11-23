package com.miaocast.app.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全零捕获不得假通过；超时后明确「未验证」。 */
class LocalOutputMuteUnverifiedTest {
    private class FakeVol(
        var volumeIndex: Int = 7,
        var muted: Boolean = false,
        val max: Int = 15,
    ) : LocalOutputMute.StreamMusicVolume {
        override fun getIndex() = volumeIndex
        override fun getMaxIndex() = max
        override fun isMuted() = muted
        override fun mute() { muted = true }
        override fun unmute() { muted = false }
        override fun setIndex(index: Int) { volumeIndex = index }
    }

    @Test
    fun allZeroCapture_marksUnverifiedNotActive() {
        val vol = FakeVol()
        val m = LocalOutputMute(
            vol,
            verifySilentSamples = 3,
            energyFloor = 50.0,
            maxPendingSamplesWithoutEnergy = 6,
        )
        m.beginMuteForCast()
        repeat(6) { m.onCaptureEnergy(0.0) }
        assertEquals(LocalOutputMute.Status.UNVERIFIED_NO_CAPTURE_ENERGY, m.snapshot().status)
        assertTrue(vol.muted) // 不因「未验证」而假恢复
        assertFalse(m.snapshot().messageZh.contains("已 mute；捕获探针仍有能量"))
        assertTrue(
            m.snapshot().messageZh.contains("未验证") ||
                m.snapshot().messageZh.contains("未能验证"),
        )
    }

    @Test
    fun acceptanceRestore_skipsVolumeIndexRestore() {
        val vol = FakeVol(volumeIndex = 9)
        val m = LocalOutputMute(vol, restoreVolumeIndex = false)
        m.beginMuteForCast()
        assertEquals(9, vol.volumeIndex)
        vol.volumeIndex = 0 // 模拟 mute 后索引变化
        m.restore()
        assertEquals(LocalOutputMute.Status.RESTORED, m.snapshot().status)
        // 验收：不把音量拉回 mute 前索引
        assertEquals(0, vol.volumeIndex)
    }
}
