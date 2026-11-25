package com.cozyting.app

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.cozyting.app.capture.CaptureEngineCapabilities
import com.cozyting.app.capture.CaptureEngineMode
import com.cozyting.app.capture.CaptureScopeMode
import com.cozyting.app.capture.PrefsTargetAppStore
import com.cozyting.app.capture.shell.PairingNotificationGuide
import com.cozyting.app.capture.shell.WirelessDebugGuideActivity
import com.cozyting.app.cast.CastRouteSelection
import com.cozyting.app.cast.CastRouteSelection.Effect
import com.cozyting.app.cast.CastRouteSelection.Kind
import com.cozyting.app.cast.NowPlayingInfo
import com.cozyting.app.cast.NowPlayingTracker
import com.cozyting.app.cast.PrefsDiscoveredDeviceStore
import com.cozyting.app.cast.ProtocolBadge
import com.cozyting.app.compat.AcceptanceAudioDefaults
import com.cozyting.app.compat.QuickSettingsTileCompat
import com.cozyting.app.diag.AppLog
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.NowPlayingCardController
import com.cozyting.app.ui.PrefsCastSettingsStore
import com.cozyting.app.ui.UiComponents
import com.cozyting.app.ui.UiPalette
import com.cozyting.app.ui.VolumeSafetyGuard
import org.json.JSONObject
import java.util.UUID

/**
 * 快捷悬浮控制卡（由控制中心磁贴 [CastTileService] 或投送通知栏直达）：
 * - 独立透明任务栈（`taskAffinity=""` + `excludeFromRecents="true"`），点空白处或返回键即关闭，不打断原 App；
 * - 与 [MainActivity] 1:1 共享 [NowPlayingCardController]、总音量行、输出设备样式 B 列表与 [CastService] 状态。
 */
