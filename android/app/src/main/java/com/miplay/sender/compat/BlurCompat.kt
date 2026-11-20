package com.miplay.sender.compat

import android.graphics.Color
import android.os.Build
import android.widget.ImageView

/**
 * 毛玻璃：API31+ 用 RenderEffect；API29–30 降级为压暗叠色。
 * RenderEffect 仅出现在 [BlurEffectApi31]，且仅在 SDK≥31 时加载该类。
 */
object BlurCompat {
    const val MIN_SDK_DOCUMENTED = 29

    fun supportsRenderEffect(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 31

    fun applyBackdrop(view: ImageView) {
        if (supportsRenderEffect()) {
            BlurEffectApi31.apply(view)
        } else {
            applySoftDimFallback(view)
        }
    }

    fun applySoftDimFallback(view: ImageView) {
        view.clearColorFilter()
        view.setColorFilter(Color.argb(140, 8, 12, 20))
    }
}
