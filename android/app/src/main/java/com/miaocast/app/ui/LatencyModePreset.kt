package com.miaocast.app.ui

import com.miaocast.app.R

/**
 * 顶栏播放延迟模式档位定义与单一事实来源解析器（仅由 `settingsStore.delayMs` 推导，避免双状态漂移）。
 *
 * 与 L1 Rust `core/src/latency.rs::startup_runway_aus_for_delay_ms` 保持严格对齐：
 * - `标准`（0 = 自动 5GHz 800ms / 2.4GHz 1000ms）→ 14 AU（≈299ms）
 * - `均衡`（400ms，双音箱同步实测稳定）→ 8 AU（≈171ms）
 * - `低延迟`（250ms，单音箱 5GHz 强信号推荐）→ 5 AU（≈107ms）
 * - `极速`（150ms，实验性极低缓冲）→ 3 AU（≈64ms）
 * - 其他正整数 → `自定义`（顶栏显示 `${delayMs}ms`，下拉框动态附加一行已勾选的自定义项）
 */
enum class LatencyModeTone {
    NEUTRAL,
    ACCENT,
    WARNING,
}

enum class LatencyModePreset(
    val delayMs: Int,
    val nameZh: String,
    val shortMsZh: String,
    val fullMsZh: String,
    val runwaySummaryZh: String,
    val detailZh: String,
    val tone: LatencyModeTone,
    val iconRes: Int,
) {
    STANDARD(
        delayMs = 0,
        nameZh = "标准",
        shortMsZh = "自动",
        fullMsZh = "5GHz 800ms / 2.4GHz 1000ms",
        runwaySummaryZh = "14 AU（≈299ms）",
        detailZh = "官方默认基线，抗抖动能力最强，适合穿墙、2.4GHz 或多音箱长时间听歌。",
        tone = LatencyModeTone.NEUTRAL,
        iconRes = R.drawable.ic_ui_latency_standard,
    ),
    BALANCED(
        delayMs = 400,
        nameZh = "均衡",
        shortMsZh = "400ms",
        fullMsZh = "400ms",
        runwaySummaryZh = "8 AU（≈171ms）",
        detailZh = "日常主力档：双音箱同步稳定可用，切歌/暂停响应较默认快约一倍。",
        tone = LatencyModeTone.ACCENT,
        iconRes = R.drawable.ic_ui_latency_balanced,
    ),
    LOW(
        delayMs = 250,
        nameZh = "低延迟",
        shortMsZh = "250ms",
        fullMsZh = "250ms",
        runwaySummaryZh = "5 AU（≈107ms）",
        detailZh = "单音箱 5GHz 强信号主力档，切歌与拖动进度条非常跟手。",
        tone = LatencyModeTone.ACCENT,
        iconRes = R.drawable.ic_ui_latency_low,
    ),
    ULTRA(
        delayMs = 150,
        nameZh = "极速",
        shortMsZh = "150ms",
        fullMsZh = "150ms",
        runwaySummaryZh = "3 AU（≈64ms）",
        detailZh = "实验性极低缓冲：仅建议同房间 5GHz 单音箱使用，弱网或多箱易出现卡顿。",
        tone = LatencyModeTone.WARNING,
        iconRes = R.drawable.ic_ui_latency_ultra,
    ),
    ;

    companion object {
        const val CUSTOM_LOW_WARNING_THRESHOLD_MS = 250

        fun resolve(delayMs: Int): LatencyModeSelection {
            if (delayMs <= 0) return LatencyModeSelection.Preset(STANDARD)
            val matched = entries.firstOrNull { it.delayMs == delayMs }
            return if (matched != null) {
                LatencyModeSelection.Preset(matched)
            } else {
                LatencyModeSelection.Custom(delayMs)
            }
        }
    }
}

sealed class LatencyModeSelection {
    abstract val capsuleLabelZh: String
    abstract val tone: LatencyModeTone
    abstract val iconRes: Int

    data class Preset(val preset: LatencyModePreset) : LatencyModeSelection() {
        override val capsuleLabelZh: String get() = preset.nameZh
        override val tone: LatencyModeTone get() = preset.tone
        override val iconRes: Int get() = preset.iconRes
    }

    data class Custom(val delayMs: Int) : LatencyModeSelection() {
        override val capsuleLabelZh: String get() = "${delayMs}ms"
        override val tone: LatencyModeTone
            get() = if (delayMs < LatencyModePreset.CUSTOM_LOW_WARNING_THRESHOLD_MS) {
                LatencyModeTone.WARNING
            } else {
                LatencyModeTone.ACCENT
            }
        override val iconRes: Int get() = R.drawable.ic_ui_latency_custom
    }
}
