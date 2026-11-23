package com.miaocast.app.capture.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.miaocast.app.capture.AudioCaptureBackend
import com.miaocast.app.capture.CaptureScopeMode
import com.miaocast.app.compat.ShellCaptureCompat
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 封装 `uid=2000` (`com.android.shell`) 进程内对 `android.media.audiopolicy` 隐藏系统 API 的反射调用，
 * 与 Android 11–12 `REMOTE_SUBMIX` 显式全局采集。
 *
 * 严格遵循 Fail-Closed：任何反射或注册失败均直接返回明确错误原因，绝不静默降级为整机捕获。
 */
object AudioPolicyReflector {
    private const val TAG = "MiPlayAudioPolicy"
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
    private const val RULE_MATCH_UID = 0x4
    private const val ROUTE_FLAG_LOOP_BACK = 0x2
    private const val ROUTE_FLAG_LOOP_BACK_RENDER = 0x3

    @Volatile private var bypassApplied = false

    class CaptureHandle(
        val record: AudioRecord,
        val muteHandling: AudioCaptureBackend.LocalMuteHandling,
        val statusLine: String,
        private val onRelease: () -> Unit,
    ) {
        @Volatile private var released = false

        @Synchronized
        fun release() {
            if (released) return
            released = true
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { onRelease() }
        }
    }

    sealed class OpenResult {
        data class Ok(val handle: CaptureHandle) : OpenResult()
        data class Error(val messageZh: String) : OpenResult()
    }

