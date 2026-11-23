package com.miaocast.app

import android.content.Intent

/**
 * 把自动化 Intent / UI spinner 解析为 JNI setAuthMode 取值：0=auto，1=legacy，2=safety。
 * 字符串 `--es auth safety` 优先于整型 `--ei auth 2`。
 */
object AuthModeResolve {
    fun fromString(raw: String?): Int? = when (raw?.trim()?.lowercase()) {
        "auto" -> 0
        "legacy" -> 1
        "safety" -> 2
        else -> null
    }

    fun coerceMode(mode: Int): Int = mode.coerceIn(0, 2)

    /** 纯解析：供单测锁定；Intent 包装见 [fromIntent]。 */
    fun fromParts(stringAuth: String?, intAuth: Int?, hasIntExtra: Boolean): Int? {
        fromString(stringAuth)?.let { return it }
        if (hasIntExtra) return coerceMode(intAuth ?: 0)
        return null
    }

    fun fromIntent(intent: Intent?): Int? {
        intent ?: return null
        val hasInt = intent.hasExtra("auth") && intent.getStringExtra("auth") == null
        val intAuth = if (hasInt) intent.getIntExtra("auth", 0) else null
        return fromParts(intent.getStringExtra("auth"), intAuth, hasInt)
    }

    fun label(mode: Int): String = when (coerceMode(mode)) {
        1 -> "legacy"
        2 -> "safety"
        else -> "auto"
    }
}
