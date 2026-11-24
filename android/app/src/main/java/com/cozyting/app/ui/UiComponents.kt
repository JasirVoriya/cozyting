package com.cozyting.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.cozyting.app.R
import com.cozyting.app.capture.AndroidLaunchableApps
import com.cozyting.app.compat.WindowInsetsApplyCompat
import java.util.concurrent.Executors

/**
 * 统一跨页面 UI 组件工厂（零重依赖纯原生 View，不受 ROM 默认主题污染）。
 * 供 MainActivity / SettingsActivity / AppPickerActivity 共用。
 */
object UiComponents {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dpf(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /**
     * 稳定圆角 ImageView：同时启用硬件 `clipToOutline` 与 `Canvas.clipPath`，
     * 确保在真机硬件管线、软件画布及 JVM 截图预览中均严格按指定半径裁剪。
     */
    fun roundedImageView(context: Context, radiusPx: Float): ImageView = object : ImageView(context) {
        private val clipPath = Path()
        private val bounds = RectF()

        init {
            clipToOutline = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            bounds.set(0f, 0f, w.toFloat(), h.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(bounds, radiusPx, radiusPx, Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            if (radiusPx > 0f && width > 0 && height > 0) {
                val save = canvas.save()
                canvas.clipPath(clipPath)
                super.onDraw(canvas)
                canvas.restoreToCount(save)
            } else {
                super.onDraw(canvas)
            }
        }
    }

    fun roundedDrawable(
        fillColor: Int,
        radiusPx: Float,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthPx: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = radiusPx
        if (strokeWidthPx > 0 && strokeColor != Color.TRANSPARENT) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }

    fun rippleSurface(
        fillColor: Int,
        radiusPx: Float,
        rippleColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthPx: Int = 0,
    ): Drawable {
        val content = roundedDrawable(fillColor, radiusPx, strokeColor, strokeWidthPx)
        val mask = roundedDrawable(Color.WHITE, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    /** 无专辑图时的克制双色柔和氛围渐变，彻底替代生硬 8×8 色块。 */
    fun ambientBackdropDrawable(palette: UiPalette): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(palette.bgAmbientTop, palette.bgAmbientBottom),
        )

    fun panelCard(context: Context, palette: UiPalette, padDp: Int = 16): LinearLayout {
        val p = dp(context, padDp)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(
                fillColor = palette.panelBg,
                radiusPx = dpf(context, UiPalette.RADIUS_PANEL_DP.toFloat()),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(context, 1).coerceAtLeast(1),
            )
            setPadding(p, p, p, p)
        }
    }

    fun sectionTitle(context: Context, palette: UiPalette, title: String): TextView =
        TextView(context).apply {
            text = title
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.textSecondary)
            setPadding(dp(context, 4), dp(context, 12), 0, dp(context, 6))
        }

    fun iconCircleButton(
        context: Context,
        palette: UiPalette,
        iconRes: Int,
        contentDesc: String,
        sizeDp: Int = 36,
        iconDp: Int = 18,
        onClick: () -> Unit,
    ): FrameLayout {
        val size = dp(context, sizeDp)
        val iconSize = dp(context, iconDp)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = rippleSurface(
                fillColor = palette.controlSurface,
                radiusPx = size / 2f,
                rippleColor = palette.ripple,
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(context, 1).coerceAtLeast(1),
            )
            isClickable = true
            isFocusable = true
            contentDescription = contentDesc
            setOnClickListener { onClick() }
            addView(
                ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(palette.textPrimary)
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                },
            )
        }
    }

