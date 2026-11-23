package com.miaocast.app.ui

import com.miaocast.app.NativeBridge
import org.json.JSONObject

/**
 * 跨端音量过大保护策略（对齐 L1 Rust `core/src/volume_guard.rs`）：
 * - 保护关闭：直接放行；
 * - 保护开启（默认开启，默认安全上限 60%，支持 30%–90% 按 5% 调节）：
 *   仅在从安全区（<= safeLimit）跨过安全上限进入高音量区（> safeLimit）时要求二次确认；
 *   已在高音量区内继续微调或降回安全区直接放行，降回安全区后再次跨线会重新确认。
 */
object VolumeSafetyGuard {
    const val DEFAULT_SAFE_LIMIT = 60
    const val MIN_SAFE_LIMIT = 30
    const val MAX_SAFE_LIMIT = 90
    const val STEP = 5

    data class Policy(
        val enabled: Boolean = true,
        val safeLimit: Int = DEFAULT_SAFE_LIMIT,
    ) {
        val normalizedSafeLimit: Int
            get() = normalizeSafeLimit(safeLimit)
    }

    sealed class Decision {
        data class Allow(val volume: Int) : Decision()
        data class RequireConfirm(
            val currentVolume: Int,
            val targetVolume: Int,
            val safeLimit: Int,
        ) : Decision()
    }

    fun normalizeSafeLimit(raw: Int): Int = raw.coerceIn(MIN_SAFE_LIMIT, MAX_SAFE_LIMIT)

    fun snapSafeLimit(raw: Int): Int {
        val clamped = normalizeSafeLimit(raw)
        val steps = ((clamped - MIN_SAFE_LIMIT) + STEP / 2) / STEP
        return (MIN_SAFE_LIMIT + steps * STEP).coerceIn(MIN_SAFE_LIMIT, MAX_SAFE_LIMIT)
    }

    fun isInHighVolumeZone(policy: Policy, volume: Int): Boolean =
        policy.enabled && volume.coerceIn(0, 100) > policy.normalizedSafeLimit

    fun syncToNative(policy: Policy) {
        try {
            NativeBridge.setVolumeGuardPolicy(policy.enabled, policy.normalizedSafeLimit)
        } catch (_: LinkageError) {
            // 纯 JVM 单元测试环境无 arm64 JNI .so，安全忽略
        }
    }

    /**
     * 裁决单路音量调整；优先走 L1 Rust 引擎单点实现，JVM 离线单测无 `.so` 时回退完全等价的纯逻辑。
     */
    fun evaluateSingle(policy: Policy, currentVolume: Int, targetVolume: Int): Decision {
        val cur = currentVolume.coerceIn(0, 100)
        val tgt = targetVolume.coerceIn(0, 100)
        val limit = policy.normalizedSafeLimit
        val fromNative = try {
            parseDecisionJson(NativeBridge.evaluateVolumeChangeJson(policy.enabled, limit, cur, tgt))
        } catch (_: LinkageError) {
            null
        }
        if (fromNative != null) return fromNative
        return if (policy.enabled && tgt > limit && cur <= limit) {
            Decision.RequireConfirm(
                currentVolume = cur,
                targetVolume = tgt,
                safeLimit = limit,
            )
        } else {
            Decision.Allow(tgt)
        }
    }

    /**
     * 裁决多音箱总音量调整：只要总音量或任意受影响音箱当前处于安全区（<= safeLimit）且目标 > safeLimit，
     * 即判定跨过安全上限线并要求二次确认；回显原音量采用拖动前总音量 `masterCurrent`。
     */
    fun evaluateGroup(
        policy: Policy,
        masterCurrent: Int,
        speakerCurrents: Collection<Int>,
        targetVolume: Int,
    ): Decision {
        val master = masterCurrent.coerceIn(0, 100)
        val effectiveCurrent = speakerCurrents
            .map { it.coerceIn(0, 100) }
            .fold(master) { acc, v -> minOf(acc, v) }
        return when (val d = evaluateSingle(policy, effectiveCurrent, targetVolume)) {
            is Decision.Allow -> d
            is Decision.RequireConfirm -> d.copy(currentVolume = master)
        }
    }

    private fun parseDecisionJson(raw: String): Decision? = runCatching {
        val obj = JSONObject(raw)
        when (obj.optString("decision")) {
            "allow" -> Decision.Allow(obj.getInt("volume").coerceIn(0, 100))
            "require_confirm" -> Decision.RequireConfirm(
                currentVolume = obj.getInt("current_volume").coerceIn(0, 100),
                targetVolume = obj.getInt("target_volume").coerceIn(0, 100),
                safeLimit = normalizeSafeLimit(obj.getInt("safe_limit")),
            )
            else -> null
        }
    }.getOrNull()
}
