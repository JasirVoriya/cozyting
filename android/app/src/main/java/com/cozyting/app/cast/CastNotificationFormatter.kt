package com.cozyting.app.cast

import android.content.Context
import com.cozyting.app.R
import com.cozyting.app.capture.CaptureScopeMode
import org.json.JSONObject

/**
 * 投送前台常驻通知文案格式化器（纯逻辑，零 Android Framework 依赖，供单测直接覆盖）。
 *
 * 设计原则（方案 2）：
 * - 标题直指状态与音源名（如「正在投送 · 汽水音乐」），不再暴露「无感/选定 App/配置延迟/E2E估」等工程术语；
 * - 锁屏/收起态严格单行定长：1 台直接显示真实音箱名；≥ 2 台统一用简洁计数（如「2 台设备同步播放」），
 *   仅在存在重连或断开设备时聚合提醒异常数量；
 * - 下拉展开态（BigTextStyle）列出具体设备清单，异常设备置顶，最多显示 5 台并折叠余量。
 */
object CastNotificationFormatter {
    const val MAX_EXPANDED_DEVICES = 5

    enum class ReceiverStatus(val sortPriority: Int, val labelZh: String) {
        ERROR(0, "已断开"),
        RECONNECTING(1, "重连中"),
        CONNECTING(2, "连接中"),
        PAUSED(3, "已暂停"),
        PLAYING(4, "播放中"),
    }

    data class Strings(
        val silentPaused: String,
        val silentConnecting: String,
        val silentPlaying: String,
        val globalMedia: String,
        val fallbackAudio: String,
        val titlePaused: (String) -> String,
        val titleConnecting: (String) -> String,
        val titleCasting: (String) -> String,
        val preparing: String,
        val singleError: (String) -> String,
        val singleReconnecting: (String) -> String,
        val singleConnecting: (String) -> String,
        val multiConnecting: (Int) -> String,
        val multiPaused: (Int) -> String,
        val multiSynced: (Int) -> String,
        val multiAbnormal: (Int, String) -> String,
        val partReconnecting: (Int) -> String,
        val partError: (Int) -> String,
        val partConnecting: (Int) -> String,
        val statusLabel: (ReceiverStatus) -> String,
        val moreDevices: (Int) -> String,
        val collapsedWithTapHint: (String) -> String = { "$it · 点击打开控制卡" },
    ) {
        companion object {
            val DEFAULT_ZH = Strings(
                silentPaused = "已暂停 · 静音测试",
                silentConnecting = "正在连接 · 静音测试",
                silentPlaying = "静音测试中",
                globalMedia = "全局媒体",
                fallbackAudio = "音频",
                titlePaused = { "已暂停投送 · $it" },
                titleConnecting = { "正在连接 · $it" },
                titleCasting = { "正在投送 · $it" },
                preparing = "准备中…",
                singleError = { "$it · 已断开" },
                singleReconnecting = { "$it · 重连中" },
                singleConnecting = { "$it · 连接中" },
                multiConnecting = { "正在连接 $it 台设备" },
                multiPaused = { "$it 台设备" },
                multiSynced = { "$it 台设备同步播放" },
                multiAbnormal = { total, parts -> "$total 台设备 · $parts" },
                partReconnecting = { "${it} 台重连中" },
                partError = { "${it} 台已断开" },
                partConnecting = { "${it} 台连接中" },
                statusLabel = { it.labelZh },
                moreDevices = { "…等共 $it 台设备" },
                collapsedWithTapHint = { "$it · 点击打开控制卡" },
            )

            fun fromContext(context: Context): Strings = Strings(
                silentPaused = context.getString(R.string.cast_notif_silent_paused),
                silentConnecting = context.getString(R.string.cast_notif_silent_connecting),
                silentPlaying = context.getString(R.string.cast_notif_silent_playing),
                globalMedia = context.getString(R.string.cast_notif_global_media),
                fallbackAudio = context.getString(R.string.cast_notif_fallback_audio),
                titlePaused = { context.getString(R.string.cast_notif_title_paused, it) },
                titleConnecting = { context.getString(R.string.cast_notif_title_connecting, it) },
                titleCasting = { context.getString(R.string.cast_notif_title_casting, it) },
                preparing = context.getString(R.string.cast_notif_preparing),
                singleError = { context.getString(R.string.cast_notif_single_error, it) },
                singleReconnecting = { context.getString(R.string.cast_notif_single_reconnecting, it) },
                singleConnecting = { context.getString(R.string.cast_notif_single_connecting, it) },
                multiConnecting = { context.getString(R.string.cast_notif_multi_connecting, it) },
                multiPaused = { context.getString(R.string.cast_notif_multi_paused, it) },
                multiSynced = { context.getString(R.string.cast_notif_multi_synced, it) },
                multiAbnormal = { total, parts -> context.getString(R.string.cast_notif_multi_abnormal, total, parts) },
                partReconnecting = { context.getString(R.string.cast_notif_part_reconnecting, it) },
                partError = { context.getString(R.string.cast_notif_part_error, it) },
                partConnecting = { context.getString(R.string.cast_notif_part_connecting, it) },
                statusLabel = { st ->
                    when (st) {
                        ReceiverStatus.ERROR -> context.getString(R.string.cast_notif_status_error)
                        ReceiverStatus.RECONNECTING -> context.getString(R.string.cast_notif_status_reconnecting)
                        ReceiverStatus.CONNECTING -> context.getString(R.string.cast_notif_status_connecting)
                        ReceiverStatus.PAUSED -> context.getString(R.string.cast_notif_status_paused)
                        ReceiverStatus.PLAYING -> context.getString(R.string.cast_notif_status_playing)
                    }
                },
                moreDevices = { context.getString(R.string.cast_notif_more_devices, it) },
                collapsedWithTapHint = { context.getString(R.string.cast_notif_tap_to_control, it) },
            )
        }
    }

