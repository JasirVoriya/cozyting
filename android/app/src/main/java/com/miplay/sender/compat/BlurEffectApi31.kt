package com.miplay.sender.compat

import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.widget.ImageView

/** 仅在 [BlurCompat.applyBackdrop] 确认 SDK≥31 后调用，隔离 RenderEffect 类引用。 */
@SuppressLint("NewApi")
internal object BlurEffectApi31 {
    fun apply(view: ImageView) {
        view.setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP))
        view.setColorFilter(android.graphics.Color.argb(120, 0, 0, 0))
    }
}
