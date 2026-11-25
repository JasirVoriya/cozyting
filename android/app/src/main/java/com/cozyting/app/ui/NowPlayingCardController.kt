package com.cozyting.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.cozyting.app.R
import com.cozyting.app.cast.NowPlayingInfo

/**
 * 跨页面共享的「正在播放」UI 组件（供 [com.cozyting.app.MainActivity] 与 [com.cozyting.app.QuickCastPanelActivity] 共用）：
 * 严格对齐 `design/UI_SPEC.md` 第 2.2 节：
 * - 左侧 96dp 圆角封面 + 右下角 26dp 音源 App Logo 角标（点击跳转音源 App）；
 * - 右侧 96dp 齐平列（左距 16dp），歌名 / 歌手 / 宽间距播控按钮沿中轴居中；
 * - 下方全宽 4dp 细胶囊可拖拽进度条 + 左右时间走字；
 * - 未授权通知使用权时自动折叠为精简单行引导条。
 */
class NowPlayingCardController(
    private val context: Context,
    private val palette: UiPalette,
    private val onOpenMusicApp: () -> Unit,
    private val onSkipPrevious: () -> Unit,
    private val onPlayPause: () -> Unit,
    private val onSkipNext: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onOpenNotifSettings: () -> Unit,
    private val onArtworkChanged: ((Bitmap?) -> Unit)? = null,
    private val compact: Boolean = false,
) {
    val rootView: LinearLayout
    val fullBox: LinearLayout
    val hintBanner: LinearLayout

    private val albumArt: ImageView
    private val albumAppBadgeWrap: FrameLayout
    private val albumAppBadgeIcon: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val playBtnIcon: ImageView
    private val progressBar: SeekBar
    private val posView: TextView
    private val durView: TextView

    private var currentInfo: NowPlayingInfo = NowPlayingInfo.unavailable("")
    private var updatedAtMs: Long = 0L
    private var draggingProgress: Boolean = false

    init {
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        fullBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val mediaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val artSize = dp(if (compact) 70 else 96)
        val artRadius = dpf(if (compact) 14f else 16f)
        albumArt = UiComponents.roundedImageView(context, artRadius).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiComponents.roundedDrawable(palette.controlSurface, artRadius)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setImageResource(R.drawable.ic_ui_music_note)
            setColorFilter(palette.textSecondary)
        }
        val badgeSize = dp(if (compact) 20 else 26)
        val badgePad = dp(if (compact) 1 else 2)
        albumAppBadgeIcon = UiComponents.roundedImageView(context, (badgeSize - badgePad * 2) / 2f).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val badgeInset = dp(if (compact) 4 else 6)
        albumAppBadgeWrap = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = badgeInset
                bottomMargin = badgeInset
            }
            background = UiComponents.roundedDrawable(
                fillColor = if (palette.isDark) Color.argb(230, 18, 22, 30) else Color.WHITE,
                radiusPx = badgeSize / 2f,
                strokeColor = if (palette.isDark) Color.argb(72, 255, 255, 255) else Color.argb(38, 15, 23, 42),
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(badgePad, badgePad, badgePad, badgePad)
            clipToOutline = true
            elevation = dpf(2f)
            visibility = View.GONE
            addView(albumAppBadgeIcon)
        }
        val albumArtContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize)
            foreground = UiComponents.rippleSurface(Color.TRANSPARENT, artRadius, palette.ripple)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.main_open_music_app_desc)
            setOnClickListener { onOpenMusicApp() }
            addView(albumArt)
            addView(albumAppBadgeWrap)
        }

        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = artSize
            layoutParams = LinearLayout.LayoutParams(
                0,
                if (compact) ViewGroup.LayoutParams.WRAP_CONTENT else artSize,
                1f,
            ).apply {
                marginStart = dp(if (compact) 12 else 16)
            }
        }
        titleView = TextView(context).apply {
            text = context.getString(R.string.main_unknown_title)
            setTextColor(palette.textPrimary)
            textSize = if (compact) 14.5f else 17.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = if (compact) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
            if (compact) {
                // 紧凑悬浮卡右上角保留 28dp 窝爱听主界面圆钮，对称留白防止长歌名与圆钮重叠
                setPadding(dp(28), 0, dp(28), 0)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        artistView = TextView(context).apply {
            text = context.getString(R.string.main_no_now_playing_sub)
            setTextColor(palette.textSecondary)
            textSize = if (compact) 11.5f else 12.5f
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(if (compact) 2 else 3), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        meta.addView(titleView)
        meta.addView(artistView)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(if (compact) 6 else 10)
            }
        }
        controls.addView(transportBtn(R.drawable.ic_ui_skip_prev, context.getString(R.string.main_prev_track_desc), onSkipPrevious))
        val playWrapSize = dp(if (compact) 36 else 42)
        val playIconSize = dp(if (compact) 17 else 20)
        val playMarginH = dp(if (compact) 18 else 26)
        playBtnIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_ui_play)
            setColorFilter(if (palette.isDark) palette.bgCanvas else Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(playIconSize, playIconSize, Gravity.CENTER)
        }
        val playWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(playWrapSize, playWrapSize).apply {
                marginStart = playMarginH
                marginEnd = playMarginH
            }
            background = UiComponents.rippleSurface(
                fillColor = palette.textPrimary,
                radiusPx = playWrapSize / 2f,
                rippleColor = if (palette.isDark) palette.rippleOnSelected else Color.argb(48, 255, 255, 255),
            )
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.main_play_pause_desc)
            setOnClickListener { onPlayPause() }
            addView(playBtnIcon)
        }
        controls.addView(playWrap)
        controls.addView(transportBtn(R.drawable.ic_ui_skip_next, context.getString(R.string.main_next_track_desc), onSkipNext))
        meta.addView(controls)

        mediaRow.addView(albumArtContainer)
        mediaRow.addView(meta)
        fullBox.addView(mediaRow)

        val progressCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(if (compact) 6 else 10), 0, dp(if (compact) 0 else 2))
        }
        posView = TextView(context).apply {
            setTextColor(palette.textTertiary)
            textSize = if (compact) 10.5f else 11f
            text = "—:—"
        }
        durView = TextView(context).apply {
            setTextColor(palette.textTertiary)
            textSize = if (compact) 10.5f else 11f
            text = "—:—"
            gravity = Gravity.END
        }
        val progThumbStroke = if (compact) {
            if (palette.isDark) Color.rgb(24, 30, 40) else Color.WHITE
        } else {
            palette.panelBg
        }
        progressBar = UiComponents.styledSeekBar(
            context = context,
            activeColor = palette.textPrimary,
            trackColor = palette.trackIdle,
            thumbFillColor = palette.textPrimary,
            thumbStrokeColor = progThumbStroke,
        ).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = currentInfo.durationMs ?: return
                        posView.text = NowPlayingInfo.formatTime(dur * p / 1000L)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    draggingProgress = true
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    draggingProgress = false
                    val dur = currentInfo.durationMs ?: return
                    onSeekTo(dur * progress / 1000L)
                }
            })
        }
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(if (compact) 0 else 2)
            }
            addView(posView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(durView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        progressCol.addView(progressBar)
        progressCol.addView(timeRow)
        fullBox.addView(progressCol)
        rootView.addView(fullBox)

        hintBanner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = palette.rowIdleBg,
                radiusPx = dpf(UiPalette.RADIUS_CONTROL_DP.toFloat()),
                rippleColor = palette.ripple,
                strokeColor = palette.rowIdleBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenNotifSettings() }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_ui_music_note)
                    setColorFilter(palette.accentBright)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
                },
            )
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.main_notif_access_hint)
                    textSize = 12.5f
                    setTextColor(palette.textSecondary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_ui_chevron_right)
                    setColorFilter(palette.textTertiary)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                },
            )
        }
        rootView.addView(hintBanner)
    }

    fun bind(
        info: NowPlayingInfo,
        infoUpdatedAtMs: Long,
        notifAccessGranted: Boolean,
        selectedPackage: String,
        selectedLabel: String,
    ) {
        currentInfo = info
        updatedAtMs = infoUpdatedAtMs

        val available = info.available
        val showFullPlayer = available || notifAccessGranted
        fullBox.visibility = if (showFullPlayer) View.VISIBLE else View.GONE
        hintBanner.visibility = if (showFullPlayer) View.GONE else View.VISIBLE

        val art = info.artwork
        if (art != null) {
            albumArt.clearColorFilter()
            albumArt.setImageBitmap(art)
        } else {
            albumArt.setImageResource(R.drawable.ic_ui_music_note)
            albumArt.setColorFilter(palette.textSecondary)
        }
        onArtworkChanged?.invoke(art)

        if (!showFullPlayer) return

        val badgePkg = info.sourcePackage?.takeIf { it.isNotBlank() }
            ?: selectedPackage.takeIf { it.isNotBlank() }
        if (badgePkg != null) {
            albumAppBadgeWrap.visibility = View.VISIBLE
            UiIconCache.bindAppIcon(context, albumAppBadgeIcon, badgePkg)
        } else {
            albumAppBadgeWrap.visibility = View.GONE
        }

        if (!available) {
            titleView.text = if (selectedLabel.isNotBlank()) {
                context.getString(R.string.main_idle_playing_with_app, selectedLabel)
            } else {
                context.getString(R.string.main_idle_playing_none)
            }
            artistView.text = if (selectedLabel.isNotBlank()) {
                context.getString(R.string.main_idle_hint_with_app, selectedLabel)
            } else {
                context.getString(R.string.main_idle_hint_none)
            }
            playBtnIcon.setImageResource(R.drawable.ic_ui_play)
            posView.text = "—:—"
            durView.text = "—:—"
            progressBar.progress = 0
            return
        }

        titleView.text = NowPlayingInfo.displayTitle(context, info)
        artistView.text = NowPlayingInfo.displayArtist(context, info)
        playBtnIcon.setImageResource(
            if (info.isPlaying == true) R.drawable.ic_ui_pause else R.drawable.ic_ui_play,
        )
        durView.text = NowPlayingInfo.formatTime(info.durationMs)
        tickProgress()
    }

    fun tickProgress() {
        if (!currentInfo.available || draggingProgress) return
        val dur = currentInfo.durationMs
        val basePos = currentInfo.positionMs
        if (dur != null && dur > 0 && basePos != null) {
            val elapsed = if (currentInfo.isPlaying == true && updatedAtMs > 0L) {
                (System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val currentPos = (basePos + elapsed).coerceIn(0L, dur)
            posView.text = NowPlayingInfo.formatTime(currentPos)
            progressBar.progress = ((currentPos * 1000L) / dur).toInt().coerceIn(0, 1000)
        } else {
            posView.text = "—:—"
            progressBar.progress = 0
        }
    }

    private fun transportBtn(res: Int, desc: String, onClick: () -> Unit): FrameLayout {
        val sz = dp(if (compact) 32 else 38)
        val iconSz = dp(if (compact) 16 else 19)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = UiComponents.rippleSurface(
                fillColor = palette.controlSurface,
                radiusPx = sz / 2f,
                rippleColor = palette.ripple,
            )
            isClickable = true
            isFocusable = true
            contentDescription = desc
            setOnClickListener { onClick() }
            addView(
                ImageView(context).apply {
                    setImageResource(res)
                    setColorFilter(palette.textPrimary)
                    layoutParams = FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER)
                },
            )
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * context.resources.displayMetrics.density
}
