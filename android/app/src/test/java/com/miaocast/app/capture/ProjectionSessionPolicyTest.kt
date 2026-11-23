package com.miaocast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaProjection：每次会话新授权；先用户授权再 FGS 再 getMediaProjection；
 * 撤销/终止干净清理；切目标停止旧会话并重新授权。不采集麦克风。
 */
class ProjectionSessionPolicyTest {

    @Test
    fun cannotReusePreviousProjectionIntent() {
        val policy = ProjectionSessionPolicy()
        val first = policy.beginAfterUserGrant(sessionId = "s1", resultCode = -1)
        assertTrue(first is ProjectionSessionPolicy.Decision.ProceedToFgsThenGetProjection)
        // 同一授权令牌不得跨会话复用
        val reused = policy.beginAfterUserGrant(sessionId = "s2", resultCode = -1, reusedGrantToken = "s1")
        assertTrue(reused is ProjectionSessionPolicy.Decision.Reject)
        assertEquals(
            ProjectionSessionPolicy.RejectReason.REUSED_GRANT,
            (reused as ProjectionSessionPolicy.Decision.Reject).reason,
        )
    }

    @Test
    fun order_isUserGrant_thenFgs_thenGetMediaProjection() {
        val steps = ProjectionSessionPolicy.requiredStartOrder()
        assertEquals(
            listOf(
                ProjectionSessionPolicy.StartStep.USER_GRANT,
                ProjectionSessionPolicy.StartStep.START_MEDIA_PROJECTION_FGS,
                ProjectionSessionPolicy.StartStep.GET_MEDIA_PROJECTION,
                ProjectionSessionPolicy.StartStep.BUILD_UID_FILTERED_CAPTURE,
            ),
            steps,
        )
    }

    @Test
    fun revokeOrStop_requiresFullCleanup() {
        val cleanup = ProjectionSessionPolicy.cleanupChecklist()
        assertTrue(cleanup.contains(ProjectionSessionPolicy.CleanupItem.STOP_AUDIO_RECORD))
        assertTrue(cleanup.contains(ProjectionSessionPolicy.CleanupItem.RELEASE_ENCODER))
        assertTrue(cleanup.contains(ProjectionSessionPolicy.CleanupItem.STOP_MEDIA_PROJECTION))
        assertTrue(cleanup.contains(ProjectionSessionPolicy.CleanupItem.RELEASE_WAKE_AND_WIFI_LOCKS))
        assertTrue(cleanup.contains(ProjectionSessionPolicy.CleanupItem.STOP_NATIVE_SESSION))
        assertTrue(cleanup.contains(ProjectionSessionPolicy.CleanupItem.CLEAR_GRANT_TOKEN))
    }

    @Test
    fun changingTarget_requiresStopAndFreshGrant() {
        val policy = ProjectionSessionPolicy()
        policy.beginAfterUserGrant(sessionId = "s1", resultCode = -1)
        val change = policy.onTargetPackageChanged(activeSessionId = "s1", newPackage = "com.example.b")
        assertTrue(change is ProjectionSessionPolicy.Decision.StopAndReauthorize)
        assertFalse(policy.hasReusableGrant("s1"))
    }

    @Test
    fun fgsType_isMediaProjection_notMicrophone() {
        assertEquals(
            ProjectionSessionPolicy.FgsType.MEDIA_PROJECTION,
            ProjectionSessionPolicy.fgsTypeForAppCapture(),
        )
        assertFalse(ProjectionSessionPolicy.capturesMicrophone())
        assertFalse(ProjectionSessionPolicy.requiresMicrophoneFgs())
    }

    @Test
    fun recordAudioPermission_isForPlaybackCaptureOnly() {
        val note = ProjectionSessionPolicy.recordAudioPurposeZh()
        assertTrue(note.contains("播放") || note.contains("投送") || note.contains("捕获"))
        assertFalse(note.contains("麦克风采集"))
    }
}