class QuickCastPanelActivity : Activity() {
    companion object {
        private const val REQ_PERMS = 51
        private const val REQ_PROJECTION = 52
        private const val REQ_APP_PICKER = 53
        private const val ALERT_DURATION_MS = 5_000L

        fun createLaunchIntent(context: Context): Intent =
            Intent(context, QuickCastPanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    private data class LatencyIndicatorViews(
        val container: LinearLayout,
        val value: TextView,
        val spinner: ProgressBar,
        val unit: TextView,
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var targetStore: PrefsTargetAppStore
    private lateinit var deviceStore: PrefsDiscoveredDeviceStore
    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var routeState: CastRouteSelection.State
    private lateinit var palette: UiPalette

    private lateinit var scrimView: FrameLayout
    private lateinit var cardView: LinearLayout
    private lateinit var nowPlayingCard: NowPlayingCardController
    private lateinit var volumeBar: UiComponents.VolumeGuardSeekBar
    private lateinit var volumePctView: TextView
    private lateinit var deviceCountView: TextView
    private lateinit var refreshBtn: FrameLayout
    private var restoreLastBtn: TextView? = null
    private lateinit var deviceList: LinearLayout
    private lateinit var alertBanner: LinearLayout
    private lateinit var alertText: TextView
    private var dismissing = false

    private var nowPlaying: NowPlayingInfo = NowPlayingInfo.unavailable("")
    private var nowPlayingUpdatedAtMs: Long = 0L
    private var nowPlayingTracker: NowPlayingTracker? = null
    private var selectedPackage: String = ""
    private var selectedLabel: String = ""
    private var selectedCert: String = ""
    private var lastSelectedSpeakerIds: List<String> = emptyList()
    private var masterVolume: Int = 35
    private val speakerVolume = mutableMapOf<String, Int>()
    private val speakerVolumeViews = mutableMapOf<String, Pair<UiComponents.VolumeGuardSeekBar, TextView>>()
    private val speakerSubtitles = mutableMapOf<String, TextView>()
    private val latencyIndicators = mutableMapOf<String, LatencyIndicatorViews>()

    private var masterVolumeConfirmedHigh = false
    private var suppressRoute = false
    private var castRequested = false
    private var stopRequested = false
    private var draggingSpeakerVolume = false
    private var pendingDeviceListRender = false
    private var pendingStart: Intent? = null
    private var discoverInFlight = false
    private var refreshSpinnerAnim: ObjectAnimator? = null
    private var volumeConfirmDialog: Dialog? = null

    private val clearAlertRunnable = Runnable {
        if (::alertBanner.isInitialized) alertBanner.visibility = View.GONE
    }

    private val poll = object : Runnable {
        override fun run() {
            if (CastService.running.get()) {
                castRequested = false
                stopRequested = false
            }
            if (!CastService.running.get() && !castRequested && CastService.lastFailMessage.isNotBlank()) {
                showAlert(CastService.lastFailMessage)
                CastService.lastFailMessage = ""
                if (routeState.selectedSpeakerIds.isNotEmpty()) {
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    renderDevices()
                }
            } else if (!CastService.running.get() && !castRequested && routeState.selectedSpeakerIds.isNotEmpty()) {
                routeState = CastRouteSelection.selectThisDevice(routeState).state
                renderDevices()
            } else if (CastService.running.get()) {
                val activeIps = CastService.activeReceiverIps()
                if (activeIps.isNotEmpty() && activeIps != routeState.selectedSpeakerIds) {
                    routeState = CastRouteSelection.syncActiveSpeakers(routeState, activeIps)
                    if (!draggingSpeakerVolume) {
                        renderDevices()
                    } else {
                        pendingDeviceListRender = true
                    }
                }
            }
            val stats = runCatching { JSONObject(CastService.lastStats) }.getOrNull()
            updateLatencyAndSubtitleIndicators(stats)
            if (::nowPlayingCard.isInitialized) {
                nowPlayingCard.tickProgress()
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        AppLog.init(applicationContext)
        targetStore = PrefsTargetAppStore(this)
        deviceStore = PrefsDiscoveredDeviceStore(this)
        settingsStore = PrefsCastSettingsStore(this)
        palette = UiPalette.forMode(settingsStore.isDark(this))

        QuickSettingsTileCompat.disableActivityTransition(this)
        QuickSettingsTileCompat.applyWindowBlurBehind(window, dp(22))

        loadPersistedState()
        buildUi()
        bindNowPlaying()
        renderDevices()
        if (!isUiPreviewMode()) {
            scheduleEnterAnimation()
        }

        if (!isUiPreviewMode()) {
            nowPlayingTracker = NowPlayingTracker(
                context = this,
                onUpdate = { info ->
                    main.post {
                        nowPlaying = info
                        nowPlayingUpdatedAtMs = System.currentTimeMillis()
                        bindNowPlaying()
                    }
                },
            ).also { it.start() }
            if (routeState.devices.count { it.isCastSink && it.castable } == 0) {
                discover(forceRefreshNames = false)
            }
            main.post(poll)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::settingsStore.isInitialized) return
        val wantDark = settingsStore.isDark(this)
        if (wantDark != palette.isDark) {
            recreate()
            return
        }
        loadTargetSelection()
        if (CastService.running.get()) {
            val activeIps = CastService.activeReceiverIps()
            if (activeIps.isNotEmpty()) {
                routeState = CastRouteSelection.syncActiveSpeakers(routeState, activeIps)
            }
        }
        nowPlayingTracker?.refresh()
        bindNowPlaying()
        renderDevices()
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        main.removeCallbacks(clearAlertRunnable)
        if (::scrimView.isInitialized) scrimView.animate().cancel()
        if (::cardView.isInitialized) cardView.animate().cancel()
        stopRefreshSpinner()
        nowPlayingTracker?.stop()
        runCatching { volumeConfirmDialog?.dismiss() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        dismissAnimated()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            val startIntent = pendingStart
            pendingStart = null
            if (resultCode == RESULT_OK && data != null && startIntent != null) {
                val currentIps = routeState.selectedSpeakerIds
                if (currentIps.isEmpty()) {
                    castRequested = false
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    renderDevices()
                    return
                }
                startIntent.putExtra(CastService.EXTRA_IPS, currentIps.joinToString(","))
                castRequested = true
                CastService.primeConnectingReceivers(currentIps)
                startIntent
                    .putExtra(CastService.EXTRA_PROJ_CODE, resultCode)
                    .putExtra(CastService.EXTRA_PROJ_DATA, data)
                startForegroundService(startIntent)
                renderDevices()
            } else {
                castRequested = false
                routeState = CastRouteSelection.selectThisDevice(routeState).state
                renderDevices()
                showAlert(getString(R.string.main_status_projection_cancelled))
            }
        } else if (requestCode == REQ_APP_PICKER) {
            loadTargetSelection()
            bindNowPlaying()
        }
    }

    private fun isUiPreviewMode(): Boolean = intent?.getBooleanExtra("ui_preview", false) == true

    private fun loadTargetSelection() {
        val saved = targetStore.loadSelection()
        if (saved != null) {
            selectedPackage = saved.packageName
            selectedLabel = saved.label
            selectedCert = saved.signingCertSha256
        }
    }

    private fun loadPersistedState() {
        loadTargetSelection()
        val policy = settingsStore.volumeGuardPolicy()
        val defaultMaster = AcceptanceAudioDefaults.speakerPremutePercent(BuildConfig.IS_ACCEPTANCE)
        masterVolume = VolumeSafetyGuard.clampForColdStartRestore(
            policy,
            settingsStore.loadMasterVolume(defaultMaster),
        )
        speakerVolume.clear()
        settingsStore.loadSpeakerVolumes().forEach { (id, v) ->
            speakerVolume[id] = VolumeSafetyGuard.clampForColdStartRestore(policy, v)
        }
        lastSelectedSpeakerIds = deviceStore.loadLastSelectedSpeakerIds()
        val cached = deviceStore.loadDevices()
        val prioritized = CastRouteSelection.prioritizeDevices(
            listOf(CastRouteSelection.Device("this", getString(R.string.main_this_device_name), Kind.THIS_DEVICE, true)) + cached,
            lastSelectedSpeakerIds,
        )
        routeState = CastRouteSelection.initial(prioritized)
        if (CastService.running.get()) {
            val activeIps = CastService.activeReceiverIps()
            if (activeIps.isNotEmpty()) {
                routeState = CastRouteSelection.syncActiveSpeakers(routeState, activeIps)
            }
        }
    }

    private fun buildUi() {
        val supportsBlur = QuickSettingsTileCompat.supportsWindowBlurBehind()
        val scrimColor = when {
            palette.isDark && supportsBlur -> Color.argb(34, 6, 10, 18)
            palette.isDark -> Color.argb(88, 6, 10, 18)
            supportsBlur -> Color.argb(38, 226, 234, 246)
            else -> Color.argb(72, 15, 23, 42)
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(scrimColor)
            isClickable = true
            setOnClickListener { dismissAnimated() }
        }
        scrimView = scrim

        val cardMaxW = dp(396)
        val screenW = resources.displayMetrics.widthPixels
        val cardW = (screenW - dp(24)).coerceAtMost(cardMaxW).coerceAtLeast(dp(280))
        val maxDeviceListH = (resources.displayMetrics.heightPixels * 0.44f).toInt().coerceAtLeast(dp(220))

        val cardFill = when {
            palette.isDark && supportsBlur -> Color.argb(148, 18, 24, 36)
            palette.isDark -> Color.argb(218, 18, 24, 36)
            supportsBlur -> Color.argb(176, 250, 252, 255)
            else -> Color.argb(234, 250, 252, 255)
        }
        val cardStroke = if (palette.isDark) Color.argb(56, 255, 255, 255) else Color.argb(198, 255, 255, 255)
        val cardPad = dp(14)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = cardFill,
                radiusPx = dpf(22f),
                strokeColor = cardStroke,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(cardPad, cardPad, cardPad, cardPad)
            isClickable = true
            elevation = dpf(14f)
            layoutParams = FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        cardView = card

        // 1. 正在播放区（与 MainActivity 100% 共享同一组件，悬浮卡启用紧凑模式）
        nowPlayingCard = NowPlayingCardController(
            context = this,
            palette = palette,
            onOpenMusicApp = { openNowPlayingApp() },
            onSkipPrevious = { nowPlayingTracker?.skipPrevious() },
            onPlayPause = {
                if (nowPlayingTracker?.playPause() != true) {
                    openNowPlayingApp()
                }
            },
            onSkipNext = { nowPlayingTracker?.skipNext() },
            onSeekTo = { posMs -> nowPlayingTracker?.seekTo(posMs) },
            onOpenNotifSettings = {
                runCatching {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            compact = true,
        )
        card.addView(nowPlayingCard.rootView)

        // 2. 总音量条（与 MainActivity volRow 布局一致，收紧垂直留白）
        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        volRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_ui_volume)
                setColorFilter(palette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
            },
        )
        val masterPolicy = settingsStore.volumeGuardPolicy()
        val masterWarning = VolumeSafetyGuard.isInHighVolumeZone(masterPolicy, masterVolume)
        volumePctView = TextView(this).apply {
            text = "$masterVolume%"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (masterWarning) volumeWarningColor(onSelectedRow = false) else palette.textSecondary)
            gravity = Gravity.END
            minWidth = dp(32)
        }
        volumeBar = UiComponents.styledVolumeSeekBar(
            context = this,
            activeColor = palette.accent,
            warningColor = volumeWarningColor(onSelectedRow = false),
            trackColor = palette.trackIdle,
            thumbFillColor = Color.WHITE,
            warning = masterWarning,
        ).apply {
            max = 100
            progress = masterVolume
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    applyMasterVolumeVisual(p)
                    if (fromUser) {
                        routeState.selectedSpeakerIds.forEach { id ->
                            speakerVolumeViews[id]?.first?.progress = p
                            applySpeakerVolumeVisual(id, p)
                        }
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    handleMasterVolumeReleased(progress)
                }
            })
        }
        volRow.addView(volumeBar)
        volRow.addView(volumePctView)
        card.addView(volRow)

        // 3. 输出设备标题栏（样式 B：左标题 + 右计数/恢复上次/刷新按钮/窝爱听品牌主界面圆钮）
        val deviceHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        deviceHeader.addView(
            TextView(this).apply {
                text = getString(R.string.main_output_devices)
                setTextColor(palette.textSecondary)
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        deviceCountView = TextView(this).apply {
            setTextColor(palette.textTertiary)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }
        deviceHeader.addView(deviceCountView)

        restoreLastBtn = TextView(this).apply {
            text = getString(R.string.main_restore_last_speakers)
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.accentBright)
            background = UiComponents.rippleSurface(
                fillColor = palette.accentSoftBg,
                radiusPx = dpf(999f),
                rippleColor = palette.ripple,
            )
            setPadding(dp(8), dp(3), dp(8), dp(3))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            setOnClickListener { restoreLastUsedSpeakers() }
        }
        deviceHeader.addView(restoreLastBtn)

        refreshBtn = UiComponents.iconCircleButton(
            context = this,
            palette = palette,
            iconRes = R.drawable.ic_ui_refresh,
            contentDesc = getString(R.string.main_refresh_devices_desc),
            sizeDp = 28,
            iconDp = 15,
        ) {
            discover(forceRefreshNames = true)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6)
        }
        deviceHeader.addView(refreshBtn)

        val openAppBtn = UiComponents.iconCircleButton(
            context = this,
            palette = palette,
            iconRes = R.drawable.ic_stat_miplay,
            contentDesc = getString(R.string.quick_panel_open_app),
            sizeDp = 28,
            iconDp = 15,
        ) {
            openFullApp()
        }
        deviceHeader.addView(openAppBtn)
        card.addView(deviceHeader)

        // 4. 可滚动输出设备列表（限高防溢出，底部直接以最后一张设备卡干净收口）
        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val listScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxDeviceListH, MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(deviceList)
        }
        card.addView(listScroll)

        // 按需轻量提示条
        alertBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = palette.rowIdleBg,
                radiusPx = dpf(UiPalette.RADIUS_CONTROL_DP.toFloat()),
                strokeColor = palette.rowIdleBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            visibility = View.GONE
            isClickable = true
            setOnClickListener { visibility = View.GONE }
        }
        alertText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(palette.statusWarning)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        alertBanner.addView(alertText)
        card.addView(alertBanner)

        scrim.addView(card)
        setContentView(scrim)
    }

