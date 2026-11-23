package com.miaocast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 锁定：acceptance 本地音频过滤验收可不选音箱；
 * 必须已选严格目标 App；不得调用 MiPlay 连接/发现；不得扩大 UID。
 */
class LocalFilterAcceptanceGateTest {

    @Test
    fun localProbe_allowedWithoutSpeakers_whenTargetSelected() {
        val r = LocalFilterAcceptanceGate.validateStart(
            mode = LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE,
            targetPackageSelected = true,
            speakerCount = 0,
        )
        assertTrue(r is LocalFilterAcceptanceGate.StartDecision.Allow)
    }

    @Test
    fun localProbe_blockedWithoutTargetApp() {
        val r = LocalFilterAcceptanceGate.validateStart(
            mode = LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE,
            targetPackageSelected = false,
            speakerCount = 0,
        )
        assertTrue(r is LocalFilterAcceptanceGate.StartDecision.Block)
        assertEquals(
            LocalFilterAcceptanceGate.BlockReason.NO_TARGET_APP,
            (r as LocalFilterAcceptanceGate.StartDecision.Block).reason,
        )
    }

    @Test
    fun castMode_stillRequiresSpeakers() {
        val r = LocalFilterAcceptanceGate.validateStart(
            mode = LocalFilterAcceptanceGate.Mode.MIPLAY_CAST,
            targetPackageSelected = true,
            speakerCount = 0,
        )
        assertTrue(r is LocalFilterAcceptanceGate.StartDecision.Block)
        assertEquals(
            LocalFilterAcceptanceGate.BlockReason.NO_SPEAKERS,
            (r as LocalFilterAcceptanceGate.StartDecision.Block).reason,
        )
    }

    @Test
    fun localProbe_mustNotInvokeMiPlayConnectOrDiscover() {
        val plan = LocalFilterAcceptanceGate.executionPlan(LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE)
        assertFalse(plan.callMiPlayConnect)
        assertFalse(plan.callMiPlayDiscover)
        assertFalse(plan.callMiPlayCreateSession)
        assertFalse(plan.sendMediaToSpeakers)
        assertFalse(plan.playLocalMonitor)
        assertFalse(plan.recordMicrophone)
        assertFalse(plan.recordDisplaySurface)
        assertFalse(plan.persistAudioFiles)
        assertTrue(plan.runUidFilteredPlaybackCapture)
        assertTrue(plan.runPcmProbeOnly)
    }

    @Test
    fun castMode_doesInvokeMiPlayConnect() {
        val plan = LocalFilterAcceptanceGate.executionPlan(LocalFilterAcceptanceGate.Mode.MIPLAY_CAST)
        assertTrue(plan.callMiPlayConnect)
        assertTrue(plan.callMiPlayCreateSession)
    }

    @Test
    fun localProbe_forbidsWideningUidOrSystemFallback() {
        try {
            LocalFilterAcceptanceGate.forbidUidWideningOrSystemFallback(
                selectedUids = listOf(10123),
                attemptedUids = listOf(10123, 10124),
            )
            fail("must reject widened UID list")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("UID") || e.message!!.contains("扩大"))
        }
        try {
            LocalFilterAcceptanceGate.forbidUidWideningOrSystemFallback(
                selectedUids = listOf(10123),
                attemptedUids = emptyList(),
            )
            fail("empty UID is system-wide fallback")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("全系统") || e.message!!.contains("回退") || e.message!!.contains("UID"))
        }
    }

    @Test
    fun silenceVerdict_cannotProveFilter() {
        val msg = LocalFilterAcceptanceGate.silenceCannotProveFilterZh()
        assertTrue(msg.contains("不能") && msg.contains("过滤"))
    }

    @Test
    fun standardFlavor_mustNotExposeLocalProbeEntry() {
        assertFalse(LocalFilterAcceptanceGate.exposeLocalProbeEntry(isAcceptanceBuild = false))
        assertTrue(LocalFilterAcceptanceGate.exposeLocalProbeEntry(isAcceptanceBuild = true))
    }
}
