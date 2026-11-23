package com.miaocast.app.compat

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.widget.ImageView

/** 仅在 [BlurCompat.applyBackdrop] 确认 SDK≥31 后调用，隔离 RenderEffect 类引用。 */
@SuppressLint("NewApi")
internal object BlurEffectApi31 {
    fun apply(view: ImageView, isDark: Boolean = true) {
        view.setRenderEffect(RenderEffect.createBlurEffect(42f, 42f, Shader.TileMode.CLAMP))
        val scrim = if (isDark) Color.argb(150, 11, 15, 23) else Color.argb(208, 244, 246, 250)
        view.setColorFilter(scrim)
    }
}
