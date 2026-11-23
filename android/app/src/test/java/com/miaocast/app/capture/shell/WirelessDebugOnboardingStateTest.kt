package com.miaocast.app.capture.shell

import com.miaocast.app.capture.shell.WirelessDebugOnboardingState.StepId
import com.miaocast.app.capture.shell.WirelessDebugOnboardingState.StepStatus
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
            developerOptionsEnabled = true,
            accessibilityEnabled = true,
            paired = false,
            connectionVerified = false,
        )
        assertFalse(state.supported)
        assertTrue(state.steps.isEmpty())
        assertNull(state.activeStep)
    }

    @Test
    fun progressesThroughAllFiveStepsWithAccessibilityHelper() {
        val s1 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = false,
            developerOptionsEnabled = false,
            accessibilityEnabled = false,
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
            developerOptionsEnabled = false,
            accessibilityEnabled = false,
            paired = false,
            connectionVerified = false,
        )
        assertEquals(StepId.DEVELOPER_OPTIONS, s2.activeStep)
        assertEquals(1, s2.completedCount)
        assertEquals(StepStatus.DONE, s2.steps[0].status)
        assertEquals(StepStatus.ACTIVE, s2.steps[1].status)

        val s3 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            developerOptionsEnabled = true,
            accessibilityEnabled = false,
            paired = false,
            connectionVerified = false,
        )
        assertEquals(StepId.ACCESSIBILITY_HELPER, s3.activeStep)
        assertEquals(2, s3.completedCount)
        assertEquals(StepStatus.DONE, s3.steps[1].status)
        assertEquals(StepStatus.ACTIVE, s3.steps[2].status)
        assertEquals("去开启无障碍（推荐）", s3.steps[2].primaryActionLabelZh)

        val s4 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            developerOptionsEnabled = true,
            accessibilityEnabled = true,
            paired = false,
            connectionVerified = false,
        )
        assertEquals(StepId.WIRELESS_PAIRING, s4.activeStep)
        assertEquals(3, s4.completedCount)
        assertEquals("启动自动配对并前往设置", s4.steps[3].primaryActionLabelZh)

        val s5 = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            developerOptionsEnabled = true,
            accessibilityEnabled = true,
            paired = true,
            connectionVerified = false,
        )
        assertEquals(StepId.VERIFY_CONNECTION, s5.activeStep)
        assertEquals(4, s5.completedCount)
        assertEquals("测试连接", s5.steps[4].primaryActionLabelZh)

        val done = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            developerOptionsEnabled = true,
            accessibilityEnabled = true,
            paired = true,
            connectionVerified = true,
            connectedPort = 39812,
        )
        assertNull(done.activeStep)
        assertEquals(5, done.completedCount)
        assertTrue(done.steps.all { it.status == StepStatus.DONE })
        assertTrue(done.steps[4].detailZh.contains("39812"))
    }

    @Test
    fun skippingAccessibilityProceedsToNotificationFallbackPairing() {
        val skipped = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            developerOptionsEnabled = true,
            accessibilityEnabled = false,
            accessibilitySkipped = true,
            paired = false,
            connectionVerified = false,
        )
        assertEquals(StepId.WIRELESS_PAIRING, skipped.activeStep)
        assertEquals(3, skipped.completedCount)
        assertEquals(StepStatus.DONE, skipped.steps[2].status)
        assertEquals("已跳过", skipped.steps[2].statusBadgeZh)
        assertEquals("前往无线调试（通知栏输入）", skipped.steps[3].primaryActionLabelZh)
        assertTrue(skipped.steps[3].detailZh.contains("长按本条通知"))
    }
}
