package com.miaocast.app.cast

/**
 * 投送到音箱时尝试关闭本机扬声器/耳机的 STREAM_MUSIC 出声，同时尽量保住 AudioPlaybackCapture。
 *
 * ## 选用 API
 * - `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0)` / `ADJUST_UNMUTE`
 * - 恢复时若仍静音则 UNMUTE；并按 [restoreVolumeIndex] 决定是否 `setStreamVolume` 回到 mute 前索引
 *
 * ## 为何不用「直接 mute 就宣称成功」
 * AOSP 上 STREAM_MUSIC mute/volume 会进入 AudioFlinger 的 stream 衰减路径；
 * AudioPlaybackCapture 混音是否在衰减之前取流因机型/厂商而异。
 * **没有捕获能量探针证据时，不得声称「本机已静音且捕获仍有音频」。**
 *
 * ## 失败回退 / 未验证
 * mute 后若捕获 RMS/peak 从「有声」跌到「静音阈值以下」并持续 [verifySilentSamples] 次，
 * 立即 UNMUTE 并标记 [Status.UNAVAILABLE_CAPTURE_SILENCED]。
 * 若始终全零且达到 [maxPendingSamplesWithoutEnergy]，标记 [Status.UNVERIFIED_NO_CAPTURE_ENERGY]
 *（明确未验证，不假通过）。
 *
 * 不做：重启音箱、改系统路由权限、提权、MODIFY_AUDIO_ROUTING 私有 API。
 */
