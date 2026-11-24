package com.cozyting.app.ui

/**
 * 严格对应仓库 `design/tokens.json`（第一版样式 B · 克制低饱和 + 高对比选中卡）。
 * Android 与未来 iOS SwiftUI 共用同一套语义角色命名与色值；使用纯位运算构造 ARGB，
 * 既与 `android.graphics.Color` 字节序完全一致，又可在纯 JVM 单元测试中直接验证。
 */
data class UiPalette(
    val isDark: Boolean,
    val bgCanvas: Int,
    val bgAmbientTop: Int,
    val bgAmbientBottom: Int,
    val panelBg: Int,
    val panelBorder: Int,
    val rowIdleBg: Int,
    val rowIdleBorder: Int,
    val rowSelectedBg: Int,
    val rowSelectedBorder: Int,
    val onSelectedPrimary: Int,
    val onSelectedSecondary: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val accent: Int,
    val accentBright: Int,
    val accentSoftBg: Int,
    val selectedPillBg: Int,
    val selectedPillText: Int,
    val controlSurface: Int,
    val trackIdle: Int,
    val trackOnSelected: Int,
    val ripple: Int,
    val rippleOnSelected: Int,
    val statusSuccess: Int,
    val statusWarning: Int,
    val statusError: Int,
) {
    companion object {
        const val RADIUS_PANEL_DP = 24
        const val RADIUS_CARD_DP = 16
        const val RADIUS_CONTROL_DP = 12
        const val RADIUS_PILL_DP = 999

        private fun rgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        fun dark(): UiPalette = UiPalette(
            isDark = true,
            bgCanvas = rgb(14, 17, 23),
            bgAmbientTop = rgb(23, 32, 48),
            bgAmbientBottom = rgb(11, 14, 20),
            panelBg = argb(230, 21, 26, 35),
            panelBorder = argb(26, 255, 255, 255),
            rowIdleBg = argb(16, 255, 255, 255),
            rowIdleBorder = argb(20, 255, 255, 255),
            rowSelectedBg = rgb(234, 240, 247),
            rowSelectedBorder = rgb(234, 240, 247),
            onSelectedPrimary = rgb(18, 22, 30),
            onSelectedSecondary = rgb(75, 85, 99),
            textPrimary = rgb(245, 247, 250),
            textSecondary = rgb(148, 163, 184),
            textTertiary = rgb(100, 116, 139),
            accent = rgb(37, 99, 235),
            accentBright = rgb(76, 141, 255),
            accentSoftBg = argb(46, 76, 141, 255),
            selectedPillBg = argb(36, 37, 99, 235),
            selectedPillText = rgb(29, 78, 216),
            controlSurface = argb(24, 255, 255, 255),
            trackIdle = argb(46, 255, 255, 255),
            trackOnSelected = argb(42, 18, 22, 30),
            ripple = argb(36, 255, 255, 255),
            rippleOnSelected = argb(26, 18, 22, 30),
            statusSuccess = rgb(52, 211, 153),
            statusWarning = rgb(251, 191, 36),
            statusError = rgb(248, 113, 113),
        )

        fun light(): UiPalette = UiPalette(
            isDark = false,
            bgCanvas = rgb(244, 246, 250),
            bgAmbientTop = rgb(228, 236, 247),
            bgAmbientBottom = rgb(244, 246, 250),
            panelBg = argb(240, 255, 255, 255),
            panelBorder = argb(20, 15, 23, 42),
            rowIdleBg = rgb(255, 255, 255),
            rowIdleBorder = argb(18, 15, 23, 42),
            rowSelectedBg = rgb(235, 243, 255),
            rowSelectedBorder = rgb(37, 99, 235),
            onSelectedPrimary = rgb(15, 23, 42),
            onSelectedSecondary = rgb(59, 89, 152),
            textPrimary = rgb(15, 23, 42),
            textSecondary = rgb(71, 85, 105),
            textTertiary = rgb(148, 163, 184),
            accent = rgb(37, 99, 235),
            accentBright = rgb(37, 99, 235),
            accentSoftBg = argb(28, 37, 99, 235),
            selectedPillBg = argb(32, 37, 99, 235),
            selectedPillText = rgb(29, 78, 216),
            controlSurface = argb(15, 15, 23, 42),
            trackIdle = argb(32, 15, 23, 42),
            trackOnSelected = argb(56, 37, 99, 235),
            ripple = argb(22, 15, 23, 42),
            rippleOnSelected = argb(30, 37, 99, 235),
            statusSuccess = rgb(22, 163, 74),
            statusWarning = rgb(217, 119, 6),
            statusError = rgb(220, 38, 38),
        )

        fun forMode(isDark: Boolean): UiPalette = if (isDark) dark() else light()
    }
}
