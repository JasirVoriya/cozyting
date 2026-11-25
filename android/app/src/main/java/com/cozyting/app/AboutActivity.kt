package com.cozyting.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cozyting.app.compat.WindowInsetsApplyCompat
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.PrefsCastSettingsStore
import com.cozyting.app.ui.SponsorDialogController
import com.cozyting.app.ui.UiComponents
import com.cozyting.app.ui.UiPalette
import com.cozyting.app.update.AppUpdateManager
import com.cozyting.app.update.UpdateChannel
import com.cozyting.app.update.UpdateCheckState
import com.cozyting.app.update.UpdateConfig
import com.cozyting.app.update.UpdateDecision
import com.cozyting.app.update.UpdateDecisionStatus
import com.cozyting.app.update.UpdateDialogController
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 独立「关于窝爱听 / About CozyTing」二级页（Style B，支持明暗主题与三语本地化）：
 * - 顶部独立居中品牌头（App 图标 + 品牌名 + 版本号/预览标识）；
 * - 主卡片平铺无框列表：检查更新（点击原地检测或打开更新详情弹窗）、接收测试版更新开关（Switch）、版本发布与更新日志外链；
 * - 底部展示简洁开源协议与纯局域网声明。
 */
class AboutActivity : Activity() {
    companion object {
        fun formatCheckTime(context: Context, tsMs: Long): String {
            if (tsMs <= 0L) return context.getString(R.string.time_just_now)
            val diff = (System.currentTimeMillis() - tsMs).coerceAtLeast(0L)
            return when {
                diff < 60_000L -> context.getString(R.string.time_just_now)
                diff < 3_600_000L -> context.getString(R.string.time_minutes_ago, diff / 60_000L)
                diff < 86_400_000L -> context.getString(R.string.time_hours_ago, diff / 3_600_000L)
                else -> SimpleDateFormat("MM-dd HH:mm", AppLocaleManager.activeLocale(context)).format(Date(tsMs))
            }
        }

        fun formatVersionTag(context: Context, displayVer: String): String = buildString {
            append("v").append(displayVer)
            if (BuildConfig.IS_ACCEPTANCE) {
                append(" · ").append(context.getString(R.string.settings_ver_acceptance))
            } else if (UpdateChannel.isPrereleaseVersion(displayVer)) {
                append(" · ").append(context.getString(R.string.settings_ver_preview))
            }
        }

        fun formatUpdateSubtitle(
            context: Context,
            settingsStore: PrefsCastSettingsStore,
            state: UpdateCheckState,
        ): String = when (state) {
            is UpdateCheckState.Checking -> context.getString(R.string.settings_update_checking)
            is UpdateCheckState.Failed -> state.message
            is UpdateCheckState.Ready -> when (state.decision.status) {
                UpdateDecisionStatus.UPDATE_AVAILABLE -> {
                    if (state.decision.versionsBehind > 1) {
                        context.getString(
                            R.string.settings_update_avail_multi,
                            state.decision.latestTag,
                            state.decision.versionsBehind,
                        )
                    } else {
                        context.getString(R.string.settings_update_avail_single, state.decision.latestTag)
                    }
                }
                UpdateDecisionStatus.IGNORED -> context.getString(R.string.settings_update_ignored, state.decision.latestTag)
                UpdateDecisionStatus.UNSUPPORTED_OS -> context.getString(R.string.settings_update_unsupported_os, state.decision.latestTag)
                UpdateDecisionStatus.UP_TO_DATE -> context.getString(
                    R.string.settings_update_up_to_date,
                    formatCheckTime(context, state.checkedAtMs),
                )
                UpdateDecisionStatus.ERROR -> state.decision.localizedError(context)
            }
            UpdateCheckState.Idle -> {
                val last = settingsStore.lastUpdateCheckMs
                if (last > 0L) {
                    context.getString(R.string.settings_update_last_checked, formatCheckTime(context, last))
                } else {
                    context.getString(R.string.settings_update_never_checked)
                }
            }
        }
    }

    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var palette: UiPalette
    private val scrollHost by lazy(LazyThreadSafetyMode.NONE) { UiComponents.ScrollContentHost(this) }

    private var pendingOpenDialogAfterManualCheck = false
    private var updateDialog: Dialog? = null
    private var sponsorDialog: Dialog? = null

