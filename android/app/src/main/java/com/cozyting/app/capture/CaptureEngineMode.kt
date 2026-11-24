package com.cozyting.app.capture

import android.content.Context
import com.cozyting.app.R

/**
 * Android 音频采集引擎模式（仅 Android 端生效；iOS 端由 ReplayKit 承载）。
 */
enum class CaptureEngineMode(val id: String, val labelZh: String, val labelRes: Int) {
    /** 默认标准模式：MediaProjection + AudioPlaybackCapture（API 29–36，零前置配置）。 */
    STANDARD("standard", "标准投屏模式", R.string.engine_standard),

    /** 内置无线调试无感模式：App 内置无线 ADB 点火 uid=2000 采集进程（API 30+，零外部 App 依赖）。 */
    WIRELESS_DEBUG("wireless_debug", "无感模式（无线调试）", R.string.engine_wireless_debug),
    ;

    fun label(context: Context): String = context.getString(labelRes)

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
enum class CaptureScopeMode(val id: String, val labelZh: String, val labelRes: Int) {
    /** 严格单 App UID 隔离（Android 13+ AudioPolicy RULE_MATCH_UID）。 */
    SINGLE_APP("single_app", "单 App 隔离", R.string.scope_single_app),

    /** 显式全局媒体捕获（Android 13+ 媒体 USAGE 路由 / Android 11–12 REMOTE_SUBMIX）。 */
    GLOBAL_MEDIA("global_media", "全局媒体（显式）", R.string.scope_global_media),
    ;

    fun label(context: Context): String = context.getString(labelRes)

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
    enum class RejectionCode {
        API29_UNSUPPORTED,
        UNPAIRED,
        SINGLE_APP_API30_UNSUPPORTED,
    }

    val tierSummaryZh: String
        get() = when {
            supportsShellSingleAppPolicy ->
                "Android 13+：免投屏弹窗 · 支持单 App 隔离与独立静音"
            supportsShellGlobalSubmix ->
                "Android 11–12：免投屏弹窗 · 仅支持显式全局媒体（不支持单 App 隔离）"
            else ->
                "Android 10：不支持无线调试采集，请使用标准投屏模式"
        }

    fun tierSummary(context: Context): String = when {
        supportsShellSingleAppPolicy -> context.getString(R.string.engine_tier_api33)
        supportsShellGlobalSubmix -> context.getString(R.string.engine_tier_api30)
        else -> context.getString(R.string.engine_tier_api29)
    }

    sealed class ValidationResult {
        data class Allowed(
            val effectiveEngine: CaptureEngineMode,
            val effectiveScope: CaptureScopeMode,
            val usesTrueSingleAppMute: Boolean,
        ) : ValidationResult()

        data class Rejected(
            val messageZh: String,
            val code: RejectionCode = RejectionCode.UNPAIRED,
            val sdkInt: Int = 0,
        ) : ValidationResult() {
            fun message(context: Context): String = when (code) {
                RejectionCode.API29_UNSUPPORTED -> context.getString(R.string.engine_reject_api29, sdkInt)
                RejectionCode.UNPAIRED -> context.getString(R.string.engine_reject_unpaired)
                RejectionCode.SINGLE_APP_API30_UNSUPPORTED ->
                    context.getString(R.string.engine_reject_single_app_api30, sdkInt)
            }
        }
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
                messageZh = "当前系统为 Android 10（API $sdkInt），不支持内置无线调试无感采集，请在设置中切回「标准投屏模式」",
                code = RejectionCode.API29_UNSUPPORTED,
                sdkInt = sdkInt,
            )
        }
        if (!hasPairedCertificate) {
            return ValidationResult.Rejected(
                messageZh = "内置无线调试尚未完成首次配对，请按 5 步向导开启悬浮窗与无线调试，并在顶部浮动数字键盘输入 6 位配对码",
                code = RejectionCode.UNPAIRED,
                sdkInt = sdkInt,
            )
        }
        if (requestedScope == CaptureScopeMode.SINGLE_APP && !supportsShellSingleAppPolicy) {
            return ValidationResult.Rejected(
                messageZh = "Android 11–12（API $sdkInt）不支持 shell 单 App 隔离（Fail-Closed 已拒绝静默退化）；请在设置中显式切换为「全局媒体」或改用「标准投屏模式」",
                code = RejectionCode.SINGLE_APP_API30_UNSUPPORTED,
                sdkInt = sdkInt,
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
