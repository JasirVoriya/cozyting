package com.cozyting.app.compat

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 系统栏 insets 与 Edge-to-Edge 明暗反色：
 * 平台 [android.view.WindowInsets.Type] 自 API30；统一走 AndroidX Compat，API29 可安全运行。
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

    /** 开启透明系统栏并根据当前深浅色主题自动反转状态栏与导航栏图标色彩。 */
    @Suppress("DEPRECATION")
    fun applyEdgeToEdge(activity: Activity, isDark: Boolean) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }
}
