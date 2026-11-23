package com.miaocast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmProbeTest {
    @Test
    fun toneA_detectedAsDominantA() {
        val pcm = PcmProbe.synthesizeToneS16LeStereo(PcmProbe.FREQ_A_HZ, frames = 4800)
        val snap = PcmProbe.analyzeInterleavedS16LeStereo(pcm)
        assertEquals(PcmProbe.Dominant.A, snap.dominant)
        assertTrue(snap.energyA > snap.energyB * 4)
        assertTrue(snap.rms > 100)
    }

    @Test
    fun toneB_detectedAsDominantB() {
        val pcm = PcmProbe.synthesizeToneS16LeStereo(PcmProbe.FREQ_B_HZ, frames = 4800)
        val snap = PcmProbe.analyzeInterleavedS16LeStereo(pcm)
        assertEquals(PcmProbe.Dominant.B, snap.dominant)
        assertTrue(snap.energyB > snap.energyA * 4)
    }

    @Test
    fun silence_cannotProveFilter() {
        val pcm = ByteArray(4800 * 4)
        val snap = PcmProbe.analyzeInterleavedS16LeStereo(pcm)
        assertEquals(PcmProbe.Dominant.SILENCE, snap.dominant)
        assertTrue(snap.verdictZh.contains("不能据此证明") || snap.verdictZh.contains("静音"))
    }

    @Test
    fun selectingA_rejectsB_signature() {
        // 模拟「只捕获 A」：输入只有 A，判决不得变成 B
        val pcm = PcmProbe.synthesizeToneS16LeStereo(PcmProbe.FREQ_A_HZ, frames = 4800)
        val snap = PcmProbe.analyzeInterleavedS16LeStereo(pcm)
        assertTrue(snap.dominant != PcmProbe.Dominant.B)
        assertEquals(PcmProbe.Dominant.A, snap.dominant)
    }
}
