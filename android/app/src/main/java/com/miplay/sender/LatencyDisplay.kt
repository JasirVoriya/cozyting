package com.miplay.sender

import org.json.JSONObject

/**
 * 延迟诊断展示：估计值（非协议 RTT），缺失显示「—」而非 0。
 * 定义与 core `latency::LATENCY_DEFINITION` / stats.latency.definition 对齐。
 */
object LatencyDisplay {
    const val UNKNOWN = "—"

    sealed interface ReceiverIndicator {
        data object Hidden : ReceiverIndicator
        data object Loading : ReceiverIndicator
        data class Value(val milliseconds: Long) : ReceiverIndicator
    }

    fun optLongOrNull(obj: JSONObject?, key: String): Long? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        return runCatching { obj.getLong(key) }.getOrNull()
    }

    fun receiverIndicator(
        stats: JSONObject?,
        deviceId: String,
        selected: Boolean,
        casting: Boolean,
        failed: Boolean = false,
    ): ReceiverIndicator {
        if (!selected || !casting || failed || stats == null) return ReceiverIndicator.Hidden
        val receivers = stats.optJSONArray("receivers") ?: return ReceiverIndicator.Hidden
        val receiver = (0 until receivers.length())
            .mapNotNull(receivers::optJSONObject)
            .firstOrNull { it.optString("ip") == deviceId }
            ?: return ReceiverIndicator.Hidden
        if (!receiver.isNull("error") && receiver.optString("error").isNotBlank()) {
            return ReceiverIndicator.Hidden
        }
        if (!receiver.has("latency_e2e_est_ms") || receiver.isNull("latency_e2e_est_ms")) {
            return ReceiverIndicator.Loading
        }
        val raw = receiver.opt("latency_e2e_est_ms") as? Number ?: return ReceiverIndicator.Hidden
        val value = raw.toLong()
        if (value < 0 || raw.toDouble() != value.toDouble()) return ReceiverIndicator.Hidden
        return ReceiverIndicator.Value(value)
    }

    fun formatReceiverValue(milliseconds: Long): String = "$milliseconds ms"

    /** 毫秒；可选附带约几秒。未知 → — */
    fun formatMs(ms: Long?, withSeconds: Boolean = true): String {
        if (ms == null) return UNKNOWN
        return if (withSeconds) {
            val sec = ms / 1000.0
            "%dms(≈%.1fs)".format(ms, sec)
        } else {
            "${ms}ms"
        }
    }

    /**
     * 投送进行中的延迟摘要：总体 + 每接收端一行。
     * 不含 UI 美化，仅可读数字与定义短注。
     */
    fun formatBlock(stats: JSONObject): String {
        val lat = stats.optJSONObject("latency")
        val kind = lat?.optString("kind")?.ifBlank { null } ?: "未知"
        val def = lat?.optString("definition")?.take(160)?.ifBlank { null } ?: "未知"
        val e2e = formatMs(optLongOrNull(lat, "e2e_est_ms"))
        val ahead = formatMs(optLongOrNull(lat, "schedule_ahead_ms"))
        val delayCfg = lat?.optLong("playback_delay_ms", -1) ?: -1
        val delayStr = if (delayCfg >= 0) "${delayCfg}ms" else UNKNOWN
        val flushed = lat?.optLong("feed_flushed_aus_at_start", -1) ?: -1
        val flushedMs = lat?.optLong("feed_flushed_est_ms", -1) ?: -1
        val flushedStr = if (flushed >= 0) "${flushed}AU/~${flushedMs}ms" else UNKNOWN

        val sb = StringBuilder()
        sb.append("延迟诊断（$kind）总体E2E $e2e  超前 $ahead  配置playback_delay $delayStr\n")
        sb.append("起播冲刷积压 $flushedStr\n")

        val rs = stats.optJSONArray("receivers")
        if (rs != null) {
            for (i in 0 until rs.length()) {
                val r = rs.optJSONObject(i) ?: continue
                val ip = r.optString("ip", "?")
                val nameHint = deviceHint(ip, r.optString("device_channel", ""))
                val re2e = formatMs(optLongOrNull(r, "latency_e2e_est_ms"))
                val rahead = formatMs(optLongOrNull(r, "latency_schedule_ahead_ms"))
                sb.append("  · $nameHint $ip  E2E $re2e  超前 $rahead\n")
            }
        }
        sb.append("定义: $def")
        return sb.toString().trimEnd()
    }

    /** 从 IP 尾段或 channel 给可读标签（LX06/L17A 等由发现列表 checkbox 文案补充）。 */
    fun deviceHint(ip: String, channel: String): String {
        val ch = channel.trim()
        return if (ch.isNotEmpty()) ch else ip.substringAfterLast('.')
    }

}
