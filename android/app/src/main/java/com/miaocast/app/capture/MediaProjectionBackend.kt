package com.miaocast.app.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 标准投屏采集后端（API 29–36 默认零依赖兜底）。
 *
 * 从 `CastService` 原样抽离，严格保持 Fail-Closed 单 UID `PlaybackCaptureFactory`、
 * `4×chunk` 紧致环缓、热切源异步反注册退避重试与 `captureGen` 代际隔离语义。
 */
class MediaProjectionBackend(
    private val projection: MediaProjection,
    private val isSessionRunning: () -> Boolean,
    private val onEvent: (String) -> Unit,
) : AudioCaptureBackend {
    private val captureGen = AtomicLong(0L)
    private val active = AtomicBoolean(false)

    @Volatile private var record: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var frameCallback: ((ByteArray, Int) -> Unit)? = null
    @Volatile private var fatalCallback: ((String) -> Unit)? = null
    @Volatile private var currentUid: Int = -1
    @Volatile private var currentKeepLocal: Boolean = false

    override fun start(
        request: AudioCaptureBackend.CaptureRequest,
        onPcmFrame: (pcm: ByteArray, length: Int) -> Unit,
        onFatalError: (reasonZh: String) -> Unit,
    ): AudioCaptureBackend.StartResult {
        if (request.scopeMode != CaptureScopeMode.SINGLE_APP) {
            return AudioCaptureBackend.StartResult.Failed(
                "标准投屏模式仅允许严格单 App UID 捕获（Fail-Closed 拒绝全局捕获）",
            )
        }
        frameCallback = onPcmFrame
        fatalCallback = onFatalError
        return startInternal(request.targetUid, request.keepLocalOutput, isHotSwitch = false)
    }

    override fun hotSwitchUid(newUid: Int, keepLocalOutput: Boolean): AudioCaptureBackend.StartResult {
        val prevUid = currentUid
        val prevKeep = currentKeepLocal
        stopCaptureLoopOnly()
        val switched = startInternal(newUid, keepLocalOutput, isHotSwitch = true)
        if (switched is AudioCaptureBackend.StartResult.Started) return switched
        // Fail-closed：尝试恢复原 UID；若亦失败则交由调用方停止会话，绝不回退全系统捕获。
        if (isSessionRunning() && prevUid >= 0) {
            val restored = startInternal(prevUid, prevKeep, isHotSwitch = true)
            if (restored is AudioCaptureBackend.StartResult.Started) {
                return AudioCaptureBackend.StartResult.Failed(
                    "系统限制无法热切换至新音源，已恢复原音源（uid=$prevUid）",
                )
            }
        }
        return switched
    }

    override fun stop() {
        active.set(false)
        stopCaptureLoopOnly()
        frameCallback = null
        fatalCallback = null
    }

    private fun stopCaptureLoopOnly() {
        captureGen.incrementAndGet()
        val oldRec = record
        record = null
        val oldThread = captureThread
        captureThread = null
        runCatching { oldRec?.stop() }
        oldThread?.interrupt()
        if (oldThread != null && oldThread !== Thread.currentThread()) {
            runCatching { oldThread.join(500) }
        }
        runCatching { oldRec?.release() }
    }

    private fun startInternal(
        uid: Int,
        keepLocalOutput: Boolean,
        isHotSwitch: Boolean,
    ): AudioCaptureBackend.StartResult {
        if (uid < 0) {
            return AudioCaptureBackend.StartResult.Failed("UID 无效，拒绝开始捕获")
        }
        val config = try {
            PlaybackCaptureFactory.buildAppCapture(projection, uid)
        } catch (e: Exception) {
            return AudioCaptureBackend.StartResult.Failed("无法构建 UID 过滤捕获：${e.message}")
        }
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioCaptureBackend.SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val chunk = AudioCaptureBackend.BYTES_PER_FRAME
        val minBuf = AudioRecord.getMinBufferSize(
            AudioCaptureBackend.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        // 热切换时 AOSP AudioRecord.release() 内部异步反注册旧 AudioPolicy，短暂退避重试避免竞态误判失败。
        val retryDelaysMs = if (isHotSwitch) longArrayOf(0L, 60L, 150L) else longArrayOf(0L)
        var rec: AudioRecord? = null
        var lastBuildErr: String? = null
        for (delayMs in retryDelaysMs) {
            if (!isSessionRunning()) {
                return AudioCaptureBackend.StartResult.Failed("投送会话已停止")
            }
            if (delayMs > 0L) {
                runCatching { Thread.sleep(delayMs) }
                if (!isSessionRunning()) {
                    return AudioCaptureBackend.StartResult.Failed("投送会话已停止")
                }
            }
            @Suppress("MissingPermission")
            val attempt = try {
                AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setAudioPlaybackCaptureConfig(config)
                    // 4×chunk ≈ 85ms 紧致捕获环缓，降低捕获侧积压
                    .setBufferSizeInBytes(maxOf(chunk * 4, minBuf))
                    .build()
            } catch (e: Exception) {
                lastBuildErr = "AudioRecord 创建失败（可能被拒绝捕获）：${e.message}"
                null
            }
            if (attempt != null) {
                if (attempt.state == AudioRecord.STATE_INITIALIZED) {
                    rec = attempt
                    break
                } else {
                    lastBuildErr = "AudioRecord 未初始化，拒绝捕获或设备不支持"
                    runCatching { attempt.release() }
                }
            }
        }
        val initializedRec = rec
            ?: return AudioCaptureBackend.StartResult.Failed(
                lastBuildErr ?: "AudioRecord 未初始化，拒绝捕获或设备不支持",
            )

        record = initializedRec
        currentUid = uid
        currentKeepLocal = keepLocalOutput
        active.set(true)
        val myGen = captureGen.incrementAndGet()
        initializedRec.startRecording()
        val status = "AudioPlaybackCapture 已开始（uid=$uid, state=${initializedRec.recordingState}）"
        onEvent(status)

        captureThread = Thread({
            val buf = ByteArray(chunk)
            while (active.get() && isSessionRunning() && captureGen.get() == myGen) {
                var off = 0
                while (off < chunk && active.get() && isSessionRunning() && captureGen.get() == myGen) {
                    val n = initializedRec.read(buf, off, chunk - off)
                    if (n <= 0) {
                        if (active.get() && isSessionRunning() && captureGen.get() == myGen) {
                            onEvent("AudioRecord.read 返回 $n")
                        }
                        return@Thread
                    }
                    off += n
                }
                if (captureGen.get() != myGen || !active.get()) break
                frameCallback?.invoke(buf, chunk)
            }
        }, "audio-capture-$myGen").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        val muteHandling = if (keepLocalOutput) {
            AudioCaptureBackend.LocalMuteHandling.KEEP_LOCAL_OUTPUT
        } else {
            AudioCaptureBackend.LocalMuteHandling.MANAGED_BY_GLOBAL_STREAM_MUTE
        }
        return AudioCaptureBackend.StartResult.Started(muteHandling, status)
    }
}