    data class ReceiverSnapshot(
        val ip: String,
        val displayName: String,
        val status: ReceiverStatus,
    )

    data class NotificationCopy(
        val title: String,
        val collapsedText: String,
        val expandedText: String?,
    )

    fun format(
        source: String,
        scopeMode: CaptureScopeMode,
        targetLabel: String,
        targetPackage: String,
        paused: Boolean,
        statsJson: String,
        fallbackIps: List<String> = emptyList(),
        deviceNamesByIp: Map<String, String> = emptyMap(),
        strings: Strings = Strings.DEFAULT_ZH,
    ): NotificationCopy {
        val receivers = parseReceivers(statsJson, fallbackIps, deviceNamesByIp, paused)
        val allConnecting = receivers.isNotEmpty() && receivers.all { it.status == ReceiverStatus.CONNECTING }

        val title = formatTitle(
            source = source,
            scopeMode = scopeMode,
            targetLabel = targetLabel,
            targetPackage = targetPackage,
            paused = paused,
            allConnecting = allConnecting,
            strings = strings,
        )
        val collapsed = formatCollapsed(receivers, paused, allConnecting, strings)
        val expanded = formatExpanded(receivers, strings)
        return NotificationCopy(
            title = title,
            collapsedText = collapsed,
            expandedText = expanded,
        )
    }

    private fun formatTitle(
        source: String,
        scopeMode: CaptureScopeMode,
        targetLabel: String,
        targetPackage: String,
        paused: Boolean,
        allConnecting: Boolean,
        strings: Strings,
    ): String {
        if (source != "app") {
            return when {
                paused -> strings.silentPaused
                allConnecting -> strings.silentConnecting
                else -> strings.silentPlaying
            }
        }
        val sourceName = when {
            scopeMode == CaptureScopeMode.GLOBAL_MEDIA -> strings.globalMedia
            targetLabel.isNotBlank() -> targetLabel.trim()
            targetPackage.isNotBlank() -> targetPackage.substringAfterLast('.').ifBlank { targetPackage }
            else -> strings.fallbackAudio
        }
        return when {
            paused -> strings.titlePaused(sourceName)
            allConnecting -> strings.titleConnecting(sourceName)
            else -> strings.titleCasting(sourceName)
        }
    }

