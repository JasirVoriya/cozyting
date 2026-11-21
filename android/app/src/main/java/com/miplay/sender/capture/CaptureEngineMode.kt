package com.miplay.sender.capture

/**
 * Android 音频采集引擎模式（仅 Android 端生效；iOS 端由 ReplayKit 承载）。
 */
enum class CaptureEngineMode(val id: String, val labelZh: String) {
    /** 默认标准模式：MediaProjection + AudioPlaybackCapture（API 29–36，零前置配置）。 */
    STANDARD("standard", "标准投屏模式"),

    /** 内置无线调试无感模式：App 内置无线 ADB 点火 uid=2000 采集进程（API 30+，零外部 App 依赖）。 */
    WIRELESS_DEBUG("wireless_debug", "无感模式（无线调试）"),
    ;

    companion object {
        fun fromId(raw: String?): CaptureEngineMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: STANDARD

        /**
         * 按 Android 系统版本自动决定默认采集引擎：
         * - Android 13+ (API 33+)：原生支持单 App 隔离无感采集与真单 App 本机静音 → 默认 [WIRELESS_DEBUG]；
         * - Android 10–12 (API 29–32)：API 29 不支持 shell 抓音，API 30–32 仅支持全局 REMOTE_SUBMIX
         *   （为守住 Fail-Closed 单 App 默认红线，不自动默认全局捕获）→ 默认 [STANDARD]，用户可在设置中显式切换。
         */
        fun defaultForSdkInt(sdkInt: Int): CaptureEngineMode =
            if (CaptureEngineCapabilities.forSdkInt(sdkInt).supportsShellSingleAppPolicy) {
                WIRELESS_DEBUG
            } else {
                STANDARD
            }
    }
}

/**
 * 无感采集作用域模式（遵守 Fail-Closed 红线：绝不将单 App 请求静默降级为全局媒体）。
 */
enum class CaptureScopeMode(val id: String, val labelZh: String) {
    /** 严格单 App UID 隔离（Android 13+ AudioPolicy RULE_MATCH_UID）。 */
    SINGLE_APP("single_app", "单 App 隔离"),

    /** 显式全局媒体捕获（Android 13+ 媒体 USAGE 路由 / Android 11–12 REMOTE_SUBMIX）。 */
    GLOBAL_MEDIA("global_media", "全局媒体（显式）"),
    ;

    companion object {
        fun fromId(raw: String?): CaptureScopeMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: SINGLE_APP
    }
}

/**
 * 按 Android API 级别严格分层的采集能力声明（与 docs/ANDROID_WIRELESS_DEBUG_CAPTURE_DESIGN.md 对齐）。
 */
data class CaptureEngineCapabilities(
    val sdkInt: Int,
    val supportsWirelessDebugEngine: Boolean,
    val supportsShellSingleAppPolicy: Boolean,
    val supportsShellGlobalSubmix: Boolean,
    val supportsTrueSingleAppLocalMute: Boolean,
) {
    val tierSummaryZh: String
        get() = when {
            supportsShellSingleAppPolicy ->
                "Android 13+（API $sdkInt）：免投屏弹窗 · 免「正在投屏」提示 · 支持单 App 隔离与真单 App 本机静音"
            supportsShellGlobalSubmix ->
                "Android 11–12（API $sdkInt）：免投屏弹窗 · 免「正在投屏」提示 · 仅支持显式全局媒体（不支持单 App 隔离）"
            else ->
                "Android 10（API $sdkInt）：系统不支持原生无线调试与 shell 音频捕获，仅可使用标准投屏模式"
        }

    sealed class ValidationResult {
        data class Allowed(
            val effectiveEngine: CaptureEngineMode,
            val effectiveScope: CaptureScopeMode,
            val usesTrueSingleAppMute: Boolean,
        ) : ValidationResult()

        data class Rejected(val messageZh: String) : ValidationResult()
    }

    /**
     * 严格 Fail-Closed 校验：任何能力不满足一律显式拒绝并给出可执行提示，绝不静默退化为全局捕获。
     */
    fun validate(
        requestedEngine: CaptureEngineMode,
        requestedScope: CaptureScopeMode,
        hasPairedCertificate: Boolean,
        keepLocalOutput: Boolean,
    ): ValidationResult {
        if (requestedEngine == CaptureEngineMode.STANDARD) {
            return ValidationResult.Allowed(
                effectiveEngine = CaptureEngineMode.STANDARD,
                effectiveScope = CaptureScopeMode.SINGLE_APP,
                usesTrueSingleAppMute = false,
            )
        }
        if (!supportsWirelessDebugEngine) {
            return ValidationResult.Rejected(
                "当前系统为 Android 10（API $sdkInt），不支持内置无线调试无感采集，请在设置中切回「标准投屏模式」",
            )
        }
        if (!hasPairedCertificate) {
            return ValidationResult.Rejected(
                "内置无线调试尚未完成首次配对，请前往「设置 → 采集引擎」开启无线调试并输入 6 位配对码",
            )
        }
        if (requestedScope == CaptureScopeMode.SINGLE_APP && !supportsShellSingleAppPolicy) {
            return ValidationResult.Rejected(
                "Android 11–12（API $sdkInt）不支持 shell 单 App 隔离（Fail-Closed 已拒绝静默退化）；请在设置中显式切换为「全局媒体」或改用「标准投屏模式」",
            )
        }
        val trueSingleAppMute = requestedScope == CaptureScopeMode.SINGLE_APP &&
            supportsTrueSingleAppLocalMute &&
            !keepLocalOutput
        return ValidationResult.Allowed(
            effectiveEngine = CaptureEngineMode.WIRELESS_DEBUG,
            effectiveScope = requestedScope,
            usesTrueSingleAppMute = trueSingleAppMute,
        )
    }

    companion object {
        fun forSdkInt(sdkInt: Int): CaptureEngineCapabilities = CaptureEngineCapabilities(
            sdkInt = sdkInt,
            supportsWirelessDebugEngine = sdkInt >= 30,
            supportsShellSingleAppPolicy = sdkInt >= 33,
            supportsShellGlobalSubmix = sdkInt >= 30,
            supportsTrueSingleAppLocalMute = sdkInt >= 33,
        )
    }
}
