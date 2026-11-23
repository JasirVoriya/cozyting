package com.miaocast.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.miaocast.app.capture.CaptureEngineMode
import com.miaocast.app.capture.CaptureScopeMode
import com.miaocast.app.update.UpdateChannel

/**
 * 跨端主题模式（与 `design/tokens.json` 和后续 iOS SwiftUI 对齐）。
 */
enum class ThemeMode(val id: String, val labelZh: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    companion object {
        fun fromId(raw: String?): ThemeMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: SYSTEM

        fun resolveIsDark(mode: ThemeMode, systemNight: Boolean): Boolean = when (mode) {
            SYSTEM -> systemNight
            LIGHT -> false
            DARK -> true
        }

        fun isSystemNight(context: Context): Boolean {
            val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return mask == Configuration.UI_MODE_NIGHT_YES
        }

        /** 顶栏一键明暗快切：当前深色则切浅色，当前浅色则切深色。 */
        fun quickToggleTarget(currentIsDark: Boolean): ThemeMode =
            if (currentIsDark) LIGHT else DARK
    }
}

/**
 * 应用级 UI 与投送偏好存储（供 MainActivity / SettingsActivity / AppPickerActivity 共享，
 * 并在不干扰 handleAutomation Intent 覆写的前提下保留用户选择）。
 */
class PrefsCastSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.fromId(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.id))
        set(value) = prefs.edit().putString(KEY_THEME, value.id).apply()

    var keepLocalOutput: Boolean
        get() = prefs.getBoolean(KEY_KEEP_LOCAL, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_LOCAL, value).apply()

    var autoDetectAudioSource: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT_SOURCE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DETECT_SOURCE, value).apply()

    /** 是否开启高音量保护（默认开启：从安全区跨过上限时弹窗二次确认）。 */
    var volumeGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_GUARD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_GUARD_ENABLED, value).apply()

    /** 安全音量上限百分比（默认 60%，合法范围 30..90）。 */
    var volumeGuardSafeLimit: Int
        get() = VolumeSafetyGuard.normalizeSafeLimit(
            prefs.getInt(KEY_VOLUME_GUARD_SAFE_LIMIT, VolumeSafetyGuard.DEFAULT_SAFE_LIMIT),
        )
        set(value) = prefs.edit()
            .putInt(KEY_VOLUME_GUARD_SAFE_LIMIT, VolumeSafetyGuard.normalizeSafeLimit(value))
            .apply()

    fun volumeGuardPolicy(): VolumeSafetyGuard.Policy =
        VolumeSafetyGuard.Policy(enabled = volumeGuardEnabled, safeLimit = volumeGuardSafeLimit)

    var showAllDiscovered: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ALL, value).apply()

    /** "app" | "silent" */
    var sourceMode: String
        get() = prefs.getString(KEY_SOURCE_MODE, "app").orEmpty().ifBlank { "app" }
        set(value) = prefs.edit().putString(KEY_SOURCE_MODE, if (value == "silent") "silent" else "app").apply()

    /** 0 表示自动（5GHz 800ms / 其他 1000ms）。 */
    var delayMs: Int
        get() = prefs.getInt(KEY_DELAY_MS, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_DELAY_MS, value.coerceAtLeast(0)).apply()

    /** "" (自动) | "single_room" | "multi_room" | "stereo" */
    var refFunction: String
        get() = prefs.getString(KEY_REF_FUNC, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_REF_FUNC, value).apply()

    /** 0=auto, 1=legacy, 2=safety */
    var authMode: Int
        get() = prefs.getInt(KEY_AUTH_MODE, 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_AUTH_MODE, value.coerceIn(0, 2)).apply()

    /**
     * Android 采集引擎模式：未手动设置时按系统版本自动选择默认模式
     * （Android 13+ 默认无感模式；Android 10–12 默认标准投屏模式）。
     */
    var captureEngineMode: CaptureEngineMode
        get() {
            val raw = prefs.getString(KEY_CAPTURE_ENGINE, null)
            return if (raw.isNullOrBlank()) {
                CaptureEngineMode.defaultForSdkInt(Build.VERSION.SDK_INT)
            } else {
                CaptureEngineMode.fromId(raw)
            }
        }
        set(value) = prefs.edit().putString(KEY_CAPTURE_ENGINE, value.id).apply()

    /** 无感模式作用域：single_app（默认单 App 隔离）| global_media（显式全局媒体）。 */
    var captureScopeMode: CaptureScopeMode
        get() = CaptureScopeMode.fromId(prefs.getString(KEY_CAPTURE_SCOPE, CaptureScopeMode.SINGLE_APP.id))
        set(value) = prefs.edit().putString(KEY_CAPTURE_SCOPE, value.id).apply()

    /** 是否在启动时每 24 小时自动检查一次公开更新清单（默认开启，可一键关闭实现 100% 零外网请求）。 */
    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_UPDATE_AUTO_CHECK, value).apply()

    /** 用户显式选择的更新通道；留空表示跟随当前安装包类型（预览版跟预览、正式版跟稳定）。 */
    var updateChannelRaw: String
        get() = prefs.getString(KEY_UPDATE_CHANNEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_UPDATE_CHANNEL, value.trim()).apply()

    fun resolvedUpdateChannel(currentVersionName: String): UpdateChannel =
        UpdateChannel.fromId(updateChannelRaw) ?: UpdateChannel.defaultForCurrentVersion(currentVersionName)

    /** 用户点击「跳过此版本」记录的 tag（如 v0.9.8）。 */
    var ignoredUpdateTag: String
        get() = prefs.getString(KEY_UPDATE_IGNORED_TAG, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_UPDATE_IGNORED_TAG, value.trim()).apply()

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_UPDATE_LAST_CHECK_MS, 0L).coerceAtLeast(0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_LAST_CHECK_MS, value.coerceAtLeast(0L)).apply()

    var updateEtag: String
        get() = prefs.getString(KEY_UPDATE_ETAG, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_UPDATE_ETAG, value).apply()

    var cachedUpdateManifestJson: String
        get() = prefs.getString(KEY_UPDATE_CACHED_JSON, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_UPDATE_CACHED_JSON, value).apply()

    fun isDark(context: Context): Boolean =
        ThemeMode.resolveIsDark(themeMode, ThemeMode.isSystemNight(context))

    companion object {
        private const val PREFS = "miaocast_ui_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_KEEP_LOCAL = "keep_local_output"
        private const val KEY_AUTO_DETECT_SOURCE = "auto_detect_audio_source"
        private const val KEY_VOLUME_GUARD_ENABLED = "volume_guard_enabled"
        private const val KEY_VOLUME_GUARD_SAFE_LIMIT = "volume_guard_safe_limit"
        private const val KEY_SHOW_ALL = "show_all_discovered"
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_DELAY_MS = "delay_ms"
        private const val KEY_REF_FUNC = "ref_function"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_CAPTURE_ENGINE = "capture_engine_mode"
        private const val KEY_CAPTURE_SCOPE = "capture_scope_mode"
        private const val KEY_UPDATE_AUTO_CHECK = "update_auto_check"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_UPDATE_IGNORED_TAG = "update_ignored_tag"
        private const val KEY_UPDATE_LAST_CHECK_MS = "update_last_check_ms"
        private const val KEY_UPDATE_ETAG = "update_etag"
        private const val KEY_UPDATE_CACHED_JSON = "update_cached_manifest_json"
    }
}
