package com.miaocast.app.compat

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import com.miaocast.app.ui.AppLanguage

/**
 * Android 13+ (API 33+) 系统分应用语言（Per-App Language）同步封装层。
 * 隔离在 `compat/` 内以满足 `minSdk = 29` 与 `NewApi` fatal 门禁。
 */
object LocaleCompat {
    fun syncSystemAppLocales(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            Api33.setApplicationLocales(context, language)
        }
    }

    @RequiresApi(33)
    private object Api33 {
        fun setApplicationLocales(context: Context, language: AppLanguage) {
            runCatching {
                val lm = context.getSystemService(LocaleManager::class.java) ?: return
                val list = if (language == AppLanguage.SYSTEM) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(language.languageTag)
                }
                lm.applicationLocales = list
            }
        }
    }
}
