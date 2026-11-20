package com.miplay.sender.compat

import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 系统栏 insets：平台 [android.view.WindowInsets.Type] 自 API30。
 * 统一走 WindowInsetsCompat，API29 可安全类加载与运行。
 */
object WindowInsetsApplyCompat {
    const val TYPE_API = 30

    fun canUsePlatformTypes(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= TYPE_API

    fun applySystemBarsPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