    fun pillBadge(
        context: Context,
        bgColor: Int,
        textColor: Int,
        label: String,
        dotColor: Int? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedDrawable(bgColor, dpf(context, 999f))
        setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4))
        if (dotColor != null) {
            val dotSize = dp(context, 7)
            addView(
                View(context).apply {
                    background = roundedDrawable(dotColor, dotSize / 2f)
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        marginEnd = dp(context, 6)
                    }
                },
            )
        }
        addView(
            TextView(context).apply {
                text = label
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
    }

    fun tonalButton(
        context: Context,
        palette: UiPalette,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 13.5f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else palette.textPrimary)
        background = rippleSurface(
            fillColor = if (primary) palette.accent else palette.controlSurface,
            radiusPx = dpf(context, UiPalette.RADIUS_CONTROL_DP.toFloat()),
            rippleColor = if (primary) Color.argb(56, 255, 255, 255) else palette.ripple,
            strokeColor = if (primary) Color.TRANSPARENT else palette.panelBorder,
            strokeWidthPx = if (primary) 0 else dp(context, 1).coerceAtLeast(1),
        )
        setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** 统一自定义勾选圆形徽章（选中 = 主题蓝实心圆 + 白勾；未选 = 细边框圆环）。 */
    fun checkCircle(context: Context, palette: UiPalette, selected: Boolean, enabled: Boolean = true): FrameLayout {
        val size = dp(context, 22)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            alpha = if (enabled) 1f else 0.4f
            if (selected) {
                background = roundedDrawable(palette.accent, size / 2f)
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_ui_check)
                        setColorFilter(Color.WHITE)
                        layoutParams = FrameLayout.LayoutParams(dp(context, 13), dp(context, 13), Gravity.CENTER)
                    },
                )
            } else {
                background = roundedDrawable(
                    fillColor = Color.TRANSPARENT,
                    radiusPx = size / 2f,
                    strokeColor = palette.textTertiary,
                    strokeWidthPx = dp(context, 2).coerceAtLeast(2),
                )
            }
        }
    }

    /** 定制细轨道 SeekBar，消除不同 ROM 默认紫/青粗进度条的割裂感。 */
    fun styledSeekBar(
        context: Context,
        activeColor: Int,
        trackColor: Int,
        thumbFillColor: Int,
        thumbStrokeColor: Int,
    ): SeekBar {
        val trackHeight = dp(context, 4).coerceAtLeast(3)
        val radius = trackHeight / 2f
        val bgTrack = GradientDrawable().apply {
            setColor(trackColor)
            cornerRadius = radius
            setSize(-1, trackHeight)
        }
        val fgTrack = GradientDrawable().apply {
            setColor(activeColor)
            cornerRadius = radius
            setSize(-1, trackHeight)
        }
        val progressLayer = LayerDrawable(
            arrayOf(
                bgTrack,
                ClipDrawable(fgTrack, Gravity.START, ClipDrawable.HORIZONTAL),
            ),
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            // 显式锁定图层高度与垂直居中，避免 ProgressBar 将 4dp 细轨道拉伸撑满整个 SeekBar 高度
            setLayerHeight(0, trackHeight)
            setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, trackHeight)
            setLayerGravity(1, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
        }
        val thumbSize = dp(context, 14)
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(thumbFillColor)
            setSize(thumbSize, thumbSize)
            setStroke(dp(context, 2).coerceAtLeast(2), thumbStrokeColor)
        }
        val hPad = thumbSize / 2
        val vPad = dp(context, 6)
        return SeekBar(context).apply {
            progressDrawable = progressLayer
            setThumb(thumb)
            splitTrack = false
            thumbOffset = hPad
            setPadding(hPad, vPad, hPad, vPad)
            minHeight = thumbSize
            maxHeight = thumbSize
        }
    }

    /**
     * 带高音量警告着色的定制细轨道音量滑块：
     * - 安全区内保持纯净细胶囊轨道（不绘制额外刻度块）；
     * - 仅在实际音量超过安全上限时，将高亮轨道与滑块描边切为琥珀警告色。
     */
    class VolumeGuardSeekBar(
        context: Context,
        private val normalActiveColor: Int,
        private val warningActiveColor: Int,
        private val fgTrack: GradientDrawable,
        private val thumbDrawable: GradientDrawable,
        private val thumbStrokePx: Int,
    ) : SeekBar(context) {
        var isWarningVisual: Boolean = false
            private set

        fun updateGuardVisual(warning: Boolean) {
            isWarningVisual = warning
            val active = if (warning) warningActiveColor else normalActiveColor
            fgTrack.setColor(active)
            thumbDrawable.setStroke(thumbStrokePx, active)
            invalidate()
        }
    }

    fun styledVolumeSeekBar(
        context: Context,
        activeColor: Int,
        warningColor: Int,
        trackColor: Int,
        thumbFillColor: Int,
        warning: Boolean,
    ): VolumeGuardSeekBar {
        val trackHeight = dp(context, 4).coerceAtLeast(3)
        val radius = trackHeight / 2f
        val bgTrack = GradientDrawable().apply {
            setColor(trackColor)
            cornerRadius = radius
            setSize(-1, trackHeight)
        }
        val fgTrack = GradientDrawable().apply {
            setColor(if (warning) warningColor else activeColor)
            cornerRadius = radius
            setSize(-1, trackHeight)
        }
        val progressLayer = LayerDrawable(
            arrayOf(
                bgTrack,
                ClipDrawable(fgTrack, Gravity.START, ClipDrawable.HORIZONTAL),
            ),
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerHeight(0, trackHeight)
            setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, trackHeight)
            setLayerGravity(1, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
        }
        val thumbSize = dp(context, 14)
        val thumbStrokePx = dp(context, 2).coerceAtLeast(2)
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(thumbFillColor)
            setSize(thumbSize, thumbSize)
            setStroke(thumbStrokePx, if (warning) warningColor else activeColor)
        }
        val hPad = thumbSize / 2
        val vPad = dp(context, 6)
        return VolumeGuardSeekBar(
            context = context,
            normalActiveColor = activeColor,
            warningActiveColor = warningColor,
            fgTrack = fgTrack,
            thumbDrawable = thumb,
            thumbStrokePx = thumbStrokePx,
        ).apply {
            progressDrawable = progressLayer
            setThumb(thumb)
            splitTrack = false
            thumbOffset = hPad
            setPadding(hPad, vPad, hPad, vPad)
            minHeight = thumbSize
            maxHeight = thumbSize
            updateGuardVisual(warning = warning)
        }
    }

    /** 统一圆角输入框（无丑陋系统底线）。 */
    fun styledEditText(
        context: Context,
        palette: UiPalette,
        hintText: String,
        inputTypeFlags: Int? = null,
    ): EditText = EditText(context).apply {
        hint = hintText
        textSize = 14f
        setTextColor(palette.textPrimary)
        setHintTextColor(palette.textTertiary)
        background = roundedDrawable(
            fillColor = palette.controlSurface,
            radiusPx = dpf(context, UiPalette.RADIUS_CONTROL_DP.toFloat()),
            strokeColor = palette.panelBorder,
            strokeWidthPx = dp(context, 1).coerceAtLeast(1),
        )
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        if (inputTypeFlags != null) inputType = inputTypeFlags
    }

    /** 胶囊分段选择器：彻底替代原生 Spinner 和 RadioGroup（告别暗底黑字与灰下拉框）。 */
    fun segmentedControl(
        context: Context,
        palette: UiPalette,
        labels: List<String>,
        initialIndex: Int,
        onSelected: (Int) -> Unit,
    ): LinearLayout {
        var current = initialIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedDrawable(
                fillColor = palette.controlSurface,
                radiusPx = dpf(context, UiPalette.RADIUS_CONTROL_DP.toFloat()),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(context, 1).coerceAtLeast(1),
            )
            setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
        }
        val items = mutableListOf<TextView>()
        fun refresh() {
            items.forEachIndexed { idx, tv ->
                val active = idx == current
                tv.setTextColor(if (active) Color.WHITE else palette.textSecondary)
                tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (active) {
                    roundedDrawable(palette.accent, dpf(context, 9f))
                } else {
                    rippleSurface(Color.TRANSPARENT, dpf(context, 9f), palette.ripple)
                }
            }
        }
        labels.forEachIndexed { idx, textLabel ->
            val tv = TextView(context).apply {
                text = textLabel
                textSize = 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(context, 6), dp(context, 8), dp(context, 6), dp(context, 8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (current != idx) {
                        current = idx
                        refresh()
                        onSelected(idx)
                    }
                }
            }
            items += tv
            container.addView(tv)
        }
        refresh()
        return container
    }

    /** 统一自定义开关行（替代原生 CheckBox）。 */
    fun switchRow(
        context: Context,
        palette: UiPalette,
        title: String,
        subtitle: String? = null,
        initialChecked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ): LinearLayout {
        var checked = initialChecked
        val trackW = dp(context, 44)
        val trackH = dp(context, 26)
        val knobSize = dp(context, 20)
        val knobMargin = dp(context, 3)

        val knob = View(context).apply {
            background = roundedDrawable(Color.WHITE, knobSize / 2f)
            val initialMargin = if (checked) trackW - knobSize - knobMargin else knobMargin
            layoutParams = FrameLayout.LayoutParams(
                knobSize,
                knobSize,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ).apply {
                leftMargin = initialMargin
                marginStart = initialMargin
            }
        }
        val track = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(trackW, trackH).apply { marginStart = dp(context, 12) }
            addView(knob)
        }
        fun syncSwitch() {
            track.background = roundedDrawable(
                fillColor = if (checked) palette.accent else palette.trackIdle,
                radiusPx = trackH / 2f,
            )
            val targetMargin = if (checked) trackW - knobSize - knobMargin else knobMargin
            val lp = (knob.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = targetMargin
                marginStart = targetMargin
            }
            // 重新赋值 layoutParams 触发 resolveLayoutParams() → resolveLayoutDirection()，
            // 否则仅改 marginStart + requestLayout() 不会更新 FrameLayout 读取的 leftMargin。
            knob.layoutParams = lp
        }
        syncSwitch()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rippleSurface(
                fillColor = palette.rowIdleBg,
                radiusPx = dpf(context, UiPalette.RADIUS_CONTROL_DP.toFloat()),
                rippleColor = palette.ripple,
                strokeColor = palette.rowIdleBorder,
                strokeWidthPx = dp(context, 1).coerceAtLeast(1),
            )
            setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                checked = !checked
                syncSwitch()
                onCheckedChange(checked)
            }
            val meta = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            meta.addView(
                TextView(context).apply {
                    text = title
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.textPrimary)
                },
            )
            if (!subtitle.isNullOrBlank()) {
                meta.addView(
                    TextView(context).apply {
                        text = subtitle
                        textSize = 11.5f
                        setTextColor(palette.textSecondary)
                        setPadding(0, dp(context, 2), 0, 0)
                    },
                )
            }
            addView(meta)
            addView(track)
        }
    }

    /** 紧凑型开关控件（用于列表行内单项开关，如常用音源的独立自动识别控制）。 */
    fun compactSwitch(
        context: Context,
        palette: UiPalette,
        initialChecked: Boolean,
        trackOffColor: Int = palette.trackIdle,
        onCheckedChange: (Boolean) -> Unit,
    ): FrameLayout {
        var checked = initialChecked
        val trackW = dp(context, 36)
        val trackH = dp(context, 21)
        val knobSize = dp(context, 15)
        val knobMargin = dp(context, 3)

        val knob = View(context).apply {
            background = roundedDrawable(Color.WHITE, knobSize / 2f)
            val initialMargin = if (checked) trackW - knobSize - knobMargin else knobMargin
            layoutParams = FrameLayout.LayoutParams(
                knobSize,
                knobSize,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ).apply {
                leftMargin = initialMargin
                marginStart = initialMargin
            }
        }
        val track = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(trackW, trackH)
            isClickable = true
            isFocusable = true
            addView(knob)
        }
        fun sync() {
            track.background = roundedDrawable(
                fillColor = if (checked) palette.accent else trackOffColor,
                radiusPx = trackH / 2f,
            )
            val targetMargin = if (checked) trackW - knobSize - knobMargin else knobMargin
            val lp = (knob.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = targetMargin
                marginStart = targetMargin
            }
            knob.layoutParams = lp
        }
        sync()
        track.setOnClickListener {
            checked = !checked
            sync()
            onCheckedChange(checked)
        }
        return track
    }

    /**
     * 复用同一个 ScrollView 容器并在重建内容树时保持原 scrollY（首帧绘制前恢复，避免设置/向导页点击变更后跳回顶部）。
     */
    class ScrollContentHost(private val activity: Activity) {
        var scrollView: ScrollView? = null
            private set
        private var pendingScrollY: Int? = null
        private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

        fun setContent(root: View, backgroundColor: Int) {
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            val existing = scrollView
            if (existing == null) {
                val created = ScrollView(activity).apply {
                    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                    setBackgroundColor(backgroundColor)
                    addView(root)
                }
                WindowInsetsApplyCompat.applySystemBarsPadding(created)
                activity.setContentView(created)
                scrollView = created
                return
            }

            val savedScrollY = pendingScrollY ?: existing.scrollY
            clearPendingPreDraw(existing)
            existing.setBackgroundColor(backgroundColor)
            existing.removeAllViews()
            existing.addView(root)

            if (savedScrollY <= 0) {
                pendingScrollY = null
                return
            }

            pendingScrollY = savedScrollY
            root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    v.removeOnLayoutChangeListener(this)
                    val target = pendingScrollY ?: return
                    applyClampedScroll(existing, target)
                    pendingScrollY = null
                }
            })
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    clearPendingPreDraw(existing)
                    applyClampedScroll(existing, savedScrollY)
                    pendingScrollY = null
                    return true
                }
            }
            preDrawListener = listener
            existing.viewTreeObserver.addOnPreDrawListener(listener)
        }

        private fun clearPendingPreDraw(scroll: ScrollView) {
            val prev = preDrawListener ?: return
            if (scroll.viewTreeObserver.isAlive) {
                scroll.viewTreeObserver.removeOnPreDrawListener(prev)
            }
            preDrawListener = null
        }

        private fun applyClampedScroll(scroll: ScrollView, targetY: Int) {
            val child = scroll.getChildAt(0) ?: return
            val viewportH = (scroll.height - scroll.paddingTop - scroll.paddingBottom).coerceAtLeast(0)
            val maxScrollY = (child.height - viewportH).coerceAtLeast(0)
            scroll.scrollTo(0, targetY.coerceIn(0, maxScrollY))
        }
    }

    /**
     * 统一 Style B 圆角面板弹窗（彻底替代系统原生 AlertDialog，保证深/浅主题一致的高对比卡片视觉）。
     */
    fun showStyleBDialog(
        activity: Activity,
        palette: UiPalette,
        title: String,
        message: String,
        footnote: String? = null,
        primaryLabel: String,
        onPrimary: () -> Unit = {},
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null,
        tertiaryLabel: String? = null,
        onTertiary: (() -> Unit)? = null,
        onDismissed: () -> Unit = {},
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val dialogSolidBg = if (palette.isDark) Color.rgb(21, 26, 35) else Color.WHITE
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(
                fillColor = dialogSolidBg,
                radiusPx = dpf(activity, UiPalette.RADIUS_PANEL_DP.toFloat()),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(activity, 1).coerceAtLeast(1),
            )
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16))
        }

        // —— 顶栏：标题 + 右上角关闭圆钮 ——
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(activity).apply {
                text = title
                textSize = 16.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(activity, 8)
                }
            },
        )
        header.addView(
            iconCircleButton(
                context = activity,
                palette = palette,
                iconRes = R.drawable.ic_ui_close,
                contentDesc = activity.getString(R.string.common_close),
                sizeDp = 30,
                iconDp = 15,
            ) {
                dialog.dismiss()
            },
        )
        card.addView(header)

        // —— 正文说明 ——
        card.addView(
            TextView(activity).apply {
                text = message
                textSize = 13f
                setTextColor(palette.textSecondary)
                setLineSpacing(dpf(activity, 2.5f), 1f)
                setPadding(0, dp(activity, 10), 0, 0)
            },
        )

        // —— 可选内嵌附注/提示框 ——
        if (!footnote.isNullOrBlank()) {
            card.addView(
                TextView(activity).apply {
                    text = footnote
                    textSize = 11.5f
                    setTextColor(palette.textSecondary)
                    setLineSpacing(dpf(activity, 2f), 1f)
                    background = roundedDrawable(
                        fillColor = palette.rowIdleBg,
                        radiusPx = dpf(activity, UiPalette.RADIUS_CONTROL_DP.toFloat()),
                        strokeColor = palette.rowIdleBorder,
                        strokeWidthPx = dp(activity, 1).coerceAtLeast(1),
                    )
                    setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(activity, 10) }
                },
            )
        }

        // —— 底部操作按钮区 ——
        val primaryBtn = tonalButton(activity, palette, primaryLabel, primary = true) {
            dialog.dismiss()
            onPrimary()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(activity, 16) }
        }
        card.addView(primaryBtn)

        if (!secondaryLabel.isNullOrBlank()) {
            val secondaryBtn = tonalButton(activity, palette, secondaryLabel, primary = false) {
                dialog.dismiss()
                onSecondary?.invoke()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(activity, 8) }
            }
            card.addView(secondaryBtn)
        }

        if (!tertiaryLabel.isNullOrBlank()) {
            val footerRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 8), 0, 0)
            }
            footerRow.addView(
                TextView(activity).apply {
                    text = tertiaryLabel
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.textSecondary)
                    background = rippleSurface(Color.TRANSPARENT, dpf(activity, 999f), palette.ripple)
                    setPadding(dp(activity, 18), dp(activity, 6), dp(activity, 18), dp(activity, 4))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        onTertiary?.invoke()
                    }
                },
            )
            card.addView(footerRow)
        }

        val outer = FrameLayout(activity).apply {
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { onDismissed() }
        dialog.show()
        return dialog
    }
}