    fun ensureHiddenApiBypass() {
        if (bypassApplied) return
        synchronized(this) {
            if (bypassApplied) return
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/media/",
                    "Landroid/app/ActivityThread",
                    "Lcom/android/org/conscrypt/",
                )
            }
            bypassApplied = true
        }
    }

    fun openCapture(
        context: Context,
        scopeMode: CaptureScopeMode,
        uid: Int,
        muteLocal: Boolean,
    ): OpenResult {
        ensureHiddenApiBypass()
        val sdk = Build.VERSION.SDK_INT
        return when {
            sdk >= Build.VERSION_CODES.TIRAMISU -> {
                if (scopeMode == CaptureScopeMode.SINGLE_APP && uid < 0) {
                    OpenResult.Error("单 App 捕获模式缺少有效 UID（Fail-Closed 已拒绝）")
                } else {
                    openAudioPolicyCaptureApi33(context, scopeMode, uid, muteLocal)
                }
            }
            sdk >= Build.VERSION_CODES.R -> {
                if (scopeMode == CaptureScopeMode.SINGLE_APP) {
                    OpenResult.Error(
                        "Android 11–12（API $sdk）不支持 shell 单 App 隔离（Fail-Closed 已拒绝降级）",
                    )
                } else {
                    openRemoteSubmixCapture(context)
                }
            }
            else -> OpenResult.Error("Android 10（API $sdk）不支持 shell 音频捕获")
        }
    }

    private fun openAudioPolicyCaptureApi33(
        context: Context,
        scopeMode: CaptureScopeMode,
        uid: Int,
        muteLocal: Boolean,
    ): OpenResult {
        var registeredPolicy: Any? = null
        var focusThread: HandlerThread? = null
        var focusBridge: ShellAudioFocusPolicyBridge? = null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return OpenResult.Error("无法获取 AudioManager 服务")

        return try {
            val mixingRuleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixingRuleBuilder = mixingRuleBuilderCls.getDeclaredConstructor().newInstance()

            val addAttrRule = mixingRuleBuilderCls.getMethod(
                "addRule",
                AudioAttributes::class.java,
                Int::class.javaPrimitiveType,
            )
            val usages = intArrayOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
            )
            for (usage in usages) {
                val attrs = AudioAttributes.Builder().setUsage(usage).build()
                addAttrRule.invoke(mixingRuleBuilder, attrs, RULE_MATCH_ATTRIBUTE_USAGE)
            }

            if (scopeMode == CaptureScopeMode.SINGLE_APP) {
                val addMixRule = mixingRuleBuilderCls.getMethod(
                    "addMixRule",
                    Int::class.javaPrimitiveType,
                    Any::class.java,
                )
                addMixRule.invoke(mixingRuleBuilder, RULE_MATCH_UID, Integer.valueOf(uid))
            }

            val mixingRule = mixingRuleBuilderCls.getMethod("build").invoke(mixingRuleBuilder)

            // MIX_TYPE_PLAYERS 描述播放侧输出格式，需使用 CHANNEL_OUT_STEREO
            val mixFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioCaptureBackend.SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixingRuleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val mixBuilder = mixBuilderCls.getDeclaredConstructor(mixingRuleCls).newInstance(mixingRule)
            mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, mixFormat)

            val routeFlags = if (muteLocal) ROUTE_FLAG_LOOP_BACK else ROUTE_FLAG_LOOP_BACK_RENDER
            mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, routeFlags)
            val audioMix = mixBuilderCls.getMethod("build").invoke(mixBuilder)
                ?: return OpenResult.Error("AudioMix.Builder.build() 返回 null")

            val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val audioMixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val registerMethod = AudioManager::class.java.getMethod("registerAudioPolicy", policyCls)

            // 仅在「独立单 App 环回投送」（音箱放音乐、手机放其他应用）时启用双区 AudioFocus 隔离，
            // 彻底避免刷短视频暂停音箱音乐或恢复音乐导致视频短暂无声；若 ROM 已有外部焦点策略冲突则自动回退纯音频路由策略。
            val wantFocusIsolation = scopeMode == CaptureScopeMode.SINGLE_APP && muteLocal
            var focusIsolationActive = false
            var audioPolicy: Any? = null
            var regStatus = -1

            if (wantFocusIsolation) {
                runCatching {
                    val ht = HandlerThread("miplay-focus-policy").apply { start() }
                    val bridge = ShellAudioFocusPolicyBridge(audioManager, uid)
                    val pb = policyBuilderCls.getDeclaredConstructor(Context::class.java).newInstance(context)
                    policyBuilderCls.getMethod("addMix", audioMixCls).invoke(pb, audioMix)
                    policyBuilderCls.getMethod("setLooper", Looper::class.java).invoke(pb, ht.looper)
                    val listenerCls = Class.forName("android.media.audiopolicy.AudioPolicy\$AudioPolicyFocusListener")
                    policyBuilderCls.getMethod("setAudioPolicyFocusListener", listenerCls).invoke(pb, bridge)
                    policyBuilderCls.getMethod("setIsAudioFocusPolicy", Boolean::class.javaPrimitiveType)
                        .invoke(pb, true)
                    val candidate = policyBuilderCls.getMethod("build").invoke(pb)!!
                    bridge.attachPolicy(candidate)
                    val status = (registerMethod.invoke(audioManager, candidate) as? Int) ?: -1
                    if (status == 0) {
                        audioPolicy = candidate
                        regStatus = 0
                        focusThread = ht
                        focusBridge = bridge
                        focusIsolationActive = true
                    } else {
                        bridge.clear()
                        ht.quitSafely()
                        runCatching { Log.w(TAG, "registerAudioPolicy(with focus policy) rc=$status, falling back to mix-only") }
                    }
                }.onFailure { t ->
                    runCatching { Log.w(TAG, "focus isolation setup failed (${t.message}), falling back to mix-only") }
                }
            }

            if (audioPolicy == null) {
                val policyBuilder = policyBuilderCls.getDeclaredConstructor(Context::class.java).newInstance(context)
                policyBuilderCls.getMethod("addMix", audioMixCls).invoke(policyBuilder, audioMix)
                runCatching {
                    policyBuilderCls.getMethod("setLooper", Looper::class.java)
                        .invoke(policyBuilder, Looper.getMainLooper())
                }
                val fallbackPolicy = policyBuilderCls.getMethod("build").invoke(policyBuilder)!!
                regStatus = (registerMethod.invoke(audioManager, fallbackPolicy) as? Int) ?: -1
                if (regStatus != 0) {
                    return OpenResult.Error(
                        "registerAudioPolicy 注册失败（code=$regStatus），可能是当前 ROM 限制 shell 动态路由策略",
                    )
                }
                audioPolicy = fallbackPolicy
            }

            val finalPolicy = audioPolicy!!
            registeredPolicy = finalPolicy

            val record = buildAudioRecordForMix(context, policyCls, finalPolicy, audioMixCls, audioMix)
                ?: return OpenResult.Error("无法为 AudioMix 创建 AudioRecord").also {
                    focusBridge?.clear()
                    focusThread?.quitSafely()
                    unregisterQuietly(audioManager, finalPolicy)
                }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                focusBridge?.clear()
                focusThread?.quitSafely()
                unregisterQuietly(audioManager, finalPolicy)
                return OpenResult.Error("AudioPolicy 创建的 AudioRecord 未初始化（state=${record.state}）")
            }

            val muteHandling = when {
                !muteLocal -> AudioCaptureBackend.LocalMuteHandling.KEEP_LOCAL_OUTPUT
                scopeMode == CaptureScopeMode.SINGLE_APP -> AudioCaptureBackend.LocalMuteHandling.ISOLATED_APP_LOOPBACK
                else -> AudioCaptureBackend.LocalMuteHandling.SYSTEM_ROUTE_LOOPBACK
            }
            val status = when {
                scopeMode == CaptureScopeMode.SINGLE_APP && focusIsolationActive ->
                    "无感单 App 捕获已启动（uid=$uid, 独立音频焦点隔离已开启）"
                scopeMode == CaptureScopeMode.SINGLE_APP ->
                    "无感单 App 捕获已启动（uid=$uid, loopbackMute=$muteLocal）"
                else ->
                    "无感全局媒体捕获已启动（loopbackMute=$muteLocal）"
            }

            val releaseBridge = focusBridge
            val releaseThread = focusThread
            OpenResult.Ok(
                CaptureHandle(
                    record = record,
                    muteHandling = muteHandling,
                    statusLine = status,
                    onRelease = {
                        releaseBridge?.clear()
                        unregisterQuietly(audioManager, finalPolicy)
                        releaseThread?.quitSafely()
                    },
                ),
            )
        } catch (t: Throwable) {
            focusBridge?.clear()
            registeredPolicy?.let { unregisterQuietly(audioManager, it) }
            focusThread?.quitSafely()
            val root = t.cause ?: t
            OpenResult.Error("AudioPolicy 隐藏 API 反射启动失败：${root.javaClass.simpleName}: ${root.message}")
        }
    }

    /**
     * 优先使用 `AudioRecord.Builder.setContext(shellCtx)` + `addr=<mix.getRegistration()>` 显式构造 AudioRecord：
     * AOSP 的 `AudioPolicy.createAudioRecordSink(mix)` 内部未传 Context，会回落到 `ActivityThread.currentOpPackageName()`
     * （在 `app_process` 中为 `"android"`），导致 Android 13–17 `AudioFlinger` 报 `EX_SECURITY (uid=2000, packageName=android)`。
     * 若反射取 `getRegistration()` 失败再回退 `createAudioRecordSink`。
     */
    @Suppress("MissingPermission")
    private fun buildAudioRecordForMix(
        context: Context,
        policyCls: Class<*>,
        audioPolicy: Any,
        audioMixCls: Class<*>,
        audioMix: Any,
    ): AudioRecord? {
        val explicit = runCatching {
            val regAddr = audioMixCls.getMethod("getRegistration").invoke(audioMix) as? String
            if (regAddr.isNullOrBlank()) return@runCatching null

            val attrBuilder = AudioAttributes.Builder()
            AudioAttributes.Builder::class.java
                .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
                .invoke(attrBuilder, MediaRecorder.AudioSource.REMOTE_SUBMIX)
            val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
            addTag.invoke(attrBuilder, "addr=$regAddr")
            addTag.invoke(attrBuilder, "fixedVolume")
            val recAttrs = attrBuilder.build()

            val recFmt = AudioFormat.Builder()
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
            val arb = AudioRecord.Builder()
                .setAudioFormat(recFmt)
                .setBufferSizeInBytes(maxOf(chunk * 4, minBuf))
            ShellCaptureCompat.applyRecordContext(arb, context)
            AudioRecord.Builder::class.java
                .getMethod("setAudioAttributes", AudioAttributes::class.java)
                .invoke(arb, recAttrs)
            arb.build()
        }.getOrNull()
        if (explicit != null && explicit.state == AudioRecord.STATE_INITIALIZED) {
            return explicit
        }
        runCatching { explicit?.release() }
        val createSink = policyCls.getMethod("createAudioRecordSink", audioMixCls)
        return createSink.invoke(audioPolicy, audioMix) as? AudioRecord
    }

    @Suppress("MissingPermission", "WrongConstant")
    private fun openRemoteSubmixCapture(context: Context): OpenResult {
        return try {
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
            val arb = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(chunk * 4, minBuf))
            ShellCaptureCompat.applyRecordContext(arb, context)
            val record = arb.build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                return OpenResult.Error("REMOTE_SUBMIX AudioRecord 未初始化（state=${record.state}）")
            }
            OpenResult.Ok(
                CaptureHandle(
                    record = record,
                    muteHandling = AudioCaptureBackend.LocalMuteHandling.SYSTEM_ROUTE_LOOPBACK,
                    statusLine = "无感全局媒体捕获已启动（REMOTE_SUBMIX）",
                    onRelease = {},
                ),
            )
        } catch (t: Throwable) {
            OpenResult.Error("REMOTE_SUBMIX 启动失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun unregisterQuietly(audioManager: AudioManager, audioPolicy: Any) {
        runCatching {
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val unregister = AudioManager::class.java.getMethod("unregisterAudioPolicy", policyCls)
            unregister.invoke(audioManager, audioPolicy)
        }
    }
}