    private fun formatCollapsed(
        receivers: List<ReceiverSnapshot>,
        paused: Boolean,
        allConnecting: Boolean,
        strings: Strings,
    ): String {
        if (receivers.isEmpty()) return strings.preparing
        if (receivers.size == 1) {
            val only = receivers.first()
            val base = when (only.status) {
                ReceiverStatus.ERROR -> strings.singleError(only.displayName)
                ReceiverStatus.RECONNECTING -> strings.singleReconnecting(only.displayName)
                ReceiverStatus.CONNECTING -> strings.singleConnecting(only.displayName)
                ReceiverStatus.PAUSED, ReceiverStatus.PLAYING -> only.displayName
            }
            return strings.collapsedWithTapHint(base)
        }
        val total = receivers.size
        val errorCount = receivers.count { it.status == ReceiverStatus.ERROR }
        val reconnectingCount = receivers.count { it.status == ReceiverStatus.RECONNECTING }
        val connectingCount = receivers.count { it.status == ReceiverStatus.CONNECTING }

        val abnormalParts = buildList {
            if (reconnectingCount > 0) add(strings.partReconnecting(reconnectingCount))
            if (errorCount > 0) add(strings.partError(errorCount))
        }
        if (abnormalParts.size > 1) {
            return strings.multiAbnormal(total, abnormalParts.joinToString(" · "))
        }
        val base = when {
            allConnecting -> strings.multiConnecting(total)
            abnormalParts.isNotEmpty() -> strings.multiAbnormal(total, abnormalParts.joinToString(" · "))
            paused -> strings.multiPaused(total)
            connectingCount > 0 -> strings.multiAbnormal(total, strings.partConnecting(connectingCount))
            else -> strings.multiSynced(total)
        }
        return strings.collapsedWithTapHint(base)
    }

    private fun formatExpanded(receivers: List<ReceiverSnapshot>, strings: Strings): String? {
        if (receivers.size < 2) return null
        val ordered = receivers.sortedBy { it.status.sortPriority }
        val lines = ordered.take(MAX_EXPANDED_DEVICES).map { item ->
            "• ${item.displayName} · ${strings.statusLabel(item.status)}"
        }
        return if (ordered.size > MAX_EXPANDED_DEVICES) {
            (lines + strings.moreDevices(ordered.size)).joinToString("\n")
        } else {
            lines.joinToString("\n")
        }
    }

    private fun parseReceivers(
        statsJson: String,
        fallbackIps: List<String>,
        deviceNamesByIp: Map<String, String>,
        paused: Boolean,
    ): List<ReceiverSnapshot> {
        val parsed = runCatching {
            if (statsJson.isBlank()) return@runCatching emptyList<ReceiverSnapshot>()
            val root = JSONObject(statsJson)
            val arr = root.optJSONArray("receivers") ?: return@runCatching emptyList<ReceiverSnapshot>()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val ip = obj.optString("ip").trim()
                    if (ip.isEmpty()) continue
                    val status = resolveStatus(obj, paused)
                    val displayName = resolveDisplayName(ip, deviceNamesByIp)
                    add(ReceiverSnapshot(ip = ip, displayName = displayName, status = status))
                }
            }
        }.getOrDefault(emptyList())

        if (parsed.isNotEmpty()) return parsed

        return fallbackIps
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { ip ->
                ReceiverSnapshot(
                    ip = ip,
                    displayName = resolveDisplayName(ip, deviceNamesByIp),
                    status = if (paused) ReceiverStatus.PAUSED else ReceiverStatus.CONNECTING,
                )
            }
    }

    private fun resolveDisplayName(ip: String, deviceNamesByIp: Map<String, String>): String {
        val cached = deviceNamesByIp[ip]?.trim().orEmpty()
        return if (cached.isNotEmpty()) cached else ip
    }

    private fun resolveStatus(receiver: JSONObject, paused: Boolean): ReceiverStatus {
        val uiState = receiver.optString("ui_state", "").trim().lowercase()
        if (uiState.isNotEmpty()) {
            return when (uiState) {
                "error" -> ReceiverStatus.ERROR
                "reconnecting" -> ReceiverStatus.RECONNECTING
                "connecting" -> ReceiverStatus.CONNECTING
                "streaming" -> if (paused || receiver.optInt("state", -1) == 3) {
                    ReceiverStatus.PAUSED
                } else {
                    ReceiverStatus.PLAYING
                }
                else -> if (paused) ReceiverStatus.PAUSED else ReceiverStatus.CONNECTING
            }
        }

        val hasError = !receiver.isNull("error") && receiver.optString("error").isNotBlank()
        val phase = receiver.optString("phase", "").trim().lowercase()
        val state = receiver.optInt("state", -1)
        return when {
            phase == "reconnecting" -> ReceiverStatus.RECONNECTING
            hasError || phase == "failed" || phase == "error" -> ReceiverStatus.ERROR
            phase == "connecting" || phase == "starting" -> ReceiverStatus.CONNECTING
            paused || state == 3 -> ReceiverStatus.PAUSED
            state == 2 || phase == "streaming" || phase == "playing" -> ReceiverStatus.PLAYING
            else -> ReceiverStatus.CONNECTING
        }
    }
}
