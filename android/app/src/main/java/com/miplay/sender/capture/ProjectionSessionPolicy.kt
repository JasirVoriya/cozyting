package com.miplay.sender.capture

/**
 * MediaProjection 会话策略（与 Android 14+ 启动限制对齐的可测规则）：
 * 用户授权 → mediaProjection FGS → getMediaProjection → UID 过滤捕获。
 * 不采集麦克风；RECORD_AUDIO 仅用于播放捕获。
 */
class ProjectionSessionPolicy {
    private val consumedGrants = mutableSetOf<String>()
    private val active = mutableMapOf<String, Boolean>()

    enum class StartStep {
        USER_GRANT,
        START_MEDIA_PROJECTION_FGS,
        GET_MEDIA_PROJECTION,
        BUILD_UID_FILTERED_CAPTURE,
    }

    enum class CleanupItem {
        STOP_AUDIO_RECORD,
        RELEASE_ENCODER,
        STOP_MEDIA_PROJECTION,
        RELEASE_WAKE_AND_WIFI_LOCKS,
        STOP_NATIVE_SESSION,
        CLEAR_GRANT_TOKEN,
    }

    enum class FgsType { MEDIA_PROJECTION, MEDIA_PLAYBACK, MICROPHONE }

    enum class RejectReason { REUSED_GRANT, MISSING_GRANT }

    sealed class Decision {
        data class ProceedToFgsThenGetProjection(val sessionId: String) : Decision()
        data class StopAndReauthorize(val messageZh: String) : Decision()
        data class Reject(val reason: RejectReason, val messageZh: String) : Decision()
    }

    fun beginAfterUserGrant(
        sessionId: String,
        resultCode: Int,
        reusedGrantToken: String? = null,
    ): Decision {
        if (reusedGrantToken != null) {
            return Decision.Reject(
                RejectReason.REUSED_GRANT,
                "MediaProjection 授权不可跨会话复用，请重新授权。",
            )
        }
        if (resultCode == 0) {
            return Decision.Reject(RejectReason.MISSING_GRANT, "用户未授予投屏/音频捕获授权。")
        }
        consumedGrants += sessionId
        active[sessionId] = true
        return Decision.ProceedToFgsThenGetProjection(sessionId)
    }

    fun onTargetPackageChanged(activeSessionId: String?, newPackage: String): Decision {
        if (activeSessionId != null) {
            active.remove(activeSessionId)
            consumedGrants.remove(activeSessionId)
        }
        return Decision.StopAndReauthorize(
            "已切换投送目标为 $newPackage。旧会话已停止，请重新授权后再开始。",
        )
    }

    fun hasReusableGrant(sessionId: String): Boolean = false.also {
        // 策略：永不复用；即使内部记录过也对外返回 false
        active.remove(sessionId)
        consumedGrants.remove(sessionId)
    }

    companion object {
        fun requiredStartOrder(): List<StartStep> = listOf(
            StartStep.USER_GRANT,
            StartStep.START_MEDIA_PROJECTION_FGS,
            StartStep.GET_MEDIA_PROJECTION,
            StartStep.BUILD_UID_FILTERED_CAPTURE,
        )

        fun cleanupChecklist(): Set<CleanupItem> = CleanupItem.entries.toSet()

        fun fgsTypeForAppCapture(): FgsType = FgsType.MEDIA_PROJECTION

        fun capturesMicrophone(): Boolean = false

        fun requiresMicrophoneFgs(): Boolean = false

        fun recordAudioPurposeZh(): String =
            "RECORD_AUDIO 仅用于系统播放捕获（AudioPlaybackCapture）以投送到音箱，不采集麦克风。"
    }
}
