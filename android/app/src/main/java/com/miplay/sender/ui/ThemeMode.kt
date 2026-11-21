package com.miplay.sender.ui

import android.content.Context
import android.content.res.Configuration

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

    fun isDark(context: Context): Boolean =
        ThemeMode.resolveIsDark(themeMode, ThemeMode.isSystemNight(context))

    companion object {
        private const val PREFS = "miplay_ui_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_KEEP_LOCAL = "keep_local_output"
        private const val KEY_AUTO_DETECT_SOURCE = "auto_detect_audio_source"
        private const val KEY_SHOW_ALL = "show_all_discovered"
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_DELAY_MS = "delay_ms"
        private const val KEY_REF_FUNC = "ref_function"
        private const val KEY_AUTH_MODE = "auth_mode"
    }
}