/**
 * 共享应用图标异步加载与内存缓存，消除 AppPicker 列表展开/滚动时的主线程卡顿。
 */
object UiIconCache {
    private val cache = LruCache<String, Drawable>(96)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    internal fun putForTest(packageName: String, drawable: Drawable) {
        if (packageName.isNotBlank()) cache.put(packageName, drawable)
    }

    internal fun clearForTest() {
        cache.evictAll()
    }

    fun bindAppIcon(context: Context, imageView: ImageView, packageName: String) {
        imageView.tag = packageName
        if (packageName.isBlank()) {
            imageView.setImageResource(R.drawable.ic_ui_music_note)
            return
        }
        val cached = cache.get(packageName)
        if (cached != null) {
            imageView.clearColorFilter()
            imageView.setImageDrawable(cached)
            return
        }
        imageView.setImageResource(R.drawable.ic_ui_music_note)
        val appCtx = context.applicationContext
        executor.execute {
            val loaded = AndroidLaunchableApps.loadIcon(appCtx, packageName)
            if (loaded != null) cache.put(packageName, loaded)
            main.post {
                if (imageView.tag == packageName) {
                    if (loaded != null) {
                        imageView.clearColorFilter()
                        imageView.setImageDrawable(loaded)
                    } else {
                        imageView.setImageResource(R.drawable.ic_ui_music_note)
                    }
                }
            }
        }
    }
}