class LocalOutputMute(
    private val volume: StreamMusicVolume,
    private val verifySilentSamples: Int = 4,
    private val energyFloor: Double = 80.0,
    private val maxPendingSamplesWithoutEnergy: Int = 24,
    private val restoreVolumeIndex: Boolean = true,
    /**
     * 初始验证窗所需有声采样数：达到后锁定探针通过（`probeLocked=true`），
     * 后续切歌间隙或暂停造成的静音不再误触发回退 UNMUTE（修复 #3）。
     */
    private val verifyAudibleSamples: Int = 6,
) {
    enum class Policy {
        /** 静音本机全局 STREAM_MUSIC（会影响同流其它视频 App，公开 API 限制）。 */
        GLOBAL_STREAM_MUTE,
        /** 保留本机 STREAM_MUSIC 出声（其它视频 App 有声，但所选音乐 App 本机也会出声）。 */
        KEEP_LOCAL_OUTPUT,
    }

    enum class Status {
        IDLE,
        /** 已 mute，等待捕获探针确认。 */
        PENDING_VERIFY,
        /** 探针确认：本机 mute 后捕获仍有能量。 */
        ACTIVE_VERIFIED,
        /** 初始探针窗口内 mute 导致捕获变静，已回退。 */
        UNAVAILABLE_CAPTURE_SILENCED,
        /** 投送期间系统或按键解除了 STREAM_MUSIC 静音（禁止循环强制重静音）。 */
        EXTERNAL_UNMUTED,
        /** 捕获持续近零，未能验证本机静音与捕获是否解耦（非假通过）。 */
        UNVERIFIED_NO_CAPTURE_ENERGY,
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
        val streamMuted: Boolean = false,
        val probeLocked: Boolean = false,
        val policy: Policy = Policy.GLOBAL_STREAM_MUTE,
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
    private var audibleCount: Int = 0
    private var probeLocked: Boolean = false
    private var activePolicy: Policy = Policy.GLOBAL_STREAM_MUTE
    private var silentStreak: Int = 0
    private var pendingZeroStreak: Int = 0

    fun snapshot(): Snapshot {
        val mutedNow = runCatching { volume.isMuted() }.getOrDefault(false)
        return Snapshot(
            status = status,
            api = "AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE|UNMUTE, 0)",
            savedIndex = savedIndex,
            messageZh = when (status) {
                Status.IDLE -> "本机出声（未尝试静音）"
                Status.PENDING_VERIFY -> "已请求全局 STREAM_MUSIC mute，等待捕获探针…"
                Status.ACTIVE_VERIFIED ->
                    if (probeLocked) {
                        "本机全局 STREAM_MUSIC 已静音（探针已锁定，切歌保持静音；同流视频 App 也会静音）"
                    } else {
                        "本机 STREAM_MUSIC 已 mute；捕获探针仍有能量（估计）"
                    }
                Status.UNAVAILABLE_CAPTURE_SILENCED -> "本机静音会连带静音捕获，已回退；本机仍出声"
                Status.EXTERNAL_UNMUTED -> "系统或按键已解除 STREAM_MUSIC 静音（不做循环强行重静音）；本机与音箱可能同时出声"
                Status.UNVERIFIED_NO_CAPTURE_ENERGY -> "捕获持续近零，本机静音未能验证（未假通过）"
                Status.RESTORED -> "已恢复本机 STREAM_MUSIC"
                Status.SKIPPED ->
                    if (activePolicy == Policy.KEEP_LOCAL_OUTPUT) {
                        "已按设置保留本机 STREAM_MUSIC 出声（Android 普通权限无单 App 本机静音公开 API）"
                    } else {
                        "已跳过本机静音（非 App 捕获源）"
                    }
            },
            streamMuted = mutedNow,
            probeLocked = probeLocked,
            policy = activePolicy,
        )
    }

    fun skip(reason: String = "non-app source"): Snapshot {
        activePolicy = Policy.GLOBAL_STREAM_MUTE
        probeLocked = false
        audibleCount = 0
        status = Status.SKIPPED
        return snapshot().copy(messageZh = "已跳过本机静音：$reason")
    }

    /** 开始投送后调用：mute 并进入探针窗口。 */
    fun beginMuteForCast(policy: Policy = Policy.GLOBAL_STREAM_MUTE): Snapshot {
        activePolicy = policy
        if (policy == Policy.KEEP_LOCAL_OUTPUT) {
            probeLocked = false
            audibleCount = 0
            silentStreak = 0
            pendingZeroStreak = 0
            status = Status.SKIPPED
            return snapshot()
        }
        if (status == Status.ACTIVE_VERIFIED || status == Status.PENDING_VERIFY) return snapshot()
        savedIndex = volume.getIndex()
        hadEnergyBeforeOrDuring = false
        audibleCount = 0
        probeLocked = false
        silentStreak = 0
        pendingZeroStreak = 0
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
        if (status != Status.PENDING_VERIFY &&
            status != Status.ACTIVE_VERIFIED &&
            status != Status.EXTERNAL_UNMUTED
        ) {
            return snapshot()
        }
        if (captureEnergy == null) return snapshot()
        val mutedNow = runCatching { volume.isMuted() }.getOrDefault(false)
        if (status == Status.ACTIVE_VERIFIED && !mutedNow) {
            status = Status.EXTERNAL_UNMUTED
            return snapshot()
        }
        if (status == Status.EXTERNAL_UNMUTED && mutedNow) {
            status = Status.ACTIVE_VERIFIED
        }
        if (captureEnergy >= energyFloor) {
            hadEnergyBeforeOrDuring = true
            audibleCount++
            if (audibleCount >= verifyAudibleSamples) {
                probeLocked = true
            }
            silentStreak = 0
            pendingZeroStreak = 0
            if (status == Status.PENDING_VERIFY) status = Status.ACTIVE_VERIFIED
            return snapshot()
        }
        // 能量过低
        if (!hadEnergyBeforeOrDuring) {
            // 尚无「有声」基线：不误判为「静音杀掉捕获」；超时则明确未验证
            if (status == Status.PENDING_VERIFY) {
                pendingZeroStreak++
                if (pendingZeroStreak >= maxPendingSamplesWithoutEnergy) {
                    status = Status.UNVERIFIED_NO_CAPTURE_ENERGY
                }
            }
            return snapshot()
        }
        silentStreak++
        if (probeLocked) {
            // 初始验证已通过并锁定：切歌间隙/暂停静音保持 STREAM_MUSIC 静音，不误触发 UNMUTE
            return snapshot()
        }
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
            if (restoreVolumeIndex) {
                val idx = savedIndex
                if (idx != null) {
                    val max = volume.getMaxIndex().coerceAtLeast(0)
                    volume.setIndex(idx.coerceIn(0, max))
                }
            }
        }
        status = if (fromFailure) Status.UNAVAILABLE_CAPTURE_SILENCED else Status.RESTORED
        audibleCount = 0
        probeLocked = false
        silentStreak = 0
        pendingZeroStreak = 0
    }
}
