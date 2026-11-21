package com.miplay.sender.capture

/**
 * L2 可插拔音频采集后端统一契约。
 *
 * 所有后端必须产出与现有 `AacEncoder` / L1 Rust 引擎一致的 PCM 帧契约：
 * `48,000 Hz` / `PCM_16BIT` / `CHANNEL_IN_STEREO` / `4096 bytes`（`1024 samples = 21.333ms`）。
 */
interface AudioCaptureBackend {
    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNEL_COUNT = 2
        const val SAMPLES_PER_FRAME = 1024
        const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * CHANNEL_COUNT * 2 // 4096 bytes
    }

    enum class LocalMuteHandling {
        /** 普通 MediaProjection 模式：由 CastService 通过 LocalOutputMute 控制全局 STREAM_MUSIC。 */
        MANAGED_BY_GLOBAL_STREAM_MUTE,

        /** Android 13+ AudioPolicy ROUTE_FLAG_LOOP_BACK：仅静音目标单 App，不碰全局 STREAM_MUSIC。 */
        ISOLATED_APP_LOOPBACK,

        /** REMOTE_SUBMIX / 全局环回：由系统路由原生接管本机输出。 */
        SYSTEM_ROUTE_LOOPBACK,

        /** 保留本机扬声器同步出声。 */
        KEEP_LOCAL_OUTPUT,
    }

    data class CaptureRequest(
        val scopeMode: CaptureScopeMode,
        /** 单 App 模式下必须 >= 0；全局媒体模式下为 -1。 */
        val targetUid: Int,
        val keepLocalOutput: Boolean,
    )

    sealed class StartResult {
        data class Started(
            val muteHandling: LocalMuteHandling,
            val statusLine: String,
        ) : StartResult()

        data class Failed(val messageZh: String) : StartResult()
    }

    /** 启动捕获并在独立实时线程回调每帧 4096B PCM。 */
    fun start(
        request: CaptureRequest,
        onPcmFrame: (pcm: ByteArray, length: Int) -> Unit,
        onFatalError: (reasonZh: String) -> Unit,
    ): StartResult

    /** 在不中断主会话与编码器的前提下热切换单 App UID（若不支持或失败返回 Failed）。 */
    fun hotSwitchUid(newUid: Int, keepLocalOutput: Boolean): StartResult

    /** 释放捕获资源并确保音频路由干净恢复。 */
    fun stop()
}
