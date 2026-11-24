package com.cozyting.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约：本地探针执行计划永不要求 MiPlay create/connect/discover；
 * 且无音箱时仍 Allow（在已选目标 App 前提下）。
 */
class LocalProbeNoMiPlayContractTest {
    @Test
    fun plan_neverTouchesMiPlayStack() {
        val plan = LocalFilterAcceptanceGate.executionPlan(LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE)
        assertFalse(plan.callMiPlayConnect)
        assertFalse(plan.callMiPlayCreateSession)
        assertFalse(plan.callMiPlayDiscover)
        assertFalse(plan.sendMediaToSpeakers)
        assertTrue(plan.runPcmProbeOnly)
        assertTrue(plan.runUidFilteredPlaybackCapture)
    }

    @Test
    fun start_withoutSpeakers_isNotBlockedBySpeakerGate() {
        val r = LocalFilterAcceptanceGate.validateStart(
            LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE,
            targetPackageSelected = true,
            speakerCount = 0,
        )
        assertTrue(r is LocalFilterAcceptanceGate.StartDecision.Allow)
        // 对照：投送模式同条件必须因无音箱被阻断
        val cast = LocalFilterAcceptanceGate.validateStart(
            LocalFilterAcceptanceGate.Mode.MIPLAY_CAST,
            targetPackageSelected = true,
            speakerCount = 0,
        )
        assertTrue(cast is LocalFilterAcceptanceGate.StartDecision.Block)
    }
}
