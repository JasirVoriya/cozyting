package com.miplay.sender.capture.shell

import android.media.AudioFocusInfo
import android.media.AudioManager
import android.media.audiopolicy.AudioPolicy
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 将 Android 系统 `AudioPolicy.AudioPolicyFocusListener` 桥接到 [DualZoneAudioFocusRouter]。
 *
 * 仅在 `uid=2000` `ShellAudioHelperMain` 进程中实例化（编译期依赖 `compileOnly(:hiddenapi-stubs)`，
 * 运行时由 Android Framework 提供真实父类）。
 * 生命周期与所属 `AudioPolicy` 强绑定：一旦停止投送或 Helper 异常退出，系统 `AudioService`
 * 会自动注销该外部焦点策略，零系统配置残留。
 */
class ShellAudioFocusPolicyBridge(
    private val audioManager: AudioManager,
    castTargetUid: Int,
) : AudioPolicy.AudioPolicyFocusListener() {
    companion object {
        private const val TAG = "MiPlayShellFocus"
    }

    private val router = DualZoneAudioFocusRouter(castTargetUid)
    private val policyRef = AtomicReference<Any?>(null)
    private val activeInfos = ConcurrentHashMap<String, AudioFocusInfo>()

    private val setFocusRequestResultMethod: Method? = runCatching {
        AudioManager::class.java.getMethod(
            "setFocusRequestResult",
            AudioFocusInfo::class.java,
            Int::class.javaPrimitiveType,
            AudioPolicy::class.java,
        )
    }.getOrNull()

    private val dispatchFocusChangeMethod: Method? = runCatching {
        AudioManager::class.java.getMethod(
            "dispatchAudioFocusChange",
            AudioFocusInfo::class.java,
            Int::class.javaPrimitiveType,
            AudioPolicy::class.java,
        )
    }.getOrNull()

    fun attachPolicy(audioPolicy: Any) {
        policyRef.set(audioPolicy)
    }

    fun clear() {
        policyRef.set(null)
        activeInfos.clear()
    }

    override fun onAudioFocusRequest(afi: AudioFocusInfo, requestResult: Int) {
        val policy = policyRef.get() ?: return
        val clientId = runCatching { afi.clientId }.getOrNull().orEmpty()
        if (clientId.isEmpty()) {
            grantQuietly(afi, policy)
            return
        }
        val uid = runCatching { afi.clientUid }.getOrDefault(-1)
        val pkg = runCatching { afi.packageName }.getOrNull().orEmpty()
        val gain = runCatching { afi.gainRequest }.getOrDefault(DualZoneAudioFocusRouter.AUDIOFOCUS_GAIN)

        activeInfos[clientId] = afi
        val actions = router.onFocusRequest(
            DualZoneAudioFocusRouter.FocusRequest(
                clientId = clientId,
                uid = uid,
                packageName = pkg,
                gainRequest = gain,
            ),
        )
        executeActions(actions, currentAfi = afi, policy = policy)
    }

    override fun onAudioFocusAbandon(afi: AudioFocusInfo) {
        val policy = policyRef.get() ?: return
        val clientId = runCatching { afi.clientId }.getOrNull().orEmpty()
        if (clientId.isEmpty()) return
        val uid = runCatching { afi.clientUid }.getOrDefault(-1)
        activeInfos.remove(clientId)
        val actions = router.onFocusAbandon(clientId, uid)
        executeActions(actions, currentAfi = null, policy = policy)
    }

    private fun executeActions(
        actions: List<DualZoneAudioFocusRouter.Action>,
        currentAfi: AudioFocusInfo?,
        policy: Any,
    ) {
        for (action in actions) {
            when (action) {
                is DualZoneAudioFocusRouter.Action.Grant -> {
                    val target = if (currentAfi != null && runCatching { currentAfi.clientId }.getOrNull() == action.clientId) {
                        currentAfi
                    } else {
                        activeInfos[action.clientId]
                    }
                    if (target != null) {
                        grantQuietly(target, policy)
                    }
                }
                is DualZoneAudioFocusRouter.Action.DispatchChange -> {
                    val target = activeInfos[action.clientId] ?: continue
                    if (action.focusChange == DualZoneAudioFocusRouter.AUDIOFOCUS_LOSS) {
                        activeInfos.remove(action.clientId)
                    }
                    runCatching {
                        dispatchFocusChangeMethod?.invoke(audioManager, target, action.focusChange, policy)
                    }.onFailure { t ->
                        runCatching { Log.w(TAG, "dispatchAudioFocusChange failed for ${action.clientId}: ${t.message}") }
                    }
                }
            }
        }
    }

    private fun grantQuietly(afi: AudioFocusInfo, policy: Any) {
        runCatching {
            setFocusRequestResultMethod?.invoke(audioManager, afi, AudioManager.AUDIOFOCUS_REQUEST_GRANTED, policy)
        }.onFailure { t ->
            runCatching { Log.w(TAG, "setFocusRequestResult failed: ${t.message}") }
        }
    }
}
