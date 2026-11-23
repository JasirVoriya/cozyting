package com.miaocast.app.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOutputMuteTest {
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
    fun muteThenEnergyKeepsActiveVerified() {
        val vol = FakeVol()
        val m = LocalOutputMute(vol, verifySilentSamples = 3, energyFloor = 50.0)
        m.beginMuteForCast()
        assertTrue(vol.muted)
        assertEquals(LocalOutputMute.Status.PENDING_VERIFY, m.snapshot().status)
        m.onCaptureEnergy(200.0)
        assertEquals(LocalOutputMute.Status.ACTIVE_VERIFIED, m.snapshot().status)
        assertTrue(vol.muted)
    }

    @Test
    fun muteThenCaptureSilenceRestoresAndMarksUnavailable() {
        val vol = FakeVol(volumeIndex = 9)
        val m = LocalOutputMute(vol, verifySilentSamples = 3, energyFloor = 50.0)
        m.beginMuteForCast()
        m.onCaptureEnergy(300.0) // 基线有声
        assertEquals(LocalOutputMute.Status.ACTIVE_VERIFIED, m.snapshot().status)
        repeat(3) { m.onCaptureEnergy(1.0) }
        assertEquals(LocalOutputMute.Status.UNAVAILABLE_CAPTURE_SILENCED, m.snapshot().status)
        assertTrue(!vol.muted)
        assertEquals(9, vol.volumeIndex)
        assertTrue(m.snapshot().messageZh.contains("回退"))
    }

    @Test
    fun noBaselineDoesNotFalsePositiveUnavailable() {
        val vol = FakeVol()
        val m = LocalOutputMute(
            vol,
            verifySilentSamples = 2,
            energyFloor = 50.0,
            maxPendingSamplesWithoutEnergy = 100,
        )
        m.beginMuteForCast()
        repeat(5) { m.onCaptureEnergy(0.0) }
        assertEquals(LocalOutputMute.Status.PENDING_VERIFY, m.snapshot().status)
        assertTrue(vol.muted)
    }

    @Test
    fun restoreUnmutes() {
        val vol = FakeVol(volumeIndex = 4)
        val m = LocalOutputMute(vol)
        m.beginMuteForCast()
        m.restore()
        assertEquals(LocalOutputMute.Status.RESTORED, m.snapshot().status)
        assertTrue(!vol.muted)
        assertEquals(4, vol.volumeIndex)
    }

    @Test
    fun apiStringDocumentsAdjustStreamVolume() {
        val m = LocalOutputMute(FakeVol())
        assertTrue(m.snapshot().api.contains("ADJUST_MUTE"))
        assertTrue(m.snapshot().api.contains("STREAM_MUSIC"))
    }

    @Test
    fun trackChangeSilenceAfterLockedVerify_keepsStreamMutedAndActiveVerified() {
        val vol = FakeVol(volumeIndex = 8)
        val m = LocalOutputMute(
            vol,
            verifySilentSamples = 3,
            energyFloor = 50.0,
            verifyAudibleSamples = 4,
        )
        m.beginMuteForCast()
        repeat(4) { m.onCaptureEnergy(240.0) }
        assertTrue(m.snapshot().probeLocked)
        assertEquals(LocalOutputMute.Status.ACTIVE_VERIFIED, m.snapshot().status)

        // 模拟切歌间隙连续 8 拍静音：不应误回退 UNMUTE
        repeat(8) { m.onCaptureEnergy(0.0) }
        assertEquals(LocalOutputMute.Status.ACTIVE_VERIFIED, m.snapshot().status)
        assertTrue(vol.muted)
    }

    @Test
    fun externalUnmuteDuringActiveCast_reportsExternalUnmutedWithoutForceRemuteLoop() {
        val vol = FakeVol(volumeIndex = 8)
        val m = LocalOutputMute(vol, verifySilentSamples = 3, energyFloor = 50.0, verifyAudibleSamples = 2)
        m.beginMuteForCast()
        repeat(2) { m.onCaptureEnergy(200.0) }
        assertEquals(LocalOutputMute.Status.ACTIVE_VERIFIED, m.snapshot().status)

        // 模拟系统/用户按键解除静音：状态应诚实反映 EXTERNAL_UNMUTED，且绝不循环调用 mute()
        vol.muted = false
        val snap = m.onCaptureEnergy(200.0)
        assertEquals(LocalOutputMute.Status.EXTERNAL_UNMUTED, snap.status)
        assertTrue(!vol.muted)
        assertTrue(!snap.streamMuted)
    }

    @Test
    fun keepLocalOutputPolicy_skipsStreamMusicMuteWithHonestPlatformBoundary() {
        val vol = FakeVol(volumeIndex = 6)
        val m = LocalOutputMute(vol)
        val snap = m.beginMuteForCast(LocalOutputMute.Policy.KEEP_LOCAL_OUTPUT)
        assertEquals(LocalOutputMute.Status.SKIPPED, snap.status)
        assertTrue(!vol.muted)
        assertTrue(snap.messageZh.contains("STREAM_MUSIC"))
    }
}
