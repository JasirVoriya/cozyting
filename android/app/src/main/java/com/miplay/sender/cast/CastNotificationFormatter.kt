package com.miplay.sender.cast

import com.miplay.sender.capture.CaptureScopeMode
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
        )
        val collapsed = formatCollapsed(receivers, paused, allConnecting)
        val expanded = formatExpanded(receivers)
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
    ): String {
        if (source != "app") {
            return when {
                paused -> "已暂停 · 静音测试"
                allConnecting -> "正在连接 · 静音测试"
                else -> "静音测试中"
            }
        }
        val sourceName = when {
            scopeMode == CaptureScopeMode.GLOBAL_MEDIA -> "全局媒体"
            targetLabel.isNotBlank() -> targetLabel.trim()
            targetPackage.isNotBlank() -> targetPackage.substringAfterLast('.').ifBlank { targetPackage }
            else -> "音频"
        }
        return when {
            paused -> "已暂停投送 · $sourceName"
            allConnecting -> "正在连接 · $sourceName"
            else -> "正在投送 · $sourceName"
        }
    }

    private fun formatCollapsed(
        receivers: List<ReceiverSnapshot>,
        paused: Boolean,
        allConnecting: Boolean,
    ): String {
        if (receivers.isEmpty()) return "准备中…"
        if (receivers.size == 1) {
            val only = receivers.first()
            return when (only.status) {
                ReceiverStatus.ERROR -> "${only.displayName} · 已断开"
                ReceiverStatus.RECONNECTING -> "${only.displayName} · 重连中"
                ReceiverStatus.CONNECTING -> "${only.displayName} · 连接中"
                ReceiverStatus.PAUSED, ReceiverStatus.PLAYING -> only.displayName
            }
        }
        val total = receivers.size
        val errorCount = receivers.count { it.status == ReceiverStatus.ERROR }
        val reconnectingCount = receivers.count { it.status == ReceiverStatus.RECONNECTING }
        val connectingCount = receivers.count { it.status == ReceiverStatus.CONNECTING }

        val abnormalParts = buildList {
            if (reconnectingCount > 0) add("${reconnectingCount} 台重连中")
            if (errorCount > 0) add("${errorCount} 台已断开")
        }
        return when {
            allConnecting -> "正在连接 $total 台设备"
            abnormalParts.isNotEmpty() -> "$total 台设备 · ${abnormalParts.joinToString(" · ")}"
            paused -> "$total 台设备"
            connectingCount > 0 -> "$total 台设备 · ${connectingCount} 台连接中"
            else -> "$total 台设备同步播放"
        }
    }

    private fun formatExpanded(receivers: List<ReceiverSnapshot>): String? {
        if (receivers.size < 2) return null
        val ordered = receivers.sortedBy { it.status.sortPriority }
        val lines = ordered.take(MAX_EXPANDED_DEVICES).map { item ->
            "• ${item.displayName} · ${item.status.labelZh}"
        }
        return if (ordered.size > MAX_EXPANDED_DEVICES) {
            (lines + "…等共 ${ordered.size} 台设备").joinToString("\n")
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
