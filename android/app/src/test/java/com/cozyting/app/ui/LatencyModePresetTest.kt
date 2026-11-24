package com.cozyting.app.ui

import com.cozyting.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyModePresetTest {
    @Test
    fun resolvesAllFourBuiltInPresetsDeterministically() {
        assertEquals(LatencyModeSelection.Preset(LatencyModePreset.STANDARD), LatencyModePreset.resolve(0))
        assertEquals(LatencyModeSelection.Preset(LatencyModePreset.STANDARD), LatencyModePreset.resolve(-1))
        assertEquals(LatencyModeSelection.Preset(LatencyModePreset.BALANCED), LatencyModePreset.resolve(400))
        assertEquals(LatencyModeSelection.Preset(LatencyModePreset.LOW), LatencyModePreset.resolve(250))
        assertEquals(LatencyModeSelection.Preset(LatencyModePreset.ULTRA), LatencyModePreset.resolve(150))

        assertEquals("标准", LatencyModePreset.resolve(0).capsuleLabelZh)
        assertEquals("均衡", LatencyModePreset.resolve(400).capsuleLabelZh)
        assertEquals("低延迟", LatencyModePreset.resolve(250).capsuleLabelZh)
        assertEquals("极速", LatencyModePreset.resolve(150).capsuleLabelZh)
    }

    @Test
    fun nonPresetDelayResolvesToCustomWithCompactLabelAndThresholdTone() {
        val mid = LatencyModePreset.resolve(320)
        assertTrue(mid is LatencyModeSelection.Custom)
        assertEquals("320ms", mid.capsuleLabelZh)
        assertEquals(LatencyModeTone.ACCENT, mid.tone)
        assertEquals(R.drawable.ic_ui_latency_custom, mid.iconRes)

        val aggressive = LatencyModePreset.resolve(180)
        assertTrue(aggressive is LatencyModeSelection.Custom)
        assertEquals("180ms", aggressive.capsuleLabelZh)
        assertEquals(LatencyModeTone.WARNING, aggressive.tone)
        assertEquals(R.drawable.ic_ui_latency_custom, aggressive.iconRes)
    }
}
