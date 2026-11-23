package com.miplay.sender.capture.shell

import com.miplay.sender.capture.shell.WirelessDebugOnboardingState.StepId
import com.miplay.sender.capture.shell.WirelessDebugOnboardingState.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessDebugOnboardingStateTest {
    @Test
    fun api29ReportsUnsupportedWithNoSteps() {
        val state = WirelessDebugOnboardingState.evaluate(
            sdkInt = 29,
            notificationsGranted = true,
            overlayGranted = true,
            developerOptionsEnabled = true,
            paired = false,
            connectionVerified = false,
        )
        assertFalse(state.supported)
        assertTrue(state.steps.isEmpty())
        assertNull(state.activeStep)
    }

    @Test
    fun progressesThroughAllFiveStepsDeterministically() {
        val s1 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = false,
            overlayGranted = false,
            developerOptionsEnabled = false,
            paired = false,
            connectionVerified = false,
        )
        assertTrue(s1.supported)
        assertEquals(5, s1.steps.size)
        assertEquals(StepId.NOTIFICATION, s1.activeStep)
        assertEquals(0, s1.completedCount)
        assertEquals(StepStatus.ACTIVE, s1.steps[0].status)
        assertEquals(StepStatus.LOCKED, s1.steps[1].status)

        val s2 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            overlayGranted = false,
            developerOptionsEnabled = false,
            paired = false,
            connectionVerified = false,
        )
        assertEquals(StepId.OVERLAY_PERMISSION, s2.activeStep)
        assertEquals(1, s2.completedCount)
        assertEquals(StepStatus.DONE, s2.steps[0].status)
        assertEquals(StepStatus.ACTIVE, s2.steps[1].status)
        assertEquals("去开启悬浮窗权限", s2.steps[1].primaryActionLabelZh)

        val s3 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            overlayGranted = true,
            developerOptionsEnabled = false,
            paired = false,
            connectionVerified = false,
        )
        assertEquals(StepId.DEVELOPER_OPTIONS, s3.activeStep)
        assertEquals(2, s3.completedCount)
        assertEquals(StepStatus.DONE, s3.steps[1].status)
        assertEquals(StepStatus.ACTIVE, s3.steps[2].status)

        val s4 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            overlayGranted = true,
            developerOptionsEnabled = true,
            paired = false,
            connectionVerified = false,
        )
        assertEquals(StepId.WIRELESS_PAIRING, s4.activeStep)
        assertEquals(3, s4.completedCount)
        assertEquals("启动浮动框并前往设置", s4.steps[3].primaryActionLabelZh)

        val s5 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            overlayGranted = true,
            developerOptionsEnabled = true,
            paired = true,
            connectionVerified = false,
        )
        assertEquals(StepId.VERIFY_CONNECTION, s5.activeStep)
        assertEquals(4, s5.completedCount)
        assertEquals("测试连接", s5.steps[4].primaryActionLabelZh)

        val done = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            overlayGranted = true,
            developerOptionsEnabled = true,
            paired = true,
            connectionVerified = true,
            connectedPort = 39812,
        )
        assertNull(done.activeStep)
        assertEquals(5, done.completedCount)
        assertTrue(done.steps.all { it.status == StepStatus.DONE })
        assertTrue(done.steps[4].detailZh.contains("39812"))
    }
}
