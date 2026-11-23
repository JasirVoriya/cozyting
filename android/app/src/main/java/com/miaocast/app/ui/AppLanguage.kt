package com.miaocast.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.miaocast.app.R
import java.util.Locale

/**
 * 跨端应用内语言模式（与 `design/UI_SPEC.md` 及 iOS `Localizable.xcstrings` 语种集合对齐）。
 *
 * - `SYSTEM`：跟随系统语言；
 * - 具体语种始终以其自身母语名称展示（简体中文 / English / 日本語），避免误选其他语种后无法辨认切回。
 */
enum class AppLanguage(
    val id: String,
    val languageTag: String,
    val nativeLabel: String?,
) {
    SYSTEM("system", "", null),
    ZH_CN("zh-CN", "zh-CN", "简体中文"),
    EN("en", "en", "English"),
    JA("ja", "ja", "日本語"),
    ;

    fun displayLabel(context: Context): String =
        nativeLabel ?: context.getString(R.string.lang_system)

    companion object {
        fun fromId(raw: String?): AppLanguage {
            val norm = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.id.equals(norm, ignoreCase = true) ||
                    (it.languageTag.isNotEmpty() && it.languageTag.equals(norm, ignoreCase = true))
            } ?: SYSTEM
        }
    }
}

/**
 * 零外部依赖的 Android API 29–36 应用内语言切换管理器：
 * - 在 Activity / Service 的 `attachBaseContext()` 挂载 [wrapContext]；
 * - 在 BroadcastReceiver / Notification / Overlay 场景通过 [localizedContext] 获取已本地化的 Context。
 */
object AppLocaleManager {
    private const val PREFS_NAME = "miaocast_ui_settings"
    private const val KEY_APP_LANGUAGE = "app_language"

    fun readSelectedLanguage(context: Context): AppLanguage {
        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return AppLanguage.SYSTEM
        return AppLanguage.fromId(prefs.getString(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.id))
    }

    fun saveSelectedLanguage(context: Context, language: AppLanguage) {
        val target = context.applicationContext ?: context
        target.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, language.id)
            .commit()
    }

    fun wrapContext(base: Context): Context {
        val lang = readSelectedLanguage(base)
        if (lang == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(lang.languageTag)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    fun localizedContext(context: Context): Context {
        val app = context.applicationContext ?: context
        return wrapContext(app)
    }

    fun activeLocale(context: Context): Locale {
        val lang = readSelectedLanguage(context)
        if (lang != AppLanguage.SYSTEM) {
            return Locale.forLanguageTag(lang.languageTag)
        }
        val locales = context.resources.configuration.locales
        return if (!locales.isEmpty) locales[0] else Locale.getDefault()
    }
}
