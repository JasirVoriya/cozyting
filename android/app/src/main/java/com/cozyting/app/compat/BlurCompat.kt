package com.cozyting.app.compat

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.widget.ImageView

/**
 * 背景模糊与明暗遮罩：API31+ 用 RenderEffect；API29–30 降级为缩略图软模糊 + 明暗叠色。
 * RenderEffect 仅出现在 [BlurEffectApi31]，且仅在 SDK≥31 时加载该类（由契约单测锁死）。
 */
object BlurCompat {
    const val MIN_SDK_DOCUMENTED = 29

    fun supportsRenderEffect(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 31

    fun applyBackdrop(view: ImageView) = applyBackdrop(view, isDark = true)

    fun applyBackdrop(view: ImageView, isDark: Boolean) {
        if (supportsRenderEffect()) {
            BlurEffectApi31.apply(view, isDark)
        } else {
            applySoftDimFallback(view, isDark)
        }
    }

    fun applySoftDimFallback(view: ImageView, isDark: Boolean = true) {
        view.clearColorFilter()
        val scrim = if (isDark) Color.argb(170, 11, 15, 23) else Color.argb(215, 244, 246, 250)
        view.setColorFilter(scrim)
    }

    /**
     * API 29–30 专辑图轻量柔化：缩到 40×40 并执行两次 3×3 盒式模糊（耗时 <2ms，无 RenderEffect 依赖），
     * 放大铺底后可呈现柔和的氛围毛玻璃质感，替代生硬像素块。
     */
    fun softBlurForBackdrop(source: Bitmap): Bitmap {
        if (supportsRenderEffect()) return source
        val w = 40
        val h = 40
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(2) {
            boxBlurPass(pixels, temp, w, h)
            boxBlurPass(temp, pixels, w, h)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== source) scaled.recycle()
        return out
    }

    private fun boxBlurPass(src: IntArray, dst: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val row = ny * w
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val c = src[row + nx]
                        r += Color.red(c)
                        g += Color.green(c)
                        b += Color.blue(c)
                        count++
                    }
                }
                dst[y * w + x] = Color.rgb(r / count, g / count, b / count)
            }
        }
    }
}