    private fun scheduleEnterAnimation() {
        scrimView.alpha = 0f
        cardView.alpha = 0f
        cardView.scaleX = 0.92f
        cardView.scaleY = 0.92f
        cardView.translationY = dpf(18f)

        val observer = cardView.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (cardView.viewTreeObserver.isAlive) {
                    cardView.viewTreeObserver.removeOnPreDrawListener(this)
                }
                val emphasizedOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
                val fadeOut = DecelerateInterpolator(1.5f)
                scrimView.animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .setInterpolator(fadeOut)
                    .start()
                cardView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(280L)
                    .setInterpolator(emphasizedOut)
                    .start()
                ObjectAnimator.ofFloat(cardView, View.ALPHA, 0f, 1f).apply {
                    duration = 200L
                    interpolator = fadeOut
                    start()
                }
                return true
            }
        })
    }

    private fun dismissAnimated() {
        if (dismissing) return
        dismissing = true
        if (isUiPreviewMode() || !::cardView.isInitialized || !::scrimView.isInitialized) {
            finishWithoutTransition()
            return
        }
        val emphasizedIn = PathInterpolator(0.4f, 0f, 1f, 1f)
        scrimView.animate().cancel()
        cardView.animate().cancel()
        scrimView.animate()
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(emphasizedIn)
            .start()
        cardView.animate()
            .alpha(0f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .translationY(dpf(14f))
            .setDuration(180L)
            .setInterpolator(emphasizedIn)
            .withEndAction { finishWithoutTransition() }
            .start()
    }

    private fun finishWithoutTransition() {
        finish()
        QuickSettingsTileCompat.disableActivityTransition(this)
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        finishWithoutTransition()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        if (isUiPreviewMode()) return true
        val flat = runCatching {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        val cn = ComponentName(this, NowPlayingNotificationListener::class.java).flattenToString()
        val shortCn = ComponentName(this, NowPlayingNotificationListener::class.java).flattenToShortString()
        return flat.split(':').any { it.equals(cn, ignoreCase = true) || it.equals(shortCn, ignoreCase = true) }
    }

    private fun bindNowPlaying() {
        if (!::nowPlayingCard.isInitialized) return
        nowPlayingCard.bind(
            info = nowPlaying,
            infoUpdatedAtMs = nowPlayingUpdatedAtMs,
            notifAccessGranted = isNotificationListenerEnabled(),
            selectedPackage = selectedPackage,
            selectedLabel = selectedLabel,
        )
    }

    private fun openNowPlayingApp() {
        val pkg = nowPlaying.sourcePackage?.takeIf { it.isNotBlank() }
            ?: selectedPackage.takeIf { it.isNotBlank() }
            ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            startActivity(launch)
            finishWithoutTransition()
        }
    }

    private fun renderDevices() {
        if (!::deviceList.isInitialized) return
        pendingDeviceListRender = false
        deviceList.removeAllViews()
        latencyIndicators.clear()
        speakerSubtitles.clear()
        speakerVolumeViews.clear()
        suppressRoute = true
        val visible = CastRouteSelection.filterVisibleDevices(
            routeState.devices,
            settingsStore.showAllDiscovered,
        )
        val castableSinks = visible.count { it.isCastSink && it.castable }
        deviceCountView.text = if (castableSinks > 0) {
            getString(R.string.main_found_castable_count, castableSinks)
        } else {
            getString(R.string.main_multiroom_support)
        }
        visible.forEachIndexed { idx, d ->
            deviceList.addView(deviceRow(d, isLast = idx == visible.lastIndex))
        }
        suppressRoute = false
        val stats = runCatching { JSONObject(CastService.lastStats) }.getOrNull()
        updateLatencyAndSubtitleIndicators(stats)
        updateRestoreLastButton()
    }

    private fun updateRestoreLastButton() {
        val btn = restoreLastBtn ?: return
        val isCasting = (CastService.running.get() && !stopRequested) ||
            castRequested ||
            routeState.selectedSpeakerIds.isNotEmpty()
        if (isCasting) {
            btn.visibility = View.GONE
            return
        }
        val restorable = CastRouteSelection.resolveRestorableSpeakerIds(routeState.devices, lastSelectedSpeakerIds)
        if (restorable.isEmpty()) {
            btn.visibility = View.GONE
            return
        }
        btn.text = if (restorable.size > 1) {
            getString(R.string.main_restore_last_speakers_multi, restorable.size)
        } else {
            getString(R.string.main_restore_last_speakers)
        }
        btn.visibility = View.VISIBLE
    }

    private fun restoreLastUsedSpeakers() {
        val restorable = CastRouteSelection.resolveRestorableSpeakerIds(routeState.devices, lastSelectedSpeakerIds)
        if (restorable.isEmpty()) {
            updateRestoreLastButton()
            return
        }
        var next = routeState
        restorable.forEach { id ->
            next = CastRouteSelection.setSpeakerChecked(next, id, true).state
        }
        routeState = next
        rememberSelectedSpeakers(next.selectedSpeakerIds)
        renderDevices()
        if (next.selectedSpeakerIds.isNotEmpty()) {
            startCastTo(next.selectedSpeakerIds)
        }
    }

    private fun deviceRow(d: CastRouteSelection.Device, isLast: Boolean): View {
        val selected = d.selected
        val bgColor = when {
            selected && palette.isDark -> Color.argb(228, 238, 244, 251)
            selected -> Color.argb(235, 235, 243, 255)
            palette.isDark -> Color.argb(34, 255, 255, 255)
            else -> Color.argb(156, 255, 255, 255)
        }
        val borderColor = when {
            selected && palette.isDark -> Color.argb(240, 255, 255, 255)
            selected -> palette.accent
            palette.isDark -> Color.argb(40, 255, 255, 255)
            else -> Color.argb(26, 15, 23, 42)
        }
        val borderW = if (selected && !palette.isDark) dp(2).coerceAtLeast(2) else dp(1).coerceAtLeast(1)
        val titleColor = if (selected) palette.onSelectedPrimary else palette.textPrimary
        val subColor = if (selected) palette.onSelectedSecondary else palette.textSecondary
        val iconColor = when {
            selected && !palette.isDark -> palette.accent
            selected -> palette.onSelectedPrimary
            else -> palette.textSecondary
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = bgColor,
                radiusPx = dpf(14f),
                rippleColor = if (selected) palette.rippleOnSelected else palette.ripple,
                strokeColor = borderColor,
                strokeWidthPx = borderW,
            )
            setPadding(dp(11), dp(7), dp(11), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = if (isLast) 0 else dp(6) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onDeviceClicked(d) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            ImageView(this).apply {
                setImageResource(
                    when (d.kind) {
                        Kind.THIS_DEVICE -> R.drawable.ic_ui_phone
                        Kind.SPEAKER -> R.drawable.ic_ui_speaker
                        Kind.TV -> R.drawable.ic_ui_tv
                    },
                )
                setColorFilter(iconColor)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) }
            },
        )
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val displayName = if (d.kind == Kind.THIS_DEVICE) getString(R.string.main_this_device_name) else d.name
        val displayRoom = if (d.kind == Kind.THIS_DEVICE) "" else d.roomName.trim()
        textCol.addView(
            TextView(this).apply {
                text = formatDeviceTitle(displayName, displayRoom, subColor)
                setTextColor(titleColor)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val sub = TextView(this).apply {
            text = when {
                d.kind == Kind.THIS_DEVICE -> if (selected) {
                    getString(R.string.main_this_device_active_sub)
                } else {
                    getString(R.string.main_this_device_idle_sub)
                }
                !d.castable && d.detail.isNotBlank() -> d.detail
                else -> d.id
            }
            setTextColor(subColor)
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, 0)
        }
        if (d.isCastSink) speakerSubtitles[d.id] = sub
        textCol.addView(sub)
        row.addView(textCol)

        if (d.isCastSink) {
            val pillTextColor = if (selected) palette.selectedPillText else palette.textSecondary
            val value = TextView(this).apply {
                setTextColor(pillTextColor)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
            }
            val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(pillTextColor)
                layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(3) }
            }
            val unit = TextView(this).apply {
                text = "ms"
                setTextColor(pillTextColor)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
            }
            val indicator = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = UiComponents.roundedDrawable(palette.selectedPillBg, dpf(999f))
                setPadding(dp(7), dp(2), dp(7), dp(2))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
                addView(spinner)
                addView(unit)
                addView(value)
            }
            latencyIndicators[d.id] = LatencyIndicatorViews(indicator, value, spinner, unit)
            row.addView(indicator)
        }

        val protoBadge = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) }
        }
        ProtocolBadge.bindWithTint(protoBadge, d.protocol, subColor)
        row.addView(protoBadge)

        if (d.isCastSink) {
            row.addView(UiComponents.checkCircle(this, palette, selected = selected, enabled = d.castable))
        } else if (selected) {
            val dotSz = dp(8)
            row.addView(
                View(this).apply {
                    background = UiComponents.roundedDrawable(palette.accent, dotSz / 2f)
                    layoutParams = LinearLayout.LayoutParams(dotSz, dotSz)
                },
            )
        }
        box.addView(row)

        if (d.isCastSink && selected && d.castable) {
            val initialVol = speakerVolume[d.id] ?: masterVolume
            val policy = settingsStore.volumeGuardPolicy()
            val warning = VolumeSafetyGuard.isInHighVolumeZone(policy, initialVol)
            val volLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, 0)
            }
            val pctView = TextView(this).apply {
                text = "$initialVol%"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (warning) volumeWarningColor(onSelectedRow = true) else subColor)
                gravity = Gravity.END
                minWidth = dp(32)
            }
            val vol = UiComponents.styledVolumeSeekBar(
                context = this,
                activeColor = palette.accent,
                warningColor = volumeWarningColor(onSelectedRow = true),
                trackColor = palette.trackOnSelected,
                thumbFillColor = Color.WHITE,
                warning = warning,
            ).apply {
                max = 100
                progress = initialVol
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        applySpeakerVolumeVisual(d.id, p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {
                        draggingSpeakerVolume = true
                    }
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        draggingSpeakerVolume = false
                        handleSpeakerVolumeReleased(d.id, d.name, progress)
                        if (pendingDeviceListRender) {
                            pendingDeviceListRender = false
                            renderDevices()
                        }
                    }
                })
            }
            speakerVolumeViews[d.id] = vol to pctView
            volLine.addView(vol)
            volLine.addView(pctView)
            box.addView(volLine)
        }
        return box
    }

    private fun onDeviceClicked(d: CastRouteSelection.Device) {
        if (suppressRoute) return
        val transition = when (d.kind) {
            Kind.THIS_DEVICE -> {
                val t = CastRouteSelection.selectThisDevice(routeState)
                if (t.effects.isEmpty() && (CastService.running.get() || castRequested)) {
                    CastRouteSelection.Transition(t.state, listOf(Effect.StopCastToThisDevice))
                } else {
                    t
                }
            }
            Kind.SPEAKER, Kind.TV -> CastRouteSelection.setSpeakerChecked(routeState, d.id, !d.selected)
        }
        routeState = transition.state
        if (transition.state.selectedSpeakerIds.isNotEmpty()) {
            rememberSelectedSpeakers(transition.state.selectedSpeakerIds)
        }
        renderDevices()
        applyEffects(transition.effects)
    }

    private fun applyEffects(effects: List<Effect>) {
        effects.forEach { e ->
            when (e) {
                is Effect.StopCastToThisDevice -> stopCast()
                is Effect.StartCast -> startCastTo(e.speakerIds)
                is Effect.AddSpeaker -> {
                    stopRequested = false
                    if (CastService.running.get() || castRequested) {
                        val targetVol = speakerVolume[e.id] ?: masterVolume
                        val needsConfirm = VolumeSafetyGuard.isInHighVolumeZone(settingsStore.volumeGuardPolicy(), targetVol)
                        CastService.primeConnectingReceivers(setOf(e.id))
                        startService(
                            Intent(this, CastService::class.java).setAction(CastService.ACTION_ADD)
                                .putExtra(CastService.EXTRA_IP, e.id)
                                .putExtra(CastService.EXTRA_VOLUME, targetVol)
                                .putExtra(CastService.EXTRA_VOLUME_CONFIRMED, needsConfirm),
                        )
                    } else if (pendingStart != null) {
                        pendingStart?.putExtra(CastService.EXTRA_IPS, routeState.selectedSpeakerIds.joinToString(","))
                    } else {
                        startCastTo(routeState.selectedSpeakerIds)
                    }
                }
                is Effect.RemoveSpeaker -> {
                    if (pendingStart != null && !CastService.running.get() && !castRequested) {
                        pendingStart?.putExtra(CastService.EXTRA_IPS, routeState.selectedSpeakerIds.joinToString(","))
                    } else {
                        CastService.primeRemovingReceiver(e.id)
                        startService(
                            Intent(this, CastService::class.java).setAction(CastService.ACTION_REMOVE)
                                .putExtra(CastService.EXTRA_IP, e.id),
                        )
                    }
                }
                is Effect.RejectNonCastable -> showAlert(getString(R.string.main_err_cannot_cast, e.reason))
            }
        }
    }

    private fun startCastTo(speakerIds: Set<String>) {
        if (speakerIds.isEmpty()) return
        stopRequested = false
        CastService.lastFailMessage = ""
        val appMode = settingsStore.sourceMode != "silent"
        val validation = if (appMode) {
            CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT).validate(
                requestedEngine = settingsStore.captureEngineMode,
                requestedScope = settingsStore.captureScopeMode,
                hasPairedCertificate = PairingNotificationGuide.keyStore(this).isPaired(),
                keepLocalOutput = settingsStore.keepLocalOutput,
            )
        } else {
            CaptureEngineCapabilities.ValidationResult.Allowed(
                effectiveEngine = CaptureEngineMode.STANDARD,
                effectiveScope = CaptureScopeMode.SINGLE_APP,
                usesTrueSingleAppMute = false,
            )
        }
        if (validation is CaptureEngineCapabilities.ValidationResult.Rejected) {
            val localizedReason = validation.message(this)
            showAlert(localizedReason)
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            startActivity(Intent(this, WirelessDebugGuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val allowed = validation as CaptureEngineCapabilities.ValidationResult.Allowed
        val needsTargetApp = appMode && allowed.effectiveScope == CaptureScopeMode.SINGLE_APP
        if (needsTargetApp && selectedPackage.isBlank()) {
            showAlert(getString(R.string.main_err_select_app_first))
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            startActivityForResult(Intent(this, AppPickerActivity::class.java), REQ_APP_PICKER)
            return
        }
        if (appMode &&
            allowed.effectiveEngine == CaptureEngineMode.STANDARD &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            showAlert(getString(R.string.main_err_record_audio_needed))
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS)
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            return
        }

        val ips = speakerIds.joinToString(",")
        speakerIds.forEach { id ->
            if (id !in speakerVolume) speakerVolume[id] = masterVolume
        }
        val initialPremute = if (speakerIds.size == 1) {
            speakerVolume[speakerIds.first()] ?: masterVolume
        } else {
            masterVolume
        }
        val speakerVolumesJson = JSONObject().apply {
            speakerIds.forEach { id -> speakerVolume[id]?.let { put(id, it) } }
        }.toString()
        val base = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_IPS, ips)
            .putExtra(CastService.EXTRA_DELAY, settingsStore.delayMs)
            .putExtra(CastService.EXTRA_PREMUTE, initialPremute)
            .putExtra(CastService.EXTRA_SPEAKER_VOLUMES, speakerVolumesJson)
            .putExtra(CastService.EXTRA_VOLUME_CONFIRMED, masterVolumeConfirmedHigh)
            .putExtra(CastService.EXTRA_REF, settingsStore.refFunction)
            .putExtra(CastService.EXTRA_AUTH, AuthModeResolve.coerceMode(settingsStore.authMode))
            .putExtra(
                CastService.EXTRA_LOCAL_MUTE_POLICY,
                if (settingsStore.keepLocalOutput) "keep_local" else "global_mute",
            )
            .putExtra(CastService.EXTRA_CAPTURE_ENGINE, allowed.effectiveEngine.id)
            .putExtra(CastService.EXTRA_CAPTURE_SCOPE, allowed.effectiveScope.id)

        if (appMode && allowed.effectiveEngine == CaptureEngineMode.WIRELESS_DEBUG) {
            castRequested = true
            CastService.primeConnectingReceivers(speakerIds)
            val svcIntent = base.putExtra(CastService.EXTRA_SOURCE, "app")
            if (needsTargetApp) {
                svcIntent
                    .putExtra(CastService.EXTRA_TARGET_PACKAGE, selectedPackage)
                    .putExtra(CastService.EXTRA_TARGET_LABEL, selectedLabel)
                    .putExtra(CastService.EXTRA_TARGET_CERT, selectedCert)
            }
            startForegroundService(svcIntent)
            renderDevices()
        } else if (appMode) {
            val grantSession = UUID.randomUUID().toString()
            pendingStart = base
                .putExtra(CastService.EXTRA_SOURCE, "app")
                .putExtra(CastService.EXTRA_TARGET_PACKAGE, selectedPackage)
                .putExtra(CastService.EXTRA_TARGET_LABEL, selectedLabel)
                .putExtra(CastService.EXTRA_TARGET_CERT, selectedCert)
                .putExtra(CastService.EXTRA_GRANT_SESSION, grantSession)
            startActivityForResult(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
                REQ_PROJECTION,
            )
        } else {
            castRequested = true
            CastService.primeConnectingReceivers(speakerIds)
            startForegroundService(base.putExtra(CastService.EXTRA_SOURCE, "silent"))
            renderDevices()
        }
    }

    private fun stopCast() {
        castRequested = false
        stopRequested = true
        pendingStart = null
        startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
        QuickSettingsTileCompat.requestListeningState(this)
    }

    private fun rememberSelectedSpeakers(selectedIds: Set<String>) {
        if (selectedIds.isEmpty()) return
        val ordered = buildList {
            lastSelectedSpeakerIds.forEach { id -> if (id in selectedIds && id !in this) add(id) }
            routeState.devices.forEach { d -> if (d.id in selectedIds && d.id !in this) add(d.id) }
            selectedIds.forEach { id -> if (id !in this) add(id) }
        }
        if (ordered != lastSelectedSpeakerIds) {
            lastSelectedSpeakerIds = ordered
            deviceStore.saveLastSelectedSpeakerIds(ordered)
        }
    }

    private fun localIp(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val ip = wm.connectionInfo.ipAddress
        return if (ip == 0) "0.0.0.0" else "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun discover(forceRefreshNames: Boolean) {
        if (discoverInFlight) return
        discoverInFlight = true
        startRefreshSpinner()
        val cachedJson = deviceStore.loadRawJson()
        Thread {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wm.createMulticastLock("miplay-mdns").apply { setReferenceCounted(false); acquire() }
            val r = try {
                NativeBridge.discoverJsonEx(localIp(), 3000, cachedJson, forceRefreshNames)
            } finally {
                lock.release()
            }
            main.post {
                discoverInFlight = false
                stopRefreshSpinner()
                if (isFinishing || isDestroyed) return@post
                val parsed = CastRouteSelection.parseDiscoverJsonResult(r)
                if (parsed == null || parsed.error != null) {
                    if (!CastService.running.get() || stopRequested) {
                        showAlert(getString(R.string.main_err_discover_failed))
                    }
                    return@post
                }
                deviceStore.saveDevices(parsed.cacheable)
                var next = CastRouteSelection.mergeDiscovered(routeState, parsed.merged)
                next = next.copy(
                    devices = CastRouteSelection.prioritizeDevices(next.devices, lastSelectedSpeakerIds),
                )
                if (CastService.running.get() && !stopRequested) {
                    val activeIps = CastService.activeReceiverIps()
                    if (activeIps.isNotEmpty()) {
                        next = CastRouteSelection.syncActiveSpeakers(next, activeIps)
                    }
                }
                routeState = next
                renderDevices()
            }
        }.start()
    }

    private fun startRefreshSpinner() {
        stopRefreshSpinner()
        refreshSpinnerAnim = ObjectAnimator.ofFloat(refreshBtn, View.ROTATION, 0f, 360f).apply {
            duration = 800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRefreshSpinner() {
        refreshSpinnerAnim?.cancel()
        refreshSpinnerAnim = null
        if (::refreshBtn.isInitialized) refreshBtn.rotation = 0f
    }

    private fun volumeWarningColor(onSelectedRow: Boolean): Int =
        if (onSelectedRow) Color.rgb(217, 119, 6) else palette.statusWarning

    private fun applyMasterVolumeVisual(level: Int) {
        val v = level.coerceIn(0, 100)
        val policy = settingsStore.volumeGuardPolicy()
        val warning = VolumeSafetyGuard.isInHighVolumeZone(policy, v)
        volumePctView.text = "$v%"
        volumePctView.setTextColor(if (warning) volumeWarningColor(onSelectedRow = false) else palette.textSecondary)
        volumeBar.updateGuardVisual(warning = warning)
    }

    private fun applySpeakerVolumeVisual(deviceId: String, level: Int) {
        val v = level.coerceIn(0, 100)
        val pair = speakerVolumeViews[deviceId] ?: return
        val policy = settingsStore.volumeGuardPolicy()
        val warning = VolumeSafetyGuard.isInHighVolumeZone(policy, v)
        pair.second.text = "$v%"
        pair.second.setTextColor(if (warning) volumeWarningColor(onSelectedRow = true) else palette.onSelectedSecondary)
        pair.first.updateGuardVisual(warning = warning)
    }

    private fun revertMasterVolumeVisuals() {
        volumeBar.progress = masterVolume
        applyMasterVolumeVisual(masterVolume)
        routeState.selectedSpeakerIds.forEach { id ->
            val prev = speakerVolume[id] ?: masterVolume
            speakerVolumeViews[id]?.first?.progress = prev
            applySpeakerVolumeVisual(id, prev)
        }
    }

    private fun revertSpeakerVolumeVisual(deviceId: String) {
        val prev = speakerVolume[deviceId] ?: masterVolume
        speakerVolumeViews[deviceId]?.first?.progress = prev
        applySpeakerVolumeVisual(deviceId, prev)
    }

    private fun commitMasterVolume(target: Int, confirmed: Boolean) {
        val clamped = target.coerceIn(0, 100)
        val policy = settingsStore.volumeGuardPolicy()
        masterVolume = clamped
        if (!BuildConfig.IS_ACCEPTANCE) {
            settingsStore.saveMasterVolume(clamped)
        }
        masterVolumeConfirmedHigh = when {
            !VolumeSafetyGuard.isInHighVolumeZone(policy, clamped) -> false
            confirmed -> true
            else -> masterVolumeConfirmedHigh
        }
        volumeBar.progress = clamped
        applyMasterVolumeVisual(clamped)
        routeState.selectedSpeakerIds.forEach { id ->
            speakerVolume[id] = clamped
            speakerVolumeViews[id]?.first?.progress = clamped
            applySpeakerVolumeVisual(id, clamped)
        }
        if (!BuildConfig.IS_ACCEPTANCE && routeState.selectedSpeakerIds.isNotEmpty()) {
            settingsStore.saveSpeakerVolumes(speakerVolume)
        }
        if (CastService.running.get() || castRequested) {
            startService(
                Intent(this, CastService::class.java).setAction(CastService.ACTION_VOLUME)
                    .putExtra(CastService.EXTRA_VOLUME, clamped)
                    .putExtra(CastService.EXTRA_VOLUME_CONFIRMED, confirmed),
            )
            QuickSettingsTileCompat.requestListeningState(this)
        }
    }

    private fun commitSpeakerVolume(deviceId: String, target: Int, confirmed: Boolean) {
        val clamped = target.coerceIn(0, 100)
        speakerVolume[deviceId] = clamped
        if (!BuildConfig.IS_ACCEPTANCE) {
            settingsStore.saveSpeakerVolume(deviceId, clamped)
        }
        speakerVolumeViews[deviceId]?.first?.progress = clamped
        applySpeakerVolumeVisual(deviceId, clamped)
        if (CastService.running.get() || castRequested) {
            startService(
                Intent(this, CastService::class.java).setAction(CastService.ACTION_VOLUME)
                    .putExtra(CastService.EXTRA_IP, deviceId)
                    .putExtra(CastService.EXTRA_VOLUME, clamped)
                    .putExtra(CastService.EXTRA_VOLUME_CONFIRMED, confirmed),
            )
            QuickSettingsTileCompat.requestListeningState(this)
        }
    }

    private fun handleMasterVolumeReleased(target: Int) {
        val policy = settingsStore.volumeGuardPolicy()
        val selectedCurrents = routeState.selectedSpeakerIds.map { id -> speakerVolume[id] ?: masterVolume }
        when (val decision = VolumeSafetyGuard.evaluateGroup(policy, masterVolume, selectedCurrents, target)) {
            is VolumeSafetyGuard.Decision.Allow -> commitMasterVolume(decision.volume, confirmed = false)
            is VolumeSafetyGuard.Decision.RequireConfirm -> {
                revertMasterVolumeVisuals()
                showVolumeConfirmDialog(
                    speakerName = null,
                    currentVolume = decision.currentVolume,
                    targetVolume = decision.targetVolume,
                    safeLimit = decision.safeLimit,
                ) {
                    commitMasterVolume(decision.targetVolume, confirmed = true)
                }
            }
        }
    }

    private fun handleSpeakerVolumeReleased(deviceId: String, deviceName: String, target: Int) {
        val policy = settingsStore.volumeGuardPolicy()
        val current = speakerVolume[deviceId] ?: masterVolume
        when (val decision = VolumeSafetyGuard.evaluateSingle(policy, current, target)) {
            is VolumeSafetyGuard.Decision.Allow -> commitSpeakerVolume(deviceId, decision.volume, confirmed = false)
            is VolumeSafetyGuard.Decision.RequireConfirm -> {
                revertSpeakerVolumeVisual(deviceId)
                showVolumeConfirmDialog(
                    speakerName = deviceName,
                    currentVolume = decision.currentVolume,
                    targetVolume = decision.targetVolume,
                    safeLimit = decision.safeLimit,
                ) {
                    commitSpeakerVolume(deviceId, decision.targetVolume, confirmed = true)
                }
            }
        }
    }

    private fun showVolumeConfirmDialog(
        speakerName: String?,
        currentVolume: Int,
        targetVolume: Int,
        safeLimit: Int,
        onConfirm: () -> Unit,
    ) {
        if (isFinishing || isDestroyed) return
        runCatching { volumeConfirmDialog?.dismiss() }
        val msg = if (speakerName.isNullOrBlank()) {
            getString(R.string.volume_guard_msg_master, currentVolume, targetVolume)
        } else {
            getString(R.string.volume_guard_msg_speaker, speakerName, currentVolume, targetVolume)
        }
        volumeConfirmDialog = UiComponents.showStyleBDialog(
            activity = this,
            palette = palette,
            title = getString(R.string.volume_guard_title, safeLimit),
            message = msg,
            primaryLabel = getString(R.string.volume_guard_keep, currentVolume),
            onPrimary = {},
            secondaryLabel = getString(R.string.volume_guard_confirm, targetVolume),
            onSecondary = onConfirm,
            onDismissed = { volumeConfirmDialog = null },
        )
    }

    private fun updateLatencyAndSubtitleIndicators(stats: JSONObject?) {
        val failed = CastService.lastFailMessage.isNotBlank()
        val isCasting = (CastService.running.get() && !stopRequested) || castRequested
        val receivers = stats?.optJSONArray("receivers")
        latencyIndicators.forEach { (deviceId, views) ->
            val device = routeState.devices.firstOrNull { it.id == deviceId }
            val selected = device?.selected == true
            val receiverObj = if (receivers != null) {
                (0 until receivers.length())
                    .mapNotNull(receivers::optJSONObject)
                    .firstOrNull { it.optString("ip") == deviceId }
            } else {
                null
            }
            val uiState = receiverObj?.optString("ui_state", "").orEmpty().lowercase()
            speakerSubtitles[deviceId]?.let { sub ->
                if (device != null && device.castable) {
                    sub.text = when {
                        !selected || !isCasting -> device.id
                        uiState == "reconnecting" -> getString(R.string.main_device_reconnecting, device.id)
                        uiState == "error" -> getString(R.string.main_device_error, device.id)
                        uiState == "streaming" -> getString(R.string.main_device_streaming, device.id)
                        else -> getString(R.string.main_device_connecting, device.id)
                    }
                }
            }
            when (
                val indicator = LatencyDisplay.receiverIndicator(
                    stats = stats,
                    deviceId = deviceId,
                    selected = selected,
                    casting = isCasting,
                    failed = failed,
                )
            ) {
                LatencyDisplay.ReceiverIndicator.Hidden -> views.container.visibility = View.GONE
                LatencyDisplay.ReceiverIndicator.Loading -> {
                    views.container.visibility = View.VISIBLE
                    views.spinner.visibility = View.VISIBLE
                    views.unit.visibility = View.VISIBLE
                    views.value.text = ""
                }
                is LatencyDisplay.ReceiverIndicator.Value -> {
                    views.container.visibility = View.VISIBLE
                    views.spinner.visibility = View.GONE
                    views.unit.visibility = View.GONE
                    views.value.text = LatencyDisplay.formatReceiverValue(indicator.milliseconds)
                }
            }
        }
    }

    private fun showAlert(msg: String) {
        if (!::alertBanner.isInitialized || msg.isBlank()) return
        alertText.text = msg
        alertBanner.visibility = View.VISIBLE
        main.removeCallbacks(clearAlertRunnable)
        main.postDelayed(clearAlertRunnable, ALERT_DURATION_MS)
    }

    private fun formatDeviceTitle(name: String, room: String, roomColor: Int): CharSequence {
        val sb = SpannableStringBuilder(name)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (room.isNotBlank()) {
            val sepStart = sb.length
            sb.append(" · ").append(room)
            sb.setSpan(ForegroundColorSpan(roomColor), sepStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(11.5f / 13f), sepStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density
}
