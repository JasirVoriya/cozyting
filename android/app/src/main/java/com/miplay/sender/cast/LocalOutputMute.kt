package com.miplay.sender.cast

/**
 * 投送到音箱时尝试关闭本机扬声器/耳机的 STREAM_MUSIC 出声，同时尽量保住 AudioPlaybackCapture。
 *
 * ## 选用 API
 * - `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0)` / `ADJUST_UNMUTE`
 * - 恢复时若仍静音则 UNMUTE；并尽量 `setStreamVolume` 回到 mute 前音量索引
 *
 * ## 为何不用「直接 mute 就宣称成功」
 * AOSP 上 STREAM_MUSIC mute/volume 会进入 AudioFlinger 的 stream 衰减路径；
 * AudioPlaybackCapture 混音是否在衰减之前取流因机型/厂商而异。
 * **没有捕获能量探针证据时，不得声称「本机已静音且捕获仍有音频」。**
 *
 * ## 失败回退
 * mute 后若捕获 RMS/peak 从「有声」跌到「静音阈值以下」并持续 [verifySilentSamples] 次，
 * 立即 UNMUTE 并标记 [Status.UNAVAILABLE_CAPTURE_SILENCED]，UI 提示本机仍出声。
 *
 * 不做：重启音箱、改系统路由权限、提权、MODIFY_AUDIO_ROUTING 私有 API。
 */
class LocalOutputMute(
    private val volume: StreamMusicVolume,
    private val verifySilentSamples: Int = 4,
    private val energyFloor: Double = 80.0,
) {
    enum class Status {
        IDLE,
        /** 已 mute，等待捕获探针确认。 */
        PENDING_VERIFY,
        /** 探针确认：本机 mute 后捕获仍有能量。 */
        ACTIVE_VERIFIED,
        /** mute 导致捕获变静，已回退。 */
        UNAVAILABLE_CAPTURE_SILENCED,
        /** 已恢复本机音量（停止投送）。 */
        RESTORED,
        /** 静音测试源等场景跳过。 */
        SKIPPED,
    }

    data class Snapshot(
        val status: Status,
        val api: String,
        val savedIndex: Int?,
        val messageZh: String,
    )

    interface StreamMusicVolume {
        fun getIndex(): Int
        fun getMaxIndex(): Int
        fun isMuted(): Boolean
        fun mute()
        fun unmute()
        fun setIndex(index: Int)
    }

    private var status: Status = Status.IDLE
    private var savedIndex: Int? = null
    private var hadEnergyBeforeOrDuring: Boolean = false
    private var silentStreak: Int = 0

    fun snapshot(): Snapshot = Snapshot(
        status = status,
        api = "AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE|UNMUTE, 0)",
        savedIndex = savedIndex,
        messageZh = when (status) {
            Status.IDLE -> "本机出声（未尝试静音）"
            Status.PENDING_VERIFY -> "已请求 STREAM_MUSIC mute，等待捕获探针…"
            Status.ACTIVE_VERIFIED -> "本机 STREAM_MUSIC 已 mute；捕获探针仍有能量（估计）"
            Status.UNAVAILABLE_CAPTURE_SILENCED -> "本机静音会连带静音捕获，已回退；本机仍出声"
            Status.RESTORED -> "已恢复本机 STREAM_MUSIC"
            Status.SKIPPED -> "已跳过本机静音（非 App 捕获源）"
        },
    )

    fun skip(reason: String = "non-app source"): Snapshot {
        status = Status.SKIPPED
        return snapshot().copy(messageZh = "已跳过本机静音：$reason")
    }

    /** 开始投送后调用：mute 并进入探针窗口。 */
    fun beginMuteForCast(): Snapshot {
        if (status == Status.ACTIVE_VERIFIED || status == Status.PENDING_VERIFY) return snapshot()
        savedIndex = volume.getIndex()
        hadEnergyBeforeOrDuring = false
        silentStreak = 0
        runCatching {
            volume.mute()
            status = Status.PENDING_VERIFY
        }.onFailure {
            status = Status.UNAVAILABLE_CAPTURE_SILENCED
            savedIndex = null
        }
        return snapshot()
    }

    /**
     * @param captureEnergy 捕获侧 RMS 或 peak（同一尺度即可）；null = 本拍无样本，忽略。
     */
    fun onCaptureEnergy(captureEnergy: Double?): Snapshot {
        if (status != Status.PENDING_VERIFY && status != Status.ACTIVE_VERIFIED) return snapshot()
        if (captureEnergy == null) return snapshot()
        if (captureEnergy >= energyFloor) {
            hadEnergyBeforeOrDuring = true
            silentStreak = 0
            if (status == Status.PENDING_VERIFY) status = Status.ACTIVE_VERIFIED
            return snapshot()
        }
        // 能量过低
        if (!hadEnergyBeforeOrDuring) {
            // 尚无「有声」基线（源 App 可能未播放）：保持 PENDING，不误判
            return snapshot()
        }
        silentStreak++
        if (silentStreak >= verifySilentSamples) {
            restoreInternal(fromFailure = true)
        }
        return snapshot()
    }

    fun restore(): Snapshot {
        restoreInternal(fromFailure = false)
        return snapshot()
    }

    private fun restoreInternal(fromFailure: Boolean) {
        runCatching {
            if (volume.isMuted()) volume.unmute()
            val idx = savedIndex
            if (idx != null) {
                val max = volume.getMaxIndex().coerceAtLeast(0)
                volume.setIndex(idx.coerceIn(0, max))
            }
        }
        status = if (fromFailure) Status.UNAVAILABLE_CAPTURE_SILENCED else Status.RESTORED
        silentStreak = 0
    }
}