    private val updateListener: (UpdateCheckState) -> Unit = { st ->
        if (!isFinishing && !isDestroyed) {
            renderUi()
            if (pendingOpenDialogAfterManualCheck && st is UpdateCheckState.Ready) {
                pendingOpenDialogAfterManualCheck = false
                if (st.decision.hasNewerVersion) {
                    showUpdateDialog(st.decision)
                }
            } else if (st is UpdateCheckState.Failed) {
                pendingOpenDialogAfterManualCheck = false
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        settingsStore = PrefsCastSettingsStore(this)
        AppUpdateManager.addListener(updateListener)
        renderUi()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsStore.isInitialized) renderUi()
    }

    override fun onDestroy() {
        AppUpdateManager.removeListener(updateListener)
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
        runCatching { sponsorDialog?.dismiss() }
        sponsorDialog = null
        super.onDestroy()
    }

    private fun renderUi() {
        val isDark = settingsStore.isDark(this)
        palette = UiPalette.forMode(isDark)
        WindowInsetsApplyCompat.applyEdgeToEdge(this, isDark)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(10), pad, dp(24))
        }

        // —— 顶栏 ——
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        topBar.addView(
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_back, getString(R.string.common_back)) {
                finish()
            },
        )
        topBar.addView(
            TextView(this).apply {
                text = getString(R.string.title_about)
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(12), 0, 0, 0)
            },
        )
        root.addView(topBar)

        // —— 顶部独立居中品牌头 ——
        val displayVer = AppUpdateManager.currentDisplayVersion()
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(22))
        }
        val iconSize = dp(64)
        val iconRadius = UiComponents.dpf(this, 17f)
        hero.addView(
            ImageView(this).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(R.string.app_brand_name)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    bottomMargin = dp(10)
                }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, iconRadius)
                    }
                }
                clipToOutline = true
            },
        )
        hero.addView(
            TextView(this).apply {
                text = getString(R.string.app_brand_name)
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        hero.addView(
            TextView(this).apply {
                text = formatVersionTag(this@AboutActivity, displayVer)
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(3), 0, 0)
            },
        )
        root.addView(hero)

        // —— 主列表卡片（平铺无框 + 1dp 细分割线） ——
        val card = UiComponents.panelCard(this, palette, padDp = 14).apply {
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        buildCheckUpdatesRow(card)
        card.addView(divider())
        buildBetaChannelRow(card)
        card.addView(divider())
        buildReleasesRow(card)
        if (BuildConfig.ENABLE_SPONSOR_ENTRY) {
            card.addView(divider())
            buildSponsorRow(card)
        }
        root.addView(card)

        // —— 底部极轻开源与纯局域网声明 ——
        root.addView(
            TextView(this).apply {
                text = getString(R.string.about_footer_note)
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(palette.textTertiary)
                setPadding(dp(12), dp(16), dp(12), dp(8))
            },
        )

        scrollHost.setContent(root, palette.bgCanvas)
    }

    private fun buildCheckUpdatesRow(card: LinearLayout) {
        val state = AppUpdateManager.state
        val readyDecision = (state as? UpdateCheckState.Ready)?.decision
        val hasNew = readyDecision?.hasNewerVersion == true
        val isChecking = state is UpdateCheckState.Checking

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = Color.TRANSPARENT,
                radiusPx = UiComponents.dpf(this@AboutActivity, 10f),
                rippleColor = palette.ripple,
            )
            setPadding(dp(2), dp(12), dp(2), dp(12))
            isClickable = !isChecking
            isFocusable = !isChecking
            setOnClickListener {
                if (isChecking) return@setOnClickListener
                if (hasNew) {
                    showUpdateDialog(readyDecision!!)
                } else {
                    pendingOpenDialogAfterManualCheck = true
                    AppUpdateManager.checkManually(this@AboutActivity)
                }
            }
        }

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        meta.addView(
            TextView(this).apply {
                text = getString(R.string.about_check_updates_title)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        meta.addView(
            TextView(this).apply {
                text = formatUpdateSubtitle(this@AboutActivity, settingsStore, state)
                textSize = 11.5f
                setTextColor(
                    when {
                        state is UpdateCheckState.Failed -> palette.statusWarning
                        hasNew -> palette.accentBright
                        else -> palette.textSecondary
                    },
                )
                setPadding(0, dp(2), 0, 0)
            },
        )
        row.addView(meta)

        when {
            isChecking -> {
                row.addView(
                    UiComponents.pillBadge(
                        this,
                        palette.accentSoftBg,
                        palette.accentBright,
                        getString(R.string.settings_update_pill_checking),
                    ),
                )
            }
            hasNew -> {
                val dotSize = dp(7)
                row.addView(
                    View(this).apply {
                        background = UiComponents.roundedDrawable(palette.accentBright, dotSize / 2f)
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            marginEnd = dp(6)
                        }
                    },
                )
                row.addView(
                    UiComponents.pillBadge(
                        this,
                        palette.accent,
                        Color.WHITE,
                        readyDecision!!.latestTag.ifBlank { "v${readyDecision.latestVersion}" },
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = dp(4) }
                    },
                )
                row.addView(chevronView())
            }
            state is UpdateCheckState.Failed -> {
                row.addView(
                    UiComponents.pillBadge(
                        this,
                        Color.argb(30, Color.red(palette.statusWarning), Color.green(palette.statusWarning), Color.blue(palette.statusWarning)),
                        palette.statusWarning,
                        getString(R.string.settings_update_pill_failed),
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = dp(4) }
                    },
                )
                row.addView(chevronView())
            }
            state is UpdateCheckState.Ready && state.decision.status == UpdateDecisionStatus.UP_TO_DATE -> {
                row.addView(
                    UiComponents.pillBadge(
                        this,
                        Color.argb(28, Color.red(palette.statusSuccess), Color.green(palette.statusSuccess), Color.blue(palette.statusSuccess)),
                        palette.statusSuccess,
                        getString(R.string.settings_update_pill_latest),
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = dp(4) }
                    },
                )
                row.addView(chevronView())
            }
            else -> {
                row.addView(chevronView())
            }
        }
        card.addView(row)
    }

    private fun buildBetaChannelRow(card: LinearLayout) {
        val currentCh = settingsStore.resolvedUpdateChannel(BuildConfig.VERSION_NAME)
        val isBeta = currentCh == UpdateChannel.PRERELEASE
        card.addView(
            UiComponents.switchRow(
                context = this,
                palette = palette,
                title = getString(R.string.about_beta_updates_title),
                subtitle = getString(R.string.about_beta_updates_sub),
                initialChecked = isBeta,
                flat = true,
            ) { checked ->
                settingsStore.updateChannelRaw = if (checked) {
                    UpdateChannel.PRERELEASE.id
                } else {
                    UpdateChannel.STABLE.id
                }
                AppUpdateManager.reevaluateFromCache(this, manual = false)
                renderUi()
            },
        )
    }

    private fun buildReleasesRow(card: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = Color.TRANSPARENT,
                radiusPx = UiComponents.dpf(this@AboutActivity, 10f),
                rippleColor = palette.ripple,
            )
            setPadding(dp(2), dp(12), dp(2), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.PUBLIC_RELEASES_WEB_URL)))
                }
            }
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        meta.addView(
            TextView(this).apply {
                text = getString(R.string.about_releases_title)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        meta.addView(
            TextView(this).apply {
                text = getString(R.string.about_releases_sub)
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(2), 0, 0)
            },
        )
        row.addView(meta)
        row.addView(chevronView())
        card.addView(row)
    }

    private fun buildSponsorRow(card: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = Color.TRANSPARENT,
                radiusPx = UiComponents.dpf(this@AboutActivity, 10f),
                rippleColor = palette.ripple,
            )
            setPadding(dp(2), dp(12), dp(2), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { showSponsorDialog() }
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        meta.addView(
            TextView(this).apply {
                text = getString(R.string.about_sponsor_title)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        meta.addView(
            TextView(this).apply {
                text = getString(R.string.about_sponsor_sub)
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(2), 0, 0)
            },
        )
        row.addView(meta)
        row.addView(chevronView())
        card.addView(row)
    }

    private fun showSponsorDialog() {
        runCatching { sponsorDialog?.dismiss() }
        sponsorDialog = SponsorDialogController.show(this, palette) {
            sponsorDialog = null
        }
    }

    private fun showUpdateDialog(decision: UpdateDecision) {
        runCatching { updateDialog?.dismiss() }
        updateDialog = UpdateDialogController.show(this, palette, decision) {
            updateDialog = null
            if (!isFinishing && !isDestroyed) renderUi()
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(palette.panelBorder)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1).coerceAtLeast(1),
        )
    }

    private fun chevronView(): ImageView = ImageView(this).apply {
        setImageResource(R.drawable.ic_ui_chevron_right)
        setColorFilter(palette.textTertiary)
        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
    }

    private fun dp(v: Int) = UiComponents.dp(this, v)
}
