package com.miaocast.app

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.miaocast.app.capture.AndroidLaunchableApps
import com.miaocast.app.capture.AndroidPackageIdentityLookup
import com.miaocast.app.capture.AutoSourceSelectionPolicy
import com.miaocast.app.capture.CaptureEngineCapabilities
import com.miaocast.app.capture.CaptureEngineMode
import com.miaocast.app.capture.CaptureScopeMode
import com.miaocast.app.capture.CaptureTargetResolver
import com.miaocast.app.capture.CaptureTargetResult
import com.miaocast.app.capture.FavoriteAudioSource
import com.miaocast.app.capture.LaunchableApp
import com.miaocast.app.capture.LaunchableAppCatalog
import com.miaocast.app.capture.ManualSourceSwitchPolicy
import com.miaocast.app.capture.PrefsTargetAppStore
import com.miaocast.app.capture.TargetAppSelection
import com.miaocast.app.capture.shell.PairingNotificationGuide
import com.miaocast.app.capture.shell.WirelessDebugGuideActivity
import com.miaocast.app.cast.ActiveMediaSessionSelector
import com.miaocast.app.cast.CastProtocol
import com.miaocast.app.cast.CastRouteSelection
import com.miaocast.app.cast.CastRouteSelection.Effect
import com.miaocast.app.cast.CastRouteSelection.Kind
import com.miaocast.app.cast.NowPlayingInfo
import com.miaocast.app.cast.NowPlayingTracker
import com.miaocast.app.cast.PrefsDiscoveredDeviceStore
import com.miaocast.app.cast.ProtocolBadge
import com.miaocast.app.compat.AcceptanceAudioDefaults
import com.miaocast.app.compat.BlurCompat
import com.miaocast.app.compat.NotificationPermissionCompat
import com.miaocast.app.compat.WindowInsetsApplyCompat
import com.miaocast.app.ui.AppLocaleManager
import com.miaocast.app.ui.LatencyModePreset
import com.miaocast.app.ui.LatencyModeSelection
import com.miaocast.app.ui.LatencyModeTone
import com.miaocast.app.ui.LinearDragReorderController
import com.miaocast.app.ui.PrefsCastSettingsStore
import com.miaocast.app.ui.ThemeMode
import com.miaocast.app.ui.UiComponents
import com.miaocast.app.ui.UiIconCache
import com.miaocast.app.ui.UiPalette
import com.miaocast.app.ui.VolumeSafetyGuard
import com.miaocast.app.update.AppUpdateManager
import com.miaocast.app.update.UpdateCheckState
import com.miaocast.app.update.UpdateDialogController
import org.json.JSONObject
import java.util.UUID

/**
 * 主界面（对齐跨端设计规范 `design/UI_SPEC.md` 第一版样式 B · 明暗双主题）：
 * 顶栏（状态胶囊 + 刷新 + 明暗快切 + 设置）→ 正在播放卡 → 音源卡 → 输出设备列表。
 * 配置与调试选项统一收纳至独立 [SettingsActivity]，保持主面板克制清晰。
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 42
        private const val REQ_APP_PICKER = 43
        private const val REQ_SETTINGS = 45
        private const val AUTO_DISCOVER_COOLDOWN_MS = 30_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: PrefsTargetAppStore
    private lateinit var deviceStore: PrefsDiscoveredDeviceStore
    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var routeState: CastRouteSelection.State
    private lateinit var palette: UiPalette

    private lateinit var bgArt: ImageView
    private lateinit var headerStatusDot: View
    private lateinit var headerStatusLabel: TextView
    private lateinit var latencyPill: LinearLayout
    private lateinit var latencyPillIcon: ImageView
    private lateinit var latencyPillLabel: TextView
    private lateinit var latencyPillChevron: ImageView
    private var latencyPopup: PopupWindow? = null
    private var latencyDetailsDialog: Dialog? = null
    private var updateDialog: Dialog? = null
    private var wirelessPromptDialog: Dialog? = null
    private var volumeConfirmDialog: Dialog? = null
    private var settingsUpdateBadge: View? = null
    private var updateBanner: LinearLayout? = null
    private var updateBannerText: TextView? = null
    private lateinit var refreshBtn: FrameLayout
    private lateinit var nowPlayingFullBox: LinearLayout
    private lateinit var nowPlayingHintBanner: LinearLayout
    private lateinit var albumArt: ImageView
    private lateinit var albumAppBadgeWrap: FrameLayout
    private lateinit var albumAppBadgeIcon: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var playBtnIcon: ImageView
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var volumeBar: UiComponents.VolumeGuardSeekBar
    private lateinit var volumePctView: TextView
    private lateinit var mainTargetIcon: ImageView
    private lateinit var mainTargetTitle: TextView
    private lateinit var mainTargetAutoBadge: TextView
    private lateinit var mainTargetSubtitle: TextView
    private lateinit var quickSwitchScroll: HorizontalScrollView
    private lateinit var quickSwitchRow: LinearLayout
    private var quickChipDragController: LinearDragReorderController? = null
    private val quickChipSelectedFlags = mutableMapOf<View, Boolean>()
    private lateinit var deviceCountView: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var statusBanner: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusLine: TextView

    private var cachedLaunchableApps: List<LaunchableApp> = emptyList()
    private var favoriteSources: List<FavoriteAudioSource> = emptyList()
    private var currentSelectionAutoDetected: Boolean = false
    private var manualOverrideBlockedPkg: String? = null
    private var pendingAutoResolvePkg: String? = null
    private var pendingStart: Intent? = null
    private var selectedPackage: String = ""
    private var selectedLabel: String = ""
    private var selectedCert: String = ""
    private var lastGrantSession: String? = null
    private var pendingIps: Set<String>? = null
    private var suppressRoute = false
    private var nowPlaying: NowPlayingInfo = NowPlayingInfo.unavailable("加载中…")
    private var latestSessionSnapshots: List<ActiveMediaSessionSelector.SessionSnapshot> = emptyList()
    private var nowPlayingUpdatedAtMs: Long = 0L
    private var draggingProgress = false
    private var nowPlayingTracker: NowPlayingTracker? = null
    private val castTargetChangeCallback: () -> Unit = {
        main.post {
            syncCastStateFromService(forceSyncRoute = false)
            val stats = runCatching { JSONObject(CastService.lastStats) }.getOrNull()
            updateLatencyAndSubtitleIndicators(stats)
            updateHeaderStatusPill()
        }
    }
    private var masterVolume: Int = 30
    private var masterVolumeConfirmedHigh: Boolean = false
    private val speakerVolume = linkedMapOf<String, Int>()
    private val latencyIndicators = linkedMapOf<String, LatencyIndicatorViews>()
    private val speakerSubtitles = linkedMapOf<String, TextView>()
    private val speakerVolumeViews = linkedMapOf<String, Pair<UiComponents.VolumeGuardSeekBar, TextView>>()
    private var castRequested: Boolean = false
    private var stopRequested: Boolean = false
    private var wasServiceRunning: Boolean = false
    private var discovering: Boolean = false
    private var lastDiscoverCompletedAtMs: Long = 0L
    private var suppressNextResumeAutoDiscover: Boolean = false
    private var draggingSpeakerVolume: Boolean = false
    private var pendingDeviceListRender: Boolean = false
    private var refreshAnim: ObjectAnimator? = null
    private var lastRenderedDark: Boolean = true
    private var appliedLanguageId: String = ""
    private var lastPromptedWirelessError: String = ""
    private var lastStatusMessage: String = ""
    private var lastStatusKind: StatusBannerKind = StatusBannerKind.HIDDEN

    private enum class StatusBannerKind {
        HIDDEN,
        TRANSIENT_INFO,
        WARNING_ALERT,
    }

    private val hideTransientStatusRunnable = Runnable {
        if (!isFinishing && !isDestroyed && lastStatusKind == StatusBannerKind.TRANSIENT_INFO) {
            clearStatusBanner()
        }
    }

    private data class LatencyIndicatorViews(
        val container: LinearLayout,
        val value: TextView,
        val spinner: ProgressBar,
        val unit: TextView,
    )

    private val updateCheckListener: (UpdateCheckState) -> Unit = {
        if (!isFinishing && !isDestroyed) {
            refreshUpdateUi()
        }
    }
    private val autoUpdateCheckRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            AppUpdateManager.maybeAutoCheck(this)
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            syncCastStateFromService(forceSyncRoute = false)
            val stats = runCatching { JSONObject(CastService.lastStats) }.getOrNull()
            updateLatencyAndSubtitleIndicators(stats)
            updateHeaderStatusPill()
            refreshUpdateUi()
            if (!nowPlaying.available && isNotificationListenerEnabled()) {
                nowPlayingTracker?.refresh()
                bindNowPlaying()
            }
            tickNowPlayingProgress()
            main.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        appliedLanguageId = AppLocaleManager.readSelectedLanguage(this).id
        nowPlaying = NowPlayingInfo.unavailable(getString(R.string.common_loading))
        store = PrefsTargetAppStore(this)
        deviceStore = PrefsDiscoveredDeviceStore(this)
        settingsStore = PrefsCastSettingsStore(this)
        VolumeSafetyGuard.syncToNative(settingsStore.volumeGuardPolicy())
        masterVolume = AcceptanceAudioDefaults.speakerPremutePercent(BuildConfig.IS_ACCEPTANCE)
        val cachedDevices = deviceStore.loadDevices()
        routeState = CastRouteSelection.initial(
            listOf(CastRouteSelection.Device("this", getString(R.string.main_this_device_name), Kind.THIS_DEVICE, true)) + cachedDevices,
        )
        syncCastStateFromService(forceSyncRoute = true)
        buildUi()
        refreshTargetFromStore()
        loadLaunchableAppsAsync()
        val need = NotificationPermissionCompat.runtimePermissionsForColdStart()
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS)
        CastService.targetChangeListener = castTargetChangeCallback
        nowPlayingTracker = NowPlayingTracker(
            context = this,
            onUpdate = { info ->
                main.post {
                    nowPlaying = info
                    nowPlayingUpdatedAtMs = System.currentTimeMillis()
                    manualOverrideBlockedPkg = AutoSourceSelectionPolicy.nextManualOverrideBlockedPackage(
                        currentBlockedPackage = manualOverrideBlockedPkg,
                        nowPlayingPackage = info.sourcePackage,
                    )
                    bindNowPlaying()
                    maybeAutoSelectFromNowPlaying()
                }
            },
            onSessionsChanged = { sessions ->
                main.post {
                    latestSessionSnapshots = sessions
                    maybeAutoSelectFromNowPlaying()
                }
            },
        ).also { it.start() }
        main.postDelayed(refreshRunnable, 1000)
        AppUpdateManager.addListener(updateCheckListener)
        if (intent?.getBooleanExtra("ui_preview", false) != true) {
            main.postDelayed(autoUpdateCheckRunnable, 1500)
        }
        handleAutomation(intent)
    }

    override fun onDestroy() {
        AppUpdateManager.removeListener(updateCheckListener)
        main.removeCallbacks(autoUpdateCheckRunnable)
        main.removeCallbacks(hideTransientStatusRunnable)
        runCatching { latencyPopup?.dismiss() }
        latencyPopup = null
        runCatching { latencyDetailsDialog?.dismiss() }
        latencyDetailsDialog = null
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
        runCatching { wirelessPromptDialog?.dismiss() }
        wirelessPromptDialog = null
        runCatching { volumeConfirmDialog?.dismiss() }
        volumeConfirmDialog = null
        quickChipDragController?.cancel()
        main.removeCallbacks(refreshRunnable)
        stopRefreshSpinner()
        if (CastService.targetChangeListener === castTargetChangeCallback) {
            CastService.targetChangeListener = null
        }
        nowPlayingTracker?.stop()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (quickChipDragController?.onDispatchTouchEvent(ev) == true) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomation(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::settingsStore.isInitialized) return
        val currentLangId = AppLocaleManager.readSelectedLanguage(this).id
        if (appliedLanguageId.isNotEmpty() && currentLangId != appliedLanguageId) {
            recreate()
            return
        }
        if (store.hasInitializedFavoriteSources()) {
            favoriteSources = store.loadFavoriteSources()
        }
        val wantDark = settingsStore.isDark(this)
        if (wantDark != lastRenderedDark) {
            buildUi()
        }
        refreshTargetFromStore()
        VolumeSafetyGuard.syncToNative(settingsStore.volumeGuardPolicy())
        syncCastStateFromService(forceSyncRoute = true)
        updateLatencyCapsuleUi()
        refreshUpdateUi()
        renderDevices()
        refreshAllVolumeGuardVisuals()
        nowPlayingTracker?.refresh()
        bindNowPlaying()
        maybeAutoSelectFromNowPlaying()
        maybeAutoDiscoverOnResume()
    }

    private fun maybeAutoDiscoverOnResume() {
        if (intent?.getBooleanExtra("ui_preview", false) == true) return
        if (suppressNextResumeAutoDiscover) {
            suppressNextResumeAutoDiscover = false
            return
        }
        if (discovering) return
        val now = System.currentTimeMillis()
        val castableCount = routeState.devices.count { it.isCastSink && it.castable }
        val cooldownElapsed = lastDiscoverCompletedAtMs <= 0L ||
            (now - lastDiscoverCompletedAtMs) >= AUTO_DISCOVER_COOLDOWN_MS
        if (castableCount == 0 || cooldownElapsed) {
            discover(forceRefreshNames = false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        suppressNextResumeAutoDiscover = true
        if (requestCode != REQ_PERMS) return
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            if (p == Manifest.permission.RECORD_AUDIO && !ok) {
                showAlertStatus(getString(R.string.main_err_record_audio_denied))
            }
        }
    }

    private fun dp(v: Int) = UiComponents.dp(this, v)
    private fun dpf(v: Float) = UiComponents.dpf(this, v)

    private fun refreshUpdateUi() {
        val showBadge = AppUpdateManager.shouldShowUpdateBadge()
        settingsUpdateBadge?.visibility = if (showBadge) View.VISIBLE else View.GONE

        val bannerDecision = AppUpdateManager.activeBannerDecision(CastService.running.get())
        val banner = updateBanner ?: return
        if (bannerDecision == null) {
            banner.visibility = View.GONE
        } else {
            updateBannerText?.text = if (bannerDecision.versionsBehind > 1) {
                getString(R.string.main_update_banner_multi, bannerDecision.latestTag, bannerDecision.versionsBehind)
            } else {
                getString(R.string.main_update_banner, bannerDecision.latestTag)
            }
            banner.visibility = View.VISIBLE
        }
    }

    private fun clearStatusBanner() {
        main.removeCallbacks(hideTransientStatusRunnable)
        lastStatusMessage = ""
        lastStatusKind = StatusBannerKind.HIDDEN
        if (::statusBanner.isInitialized) {
            statusBanner.visibility = View.GONE
        }
    }

    private fun showTransientStatus(msg: String, durationMs: Long = 2500L) {
        if (msg.isBlank()) {
            clearStatusBanner()
            return
        }
        main.removeCallbacks(hideTransientStatusRunnable)
        lastStatusMessage = msg
        lastStatusKind = StatusBannerKind.TRANSIENT_INFO
        applyStatusBannerUi()
        main.postDelayed(hideTransientStatusRunnable, durationMs)
    }

    private fun showAlertStatus(msg: String) {
        if (msg.isBlank()) {
            clearStatusBanner()
            return
        }
        main.removeCallbacks(hideTransientStatusRunnable)
        lastStatusMessage = msg
        lastStatusKind = StatusBannerKind.WARNING_ALERT
        applyStatusBannerUi()
    }

    private fun applyStatusBannerUi() {
        if (!::statusBanner.isInitialized) return
        if (lastStatusKind == StatusBannerKind.HIDDEN || lastStatusMessage.isBlank()) {
            statusBanner.visibility = View.GONE
            return
        }
        val isWarning = lastStatusKind == StatusBannerKind.WARNING_ALERT
        val fgColor = if (isWarning) palette.statusWarning else palette.accentBright
        val bgColor = if (isWarning) {
            Color.argb(
                if (palette.isDark) 34 else 24,
                Color.red(palette.statusWarning),
                Color.green(palette.statusWarning),
                Color.blue(palette.statusWarning),
            )
        } else {
            palette.accentSoftBg
        }
        val borderColor = Color.argb(
            if (palette.isDark) 72 else 52,
            Color.red(fgColor),
            Color.green(fgColor),
            Color.blue(fgColor),
        )
        statusBanner.background = UiComponents.roundedDrawable(
            fillColor = bgColor,
            radiusPx = dpf(12f),
            strokeColor = borderColor,
            strokeWidthPx = dp(1).coerceAtLeast(1),
        )
        statusDot.background = UiComponents.roundedDrawable(fgColor, dp(7) / 2f)
        statusLine.setTextColor(fgColor)
        statusLine.text = lastStatusMessage
        statusBanner.visibility = View.VISIBLE
    }

    private fun buildUi() {
        runCatching { latencyPopup?.dismiss() }
        latencyPopup = null
        runCatching { latencyDetailsDialog?.dismiss() }
        latencyDetailsDialog = null
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
        runCatching { wirelessPromptDialog?.dismiss() }
        wirelessPromptDialog = null
        runCatching { volumeConfirmDialog?.dismiss() }
        volumeConfirmDialog = null
        val isDark = settingsStore.isDark(this)
        lastRenderedDark = isDark
        palette = UiPalette.forMode(isDark)
        WindowInsetsApplyCompat.applyEdgeToEdge(this, isDark)

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(palette.bgCanvas)
        }
        bgArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(UiComponents.ambientBackdropDrawable(palette))
            BlurCompat.applyBackdrop(this, isDark)
        }
        rootFrame.addView(
            bgArt,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val pad = dp(16)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(10), pad, dp(24))
        }

        // —— 1. 顶栏（标题 + 运行状态胶囊 + 刷新 / 明暗快切 / 设置） ——
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(12))
        }
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleWrap.addView(
            TextView(this).apply {
                text = getString(R.string.app_brand_name)
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
            },
        )
        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = palette.panelBg,
                radiusPx = dpf(999f),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        val dotSize = dp(7)
        headerStatusDot = View(this).apply {
            background = UiComponents.roundedDrawable(palette.textTertiary, dotSize / 2f)
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { marginEnd = dp(6) }
        }
        headerStatusLabel = TextView(this).apply {
            text = getString(R.string.main_status_this_device)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.textPrimary)
            maxLines = 1
        }
        statusPill.addView(headerStatusDot)
        statusPill.addView(headerStatusLabel)
        titleWrap.addView(statusPill)
        topBar.addView(titleWrap)

        // 延迟模式快捷胶囊（紧贴设置按钮左侧，点击弹出极简 4 档热切下拉框）
        latencyPillIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(4) }
        }
        latencyPillLabel = TextView(this).apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        latencyPillChevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_chevron_down)
            layoutParams = LinearLayout.LayoutParams(dp(11), dp(11)).apply { marginStart = dp(2) }
        }
        latencyPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.latency_switch_desc)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32),
            ).apply {
                marginEnd = dp(8)
            }
            addView(latencyPillIcon)
            addView(latencyPillLabel)
            addView(latencyPillChevron)
            setOnClickListener { showLatencyModeDropdown(it) }
        }
        updateLatencyCapsuleUi()
        topBar.addView(latencyPill)

        val settingsBtn = UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_settings, getString(R.string.title_settings)) {
            startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
        }
        val settingsBadgeSize = dp(8)
        settingsUpdateBadge = View(this).apply {
            background = UiComponents.roundedDrawable(
                fillColor = palette.accentBright,
                radiusPx = settingsBadgeSize / 2f,
                strokeColor = palette.bgCanvas,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            layoutParams = FrameLayout.LayoutParams(settingsBadgeSize, settingsBadgeSize, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(2)
                marginEnd = dp(2)
            }
            visibility = View.GONE
        }
        val settingsWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(settingsBtn)
            addView(settingsUpdateBadge)
        }
        topBar.addView(settingsWrap)
        content.addView(topBar)

        // —— 非阻塞版本更新轻提示条（仅非投送态发现新版本时展示，可一键关闭，绝不抢焦点） ——
        updateBannerText = TextView(this).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.accentBright)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }
        val bannerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_update)
            setColorFilter(palette.accentBright)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        }
        val bannerAction = TextView(this).apply {
            text = getString(R.string.main_update_banner_view)
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.accentBright)
            background = UiComponents.roundedDrawable(
                fillColor = if (palette.isDark) Color.argb(46, 76, 141, 255) else Color.argb(26, 37, 99, 235),
                radiusPx = dpf(999f),
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val bannerClose = ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_close)
            setColorFilter(palette.textSecondary)
            contentDescription = getString(R.string.main_update_banner_close_desc)
            setPadding(dp(6), dp(6), dp(2), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4) }
            setOnClickListener {
                AppUpdateManager.activeBannerDecision(CastService.running.get())?.let { d ->
                    AppUpdateManager.dismissBannerForTag(d.latestTag)
                }
            }
        }
        updateBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = palette.accentSoftBg,
                radiusPx = dpf(UiPalette.RADIUS_CARD_DP.toFloat()),
                rippleColor = palette.ripple,
                strokeColor = Color.argb(56, Color.red(palette.accentBright), Color.green(palette.accentBright), Color.blue(palette.accentBright)),
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(12), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            addView(bannerIcon)
            addView(updateBannerText)
            addView(bannerAction)
            addView(bannerClose)
            setOnClickListener {
                val d = AppUpdateManager.activeBannerDecision(CastService.running.get()) ?: return@setOnClickListener
                runCatching { updateDialog?.dismiss() }
                updateDialog = UpdateDialogController.show(this@MainActivity, palette, d) {
                    updateDialog = null
                    refreshUpdateUi()
                }
            }
        }
        content.addView(updateBanner)
        refreshUpdateUi()

        // —— 主面板容器 ——
        val mainCard = UiComponents.panelCard(this, palette, padDp = 16)

        // —— 2. 正在播放区 ——
        nowPlayingFullBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左侧大尺寸圆角封面 + 右下角播放软件 Logo + 点击跳转对应 App
        val artSize = dp(96)
        val artRadius = dpf(16f)
        albumArt = UiComponents.roundedImageView(this, artRadius).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiComponents.roundedDrawable(palette.controlSurface, artRadius)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setImageResource(R.drawable.ic_ui_music_note)
            setColorFilter(palette.textSecondary)
        }
        val badgeSize = dp(26)
        val badgePad = dp(2)
        albumAppBadgeIcon = UiComponents.roundedImageView(this, (badgeSize - badgePad * 2) / 2f).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        albumAppBadgeWrap = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(6)
                bottomMargin = dp(6)
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
        val albumArtContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize)
            foreground = UiComponents.rippleSurface(Color.TRANSPARENT, artRadius, palette.ripple)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.main_open_music_app_desc)
            setOnClickListener { openNowPlayingApp() }
            addView(albumArt)
            addView(albumAppBadgeWrap)
        }

        // 右侧：歌名 + 歌手 + 宽间距播控按钮组（左距封面 16dp，与主卡片左右 16dp 内边距完全对称；文字与播控统一沿中轴线居中并在 96dp 封面高度内垂直居中）
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, artSize, 1f).apply {
                marginStart = dp(16)
            }
        }
        titleView = TextView(this).apply {
            text = getString(R.string.main_unknown_title)
            setTextColor(palette.textPrimary)
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        artistView = TextView(this).apply {
            text = getString(R.string.main_no_now_playing_sub)
            setTextColor(palette.textSecondary)
            textSize = 12.5f
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        meta.addView(titleView)
        meta.addView(artistView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
        fun transportBtn(res: Int, desc: String, onClick: () -> Unit): FrameLayout {
            val sz = dp(38)
            return FrameLayout(this).apply {
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
                    ImageView(this@MainActivity).apply {
                        setImageResource(res)
                        setColorFilter(palette.textPrimary)
                        layoutParams = FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
                    },
                )
            }
        }
        controls.addView(transportBtn(R.drawable.ic_ui_skip_prev, getString(R.string.main_prev_track_desc)) { nowPlayingTracker?.skipPrevious() })
        val playWrapSize = dp(42)
        playBtnIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_play)
            setColorFilter(if (palette.isDark) palette.bgCanvas else Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        }
        val playWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(playWrapSize, playWrapSize).apply {
                marginStart = dp(26)
                marginEnd = dp(26)
            }
            background = UiComponents.rippleSurface(
                fillColor = palette.textPrimary,
                radiusPx = playWrapSize / 2f,
                rippleColor = if (palette.isDark) palette.rippleOnSelected else Color.argb(48, 255, 255, 255),
            )
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.main_play_pause_desc)
            setOnClickListener {
                if (nowPlayingTracker?.playPause() != true) {
                    openNowPlayingApp()
                }
            }
            addView(playBtnIcon)
        }
        controls.addView(playWrap)
        controls.addView(transportBtn(R.drawable.ic_ui_skip_next, getString(R.string.main_next_track_desc)) { nowPlayingTracker?.skipNext() })
        meta.addView(controls)

        mediaRow.addView(albumArtContainer)
        mediaRow.addView(meta)
        nowPlayingFullBox.addView(mediaRow)

        val progressCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        posView = TextView(this).apply {
            setTextColor(palette.textTertiary)
            textSize = 11f
            text = "—:—"
        }
        durView = TextView(this).apply {
            setTextColor(palette.textTertiary)
            textSize = 11f
            text = "—:—"
            gravity = Gravity.END
        }
        progressBar = UiComponents.styledSeekBar(
            context = this,
            activeColor = palette.textPrimary,
            trackColor = palette.trackIdle,
            thumbFillColor = palette.textPrimary,
            thumbStrokeColor = palette.panelBg,
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
                        val dur = nowPlaying.durationMs ?: return
                        posView.text = NowPlayingInfo.formatTime(dur * p / 1000L)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    draggingProgress = true
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    draggingProgress = false
                    val dur = nowPlaying.durationMs ?: return
                    nowPlayingTracker?.seekTo(dur * progress / 1000L)
                }
            })
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
            }
            addView(posView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(durView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        progressCol.addView(progressBar)
        progressCol.addView(timeRow)
        nowPlayingFullBox.addView(progressCol)
        mainCard.addView(nowPlayingFullBox)

        // 未开启通知使用权/无活跃媒体会话时的精简可点击引导条
        nowPlayingHintBanner = LinearLayout(this).apply {
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
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_ui_music_note)
                    setColorFilter(palette.accentBright)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = getString(R.string.main_notif_access_hint)
                    textSize = 12.5f
                    setTextColor(palette.textSecondary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_ui_chevron_right)
                    setColorFilter(palette.textTertiary)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                },
            )
        }
        mainCard.addView(nowPlayingHintBanner)

        // —— 总音量条（带实时百分比与安全上限刻度，拖动时增量更新音箱行不整表重建；跨过安全上限时弹窗二次确认） ——
        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        volRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_ui_volume)
                setColorFilter(palette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
            },
        )
        val masterPolicy = settingsStore.volumeGuardPolicy()
        val masterWarning = VolumeSafetyGuard.isInHighVolumeZone(masterPolicy, masterVolume)
        volumePctView = TextView(this).apply {
            text = "$masterVolume%"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (masterWarning) volumeWarningColor(onSelectedRow = false) else palette.textSecondary)
            gravity = Gravity.END
            minWidth = dp(34)
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
        mainCard.addView(volRow)

        // —— 3. 投送音源卡 ——
        val sourceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = palette.rowIdleBg,
                radiusPx = dpf(UiPalette.RADIUS_CARD_DP.toFloat()),
                strokeColor = palette.rowIdleBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        val sourceHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(Color.TRANSPARENT, dpf(10f), palette.ripple)
            isClickable = true
            isFocusable = true
            setOnClickListener { openAppPicker() }
        }
        mainTargetIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_music_note)
            setColorFilter(palette.accentBright)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
        }
        val sourceMeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val sourceTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mainTargetTitle = TextView(this).apply {
            text = getString(R.string.main_source_unselected)
            setTextColor(palette.textPrimary)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        mainTargetAutoBadge = TextView(this).apply {
            text = getString(R.string.main_source_auto_badge)
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.accentBright)
            background = UiComponents.roundedDrawable(palette.accentSoftBg, dpf(999f))
            setPadding(dp(7), dp(2), dp(7), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
            visibility = View.GONE
        }
        sourceTitleRow.addView(mainTargetTitle)
        sourceTitleRow.addView(mainTargetAutoBadge)
        mainTargetSubtitle = TextView(this).apply {
            text = getString(R.string.main_source_unselected_sub)
            setTextColor(palette.textSecondary)
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        sourceMeta.addView(sourceTitleRow)
        sourceMeta.addView(mainTargetSubtitle)

        val moreAppPill = UiComponents.pillBadge(
            context = this,
            bgColor = palette.accentSoftBg,
            textColor = palette.accentBright,
            label = getString(R.string.main_source_manage),
        ).apply {
            setOnClickListener { openAppPicker() }
        }
        sourceHeader.addView(mainTargetIcon)
        sourceHeader.addView(sourceMeta)
        sourceHeader.addView(moreAppPill)
        sourceCard.addView(sourceHeader)

        quickSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(2))
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        quickSwitchScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(quickSwitchRow)
        }
        sourceCard.clipChildren = false
        sourceCard.clipToPadding = false
        sourceCard.addView(quickSwitchScroll)
        mainCard.addView(sourceCard)

        quickChipDragController?.cancel()
        quickChipDragController = LinearDragReorderController(
            context = this,
            axis = LinearDragReorderController.Axis.HORIZONTAL,
            containerProvider = { if (::quickSwitchRow.isInitialized) quickSwitchRow else null },
            scrollViewProvider = { if (::quickSwitchScroll.isInitialized) quickSwitchScroll else null },
            itemKeyProvider = { it.tag as? String },
            isEnabled = { store.hasInitializedFavoriteSources() && favoriteSources.size >= 2 },
            dispatchSyntheticCancel = { super.dispatchTouchEvent(it) },
            onDragVisualStateChanged = { chip, dragging ->
                applyQuickChipBackground(chip, isCurrent = quickChipSelectedFlags[chip] == true, dragging = dragging)
            },
            onItemMoved = { fromPkg, toPkg ->
                favoriteSources = LaunchableAppCatalog.reorderFavoriteSources(favoriteSources, fromPkg, toPkg)
            },
            onDragFinished = { changed ->
                if (changed) {
                    store.saveFavoriteSources(favoriteSources)
                }
            },
        )

        // —— 4. 输出设备区 ——
        val devHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        devHeader.addView(
            TextView(this).apply {
                text = getString(R.string.main_output_devices)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        deviceCountView = TextView(this).apply {
            text = getString(R.string.main_multiroom_support)
            textSize = 11f
            setTextColor(palette.textTertiary)
        }
        val refreshSize = dp(28)
        refreshBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(refreshSize, refreshSize).apply {
                marginStart = dp(8)
            }
            background = UiComponents.rippleSurface(
                fillColor = palette.controlSurface,
                radiusPx = refreshSize / 2f,
                rippleColor = palette.ripple,
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.main_refresh_devices_desc)
            setOnClickListener { discover(forceRefreshNames = true) }
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_ui_refresh)
                    setColorFilter(palette.textSecondary)
                    layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER)
                },
            )
        }
        devHeader.addView(deviceCountView)
        devHeader.addView(refreshBtn)
        mainCard.addView(devHeader)

        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mainCard.addView(deviceList)

        val statusDotSize = dp(7)
        statusDot = View(this).apply {
            background = UiComponents.roundedDrawable(palette.accentBright, statusDotSize / 2f)
            layoutParams = LinearLayout.LayoutParams(statusDotSize, statusDotSize).apply {
                marginEnd = dp(8)
            }
        }
        statusLine = TextView(this).apply {
            setTextColor(palette.accentBright)
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            text = lastStatusMessage
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            visibility = View.GONE
            addView(statusDot)
            addView(statusLine)
        }
        mainCard.addView(statusBanner)
        applyStatusBannerUi()

        content.addView(mainCard)

        val scroll = ScrollView(this).apply {
            addView(content)
        }
        WindowInsetsApplyCompat.applySystemBarsPadding(scroll)
        rootFrame.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(rootFrame)

        if (discovering) startRefreshSpinner()
        refreshTargetFromStore()
        renderDevices()
        bindNowPlaying()
        updateHeaderStatusPill()
    }

    private fun startRefreshSpinner() {
        stopRefreshSpinner()
        val icon = refreshBtn.getChildAt(0) ?: return
        refreshAnim = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
            duration = 850
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRefreshSpinner() {
        refreshAnim?.cancel()
        refreshAnim = null
        if (::refreshBtn.isInitialized) refreshBtn.getChildAt(0)?.rotation = 0f
    }

    private fun updateHeaderStatusPill() {
        if (!::headerStatusLabel.isInitialized) return
        val running = CastService.running.get() && !stopRequested
        val selectedCount = routeState.selectedSpeakerIds.size
        val activeCount = if (running) maxOf(selectedCount, CastService.activeReceiverIps().size) else selectedCount
        val failed = CastService.lastFailMessage.isNotBlank() && !running && !castRequested
        val (dotColor, label) = when {
            failed -> palette.statusError to getString(R.string.main_status_failed)
            running && activeCount > 0 -> palette.statusSuccess to getString(R.string.main_status_casting_count, activeCount)
            running -> palette.statusWarning to getString(R.string.main_status_connecting)
            castRequested && selectedCount > 0 -> palette.statusWarning to getString(R.string.main_status_connecting)
            settingsStore.sourceMode == "silent" -> palette.textTertiary to getString(R.string.main_status_silent_mode)
            else -> palette.textTertiary to getString(R.string.main_status_this_device)
        }
        headerStatusDot.background = UiComponents.roundedDrawable(dotColor, dp(7) / 2f)
        headerStatusLabel.text = label
    }

    private fun latencyWarningSoftColor(): Int = Color.argb(
        if (palette.isDark) 42 else 30,
        Color.red(palette.statusWarning),
        Color.green(palette.statusWarning),
        Color.blue(palette.statusWarning),
    )

    private fun latencyPopupSolidBg(): Int =
        if (palette.isDark) Color.parseColor("#181F2B") else Color.WHITE

    private fun updateLatencyCapsuleUi() {
        if (!::latencyPill.isInitialized) return
        val sel = LatencyModePreset.resolve(settingsStore.delayMs)
        val (fill, stroke, fg) = when (sel.tone) {
            LatencyModeTone.NEUTRAL -> Triple(palette.panelBg, palette.panelBorder, palette.textPrimary)
            LatencyModeTone.ACCENT -> Triple(palette.accentSoftBg, palette.accentBright, palette.accentBright)
            LatencyModeTone.WARNING -> Triple(latencyWarningSoftColor(), palette.statusWarning, palette.statusWarning)
        }
        latencyPill.background = UiComponents.rippleSurface(
            fillColor = fill,
            radiusPx = dpf(999f),
            rippleColor = palette.ripple,
            strokeColor = stroke,
            strokeWidthPx = dp(1).coerceAtLeast(1),
        )
        latencyPillIcon.setImageResource(sel.iconRes)
        latencyPillIcon.setColorFilter(fg)
        latencyPillLabel.text = sel.capsuleLabel(this)
        latencyPillLabel.setTextColor(fg)
        latencyPillChevron.setColorFilter(fg)
    }

    private fun showLatencyModeDropdown(anchor: View) {
        if (latencyPopup?.isShowing == true) {
            latencyPopup?.dismiss()
            return
        }
        val sel = LatencyModePreset.resolve(settingsStore.delayMs)
        val popupWidth = dp(200)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = latencyPopupSolidBg(),
                radiusPx = dpf(16f),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(6), dp(6), dp(6), dp(6))
            elevation = dpf(12f)
        }

        fun buildMenuRow(
            iconRes: Int,
            title: String,
            trailing: String,
            active: Boolean,
            tone: LatencyModeTone,
            onClick: (() -> Unit)?,
        ): LinearLayout {
            val accentCol = when (tone) {
                LatencyModeTone.WARNING -> palette.statusWarning
                LatencyModeTone.NEUTRAL -> palette.textPrimary
                LatencyModeTone.ACCENT -> palette.accentBright
            }
            val rowFill = when {
                !active -> Color.TRANSPARENT
                tone == LatencyModeTone.WARNING -> latencyWarningSoftColor()
                else -> palette.accentSoftBg
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(38),
                )
                background = UiComponents.rippleSurface(
                    fillColor = rowFill,
                    radiusPx = dpf(11f),
                    rippleColor = palette.ripple,
                )
                isClickable = onClick != null
                isFocusable = onClick != null
                if (onClick != null) setOnClickListener { onClick() }

                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(iconRes)
                        setColorFilter(
                            when {
                                active -> accentCol
                                tone == LatencyModeTone.WARNING -> palette.statusWarning
                                else -> palette.textSecondary
                            },
                        )
                        layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                            marginEnd = dp(8)
                        }
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = title
                        textSize = 13f
                        setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                        setTextColor(if (active) accentCol else palette.textPrimary)
                        maxLines = 1
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = trailing
                        textSize = 11.5f
                        setTextColor(palette.textSecondary)
                        maxLines = 1
                        setPadding(dp(6), 0, dp(6), 0)
                    },
                )
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_ui_check)
                        setColorFilter(accentCol)
                        visibility = if (active) View.VISIBLE else View.INVISIBLE
                        layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
                    },
                )
            }
        }

        LatencyModePreset.entries.forEach { preset ->
            val active = sel is LatencyModeSelection.Preset && sel.preset == preset
            panel.addView(
                buildMenuRow(
                    iconRes = preset.iconRes,
                    title = preset.name(this),
                    trailing = preset.shortMs(this),
                    active = active,
                    tone = preset.tone,
                ) {
                    latencyPopup?.dismiss()
                    applyLatencyPreset(preset)
                },
            )
        }

        // 仅当用户在设置页填写了非预设毫秒数时动态展示「自定义」行，不伪装成任何预设
        if (sel is LatencyModeSelection.Custom) {
            panel.addView(
                View(this).apply {
                    setBackgroundColor(palette.panelBorder)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                        marginStart = dp(6)
                        marginEnd = dp(6)
                    }
                },
            )
            panel.addView(
                buildMenuRow(
                    iconRes = sel.iconRes,
                    title = getString(R.string.latency_custom),
                    trailing = "${sel.delayMs}ms",
                    active = true,
                    tone = sel.tone,
                    onClick = null,
                ),
            )
        }

        // 底部分割线 + 按需查看「档位说明」入口
        panel.addView(
            View(this).apply {
                setBackgroundColor(palette.panelBorder)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
                    topMargin = dp(5)
                    bottomMargin = dp(5)
                    marginStart = dp(6)
                    marginEnd = dp(6)
                }
            },
        )
        panel.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(34),
                )
                background = UiComponents.rippleSurface(
                    fillColor = Color.TRANSPARENT,
                    radiusPx = dpf(10f),
                    rippleColor = palette.ripple,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    latencyPopup?.dismiss()
                    showLatencyDetailsDialog()
                }
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_ui_info)
                        setColorFilter(palette.textSecondary)
                        layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) }
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = getString(R.string.latency_tiers_info)
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(palette.textSecondary)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_ui_chevron_right)
                        setColorFilter(palette.textTertiary)
                        layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
                    },
                )
            },
        )

        val popup = PopupWindow(panel, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dpf(12f)
            setOnDismissListener {
                if (::latencyPillChevron.isInitialized) latencyPillChevron.rotation = 0f
            }
        }
        latencyPopup = popup
        latencyPillChevron.rotation = 180f
        popup.showAsDropDown(anchor, anchor.width - popupWidth, dp(6))
    }

    private fun applyLatencyPreset(preset: LatencyModePreset) {
        settingsStore.delayMs = preset.delayMs
        updateLatencyCapsuleUi()
        val effectiveMs = if (preset.delayMs > 0) preset.delayMs else CastService.autoDelayMs(this)
        val pName = preset.name(this)
        val pShort = preset.shortMs(this)
        if (CastService.running.get()) {
            startService(
                Intent(this, CastService::class.java)
                    .setAction(CastService.ACTION_SET_DELAY)
                    .putExtra(CastService.EXTRA_DELAY, effectiveMs),
            )
            val msg = if (preset == LatencyModePreset.ULTRA && routeState.selectedSpeakerIds.size >= 2) {
                getString(R.string.latency_switched_live_ultra_multi, pName, pShort)
            } else {
                getString(R.string.latency_switched_live, pName, pShort)
            }
            showTransientStatus(msg)
        } else {
            showTransientStatus(getString(R.string.latency_switched_saved, pName, pShort))
        }
    }

    private fun showLatencyDetailsDialog() {
        runCatching { latencyDetailsDialog?.dismiss() }
        val sel = LatencyModePreset.resolve(settingsStore.delayMs)
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = latencyPopupSolidBg(),
                radiusPx = dpf(22f),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        // 标题行
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.latency_details_title)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(
            FrameLayout(this).apply {
                val sz = dp(30)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                background = UiComponents.rippleSurface(palette.controlSurface, sz / 2f, palette.ripple)
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.common_close)
                setOnClickListener { dialog.dismiss() }
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_ui_close)
                        setColorFilter(palette.textSecondary)
                        layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER)
                    },
                )
            },
        )
        card.addView(header)
        card.addView(
            TextView(this).apply {
                text = getString(R.string.latency_details_subtitle)
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setLineSpacing(dpf(2f), 1f)
                setPadding(0, dp(6), 0, dp(10))
            },
        )

        LatencyModePreset.entries.forEach { preset ->
            val active = sel is LatencyModeSelection.Preset && sel.preset == preset
            val iconCol = when (preset.tone) {
                LatencyModeTone.WARNING -> palette.statusWarning
                LatencyModeTone.NEUTRAL -> palette.textPrimary
                LatencyModeTone.ACCENT -> palette.accentBright
            }
            val itemFill = when {
                !active -> palette.controlSurface
                preset.tone == LatencyModeTone.WARNING -> latencyWarningSoftColor()
                else -> palette.accentSoftBg
            }
            val itemBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = UiComponents.roundedDrawable(itemFill, dpf(14f))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(
                ImageView(this).apply {
                    setImageResource(preset.iconRes)
                    setColorFilter(iconCol)
                    layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) }
                },
            )
            titleRow.addView(
                TextView(this).apply {
                    val pName = preset.name(this@MainActivity)
                    text = if (active) getString(R.string.latency_preset_current, pName) else pName
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            titleRow.addView(
                TextView(this).apply {
                    text = preset.fullMs(this@MainActivity)
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(iconCol)
                },
            )
            itemBox.addView(titleRow)
            itemBox.addView(
                TextView(this).apply {
                    text = preset.detail(this@MainActivity)
                    textSize = 11.5f
                    setTextColor(palette.textSecondary)
                    setLineSpacing(dpf(1.5f), 1f)
                    setPadding(dp(23), dp(4), 0, 0)
                },
            )
            itemBox.addView(
                TextView(this).apply {
                    text = getString(R.string.latency_runway_prefix, preset.runwaySummary(this@MainActivity))
                    textSize = 10.5f
                    setTextColor(palette.textTertiary)
                    setPadding(dp(23), dp(2), 0, 0)
                },
            )
            card.addView(itemBox)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        btnRow.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.latency_go_custom)) {
                dialog.dismiss()
                startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            },
        )
        btnRow.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.common_got_it), primary = true) {
                dialog.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        card.addView(btnRow)

        val scroll = ScrollView(this).apply {
            addView(card)
        }
        dialog.setContentView(scroll)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost(dp(380)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        latencyDetailsDialog = dialog
        dialog.show()
    }

    internal fun openLatencyDetailsDialogForTest(): Dialog {
        showLatencyDetailsDialog()
        return requireNotNull(latencyDetailsDialog)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        if (intent?.getBooleanExtra("ui_preview", false) == true) {
            val r = nowPlaying.placeholderReasonZh
            val missingAccess = r.contains("通知使用权") ||
                r.contains("Notification Access", ignoreCase = true) ||
                r.contains("通知へのアクセス")
            return nowPlaying.available || !missingAccess
        }
        val flat = runCatching {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        val cn = ComponentName(this, NowPlayingNotificationListener::class.java).flattenToString()
        val shortCn = ComponentName(this, NowPlayingNotificationListener::class.java).flattenToShortString()
        return flat.split(':').any { it.equals(cn, ignoreCase = true) || it.equals(shortCn, ignoreCase = true) }
    }

    private fun bindNowPlaying() {
        if (!::nowPlayingFullBox.isInitialized) return
        val available = nowPlaying.available
        val notifAccessGranted = isNotificationListenerEnabled()
        val showFullPlayer = available || notifAccessGranted
        nowPlayingFullBox.visibility = if (showFullPlayer) View.VISIBLE else View.GONE
        nowPlayingHintBanner.visibility = if (showFullPlayer) View.GONE else View.VISIBLE

        val art = nowPlaying.artwork
        if (art != null) {
            albumArt.clearColorFilter()
            albumArt.setImageBitmap(art)
            bgArt.setImageBitmap(BlurCompat.softBlurForBackdrop(art))
            BlurCompat.applyBackdrop(bgArt, palette.isDark)
        } else {
            albumArt.setImageResource(R.drawable.ic_ui_music_note)
            albumArt.setColorFilter(palette.textSecondary)
            bgArt.clearColorFilter()
            bgArt.setImageDrawable(UiComponents.ambientBackdropDrawable(palette))
        }

        if (!showFullPlayer) return
        val badgePkg = nowPlaying.sourcePackage?.takeIf { it.isNotBlank() }
            ?: selectedPackage.takeIf { it.isNotBlank() }
        if (badgePkg != null) {
            albumAppBadgeWrap.visibility = View.VISIBLE
            UiIconCache.bindAppIcon(this, albumAppBadgeIcon, badgePkg)
        } else {
            albumAppBadgeWrap.visibility = View.GONE
        }

        if (!available) {
            titleView.text = if (selectedLabel.isNotBlank()) {
                getString(R.string.main_idle_playing_with_app, selectedLabel)
            } else {
                getString(R.string.main_idle_playing_none)
            }
            artistView.text = if (selectedLabel.isNotBlank()) {
                getString(R.string.main_idle_hint_with_app, selectedLabel)
            } else {
                getString(R.string.main_idle_hint_none)
            }
            playBtnIcon.setImageResource(R.drawable.ic_ui_play)
            posView.text = "—:—"
            durView.text = "—:—"
            progressBar.progress = 0
            return
        }

        titleView.text = NowPlayingInfo.displayTitle(this, nowPlaying)
        artistView.text = NowPlayingInfo.displayArtist(this, nowPlaying)
        playBtnIcon.setImageResource(
            if (nowPlaying.isPlaying == true) R.drawable.ic_ui_pause else R.drawable.ic_ui_play,
        )
        durView.text = NowPlayingInfo.formatTime(nowPlaying.durationMs)
        tickNowPlayingProgress()
    }

    private fun openNowPlayingApp() {
        val pkg = nowPlaying.sourcePackage?.takeIf { it.isNotBlank() }
            ?: selectedPackage.takeIf { it.isNotBlank() }
            ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }

    private fun tickNowPlayingProgress() {
        if (!::progressBar.isInitialized || !nowPlaying.available || draggingProgress) return
        val dur = nowPlaying.durationMs
        val basePos = nowPlaying.positionMs
        if (dur != null && dur > 0 && basePos != null) {
            val elapsed = if (nowPlaying.isPlaying == true && nowPlayingUpdatedAtMs > 0L) {
                (System.currentTimeMillis() - nowPlayingUpdatedAtMs).coerceAtLeast(0L)
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
        updateHeaderStatusPill()
    }

    private fun deviceRow(d: CastRouteSelection.Device, isLast: Boolean = false): View {
        val selected = d.selected
        val bgColor = if (selected) palette.rowSelectedBg else palette.rowIdleBg
        val borderColor = if (selected) palette.rowSelectedBorder else palette.rowIdleBorder
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
                radiusPx = dpf(UiPalette.RADIUS_CARD_DP.toFloat()),
                rippleColor = if (selected) palette.rippleOnSelected else palette.ripple,
                strokeColor = borderColor,
                strokeWidthPx = borderW,
            )
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = if (isLast) 0 else dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onDeviceClicked(d) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(
                when (d.kind) {
                    Kind.THIS_DEVICE -> R.drawable.ic_ui_phone
                    Kind.SPEAKER -> R.drawable.ic_ui_speaker
                    Kind.TV -> R.drawable.ic_ui_tv
                },
            )
            setColorFilter(iconColor)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val displayName = if (d.kind == Kind.THIS_DEVICE) getString(R.string.main_this_device_name) else d.name
        val displayRoom = if (d.kind == Kind.THIS_DEVICE) "" else d.roomName.trim()
        val name = TextView(this).apply {
            text = formatDeviceTitle(displayName, displayRoom, subColor)
            setTextColor(titleColor)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
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
        textCol.addView(name)
        textCol.addView(sub)

        row.addView(icon)
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
                setPadding(0, dp(8), 0, 0)
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

    private fun formatDeviceTitle(deviceName: String, roomName: String, roomColor: Int): CharSequence {
        val sb = SpannableStringBuilder(deviceName)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, deviceName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (roomName.isNotEmpty()) {
            val start = sb.length
            sb.append(" · ").append(roomName)
            sb.setSpan(
                RelativeSizeSpan(11.5f / 13f),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.setSpan(
                ForegroundColorSpan(roomColor),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    private fun onDeviceClicked(d: CastRouteSelection.Device) {
        if (suppressRoute) return
        val transition = when (d.kind) {
            Kind.THIS_DEVICE -> {
                val t = CastRouteSelection.selectThisDevice(routeState)
                // 防御性兜底：若服务仍在运行但本地路由状态失步，点击「本机播放」依然允许强制停投
                if (t.effects.isEmpty() && (CastService.running.get() || castRequested)) {
                    CastRouteSelection.Transition(t.state, listOf(Effect.StopCastToThisDevice))
                } else {
                    t
                }
            }
            Kind.SPEAKER, Kind.TV -> CastRouteSelection.setSpeakerChecked(routeState, d.id, !d.selected)
        }
        routeState = transition.state
        renderDevices()
        applyEffects(transition.effects)
    }

    private fun applyEffects(effects: List<Effect>) {
        effects.forEach { e ->
            when (e) {
                is Effect.StopCastToThisDevice -> {
                    castRequested = false
                    stopRequested = true
                    pendingStart = null
                    stopCast()
                    updateLatencyAndSubtitleIndicators(null)
                    updateHeaderStatusPill()
                    clearStatusBanner()
                }
                is Effect.StartCast -> startCastTo(e.speakerIds)
                is Effect.AddSpeaker -> {
                    stopRequested = false
                    if (CastService.running.get() || castRequested) {
                        CastService.primeConnectingReceivers(setOf(e.id))
                        updateLatencyAndSubtitleIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
                        service(CastService.ACTION_ADD) { putExtra(CastService.EXTRA_IP, e.id) }
                        clearStatusBanner()
                    } else if (pendingStart != null) {
                        val ips = routeState.selectedSpeakerIds.joinToString(",")
                        pendingStart?.putExtra(CastService.EXTRA_IPS, ips)
                        clearStatusBanner()
                    } else {
                        startCastTo(routeState.selectedSpeakerIds)
                    }
                }
                is Effect.RemoveSpeaker -> {
                    if (pendingStart != null && !CastService.running.get() && !castRequested) {
                        val ips = routeState.selectedSpeakerIds.joinToString(",")
                        pendingStart?.putExtra(CastService.EXTRA_IPS, ips)
                    } else {
                        CastService.primeRemovingReceiver(e.id)
                        updateLatencyAndSubtitleIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
                        service(CastService.ACTION_REMOVE) { putExtra(CastService.EXTRA_IP, e.id) }
                    }
                    clearStatusBanner()
                }
                is Effect.RejectNonCastable -> {
                    showAlertStatus(getString(R.string.main_err_cannot_cast, e.reason))
                }
            }
        }
    }

    private fun startCastTo(speakerIds: Set<String>) {
        if (speakerIds.isEmpty()) return
        stopRequested = false
        CastService.lastFailMessage = ""
        val appMode = settingsStore.sourceMode != "silent"
        val requestedEngine = settingsStore.captureEngineMode
        val requestedScope = settingsStore.captureScopeMode
        val validation = if (appMode) {
            CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT).validate(
                requestedEngine = requestedEngine,
                requestedScope = requestedScope,
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
            showAlertStatus(localizedReason)
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            showWirelessPrecheckGuideDialog(speakerIds, localizedReason)
            return
        }
        val allowed = validation as CaptureEngineCapabilities.ValidationResult.Allowed
        val needsTargetApp = appMode && allowed.effectiveScope == CaptureScopeMode.SINGLE_APP

        if (needsTargetApp && selectedPackage.isBlank()) {
            showAlertStatus(getString(R.string.main_err_select_app_first))
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            openAppPicker()
            return
        }
        if (appMode &&
            allowed.effectiveEngine == CaptureEngineMode.STANDARD &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            showAlertStatus(getString(R.string.main_err_record_audio_needed))
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS)
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            return
        }
        val ips = speakerIds.joinToString(",")
        val base = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_IPS, ips)
            .putExtra(CastService.EXTRA_DELAY, settingsStore.delayMs)
            .putExtra(CastService.EXTRA_PREMUTE, masterVolume)
            .putExtra(CastService.EXTRA_VOLUME_CONFIRMED, masterVolumeConfirmedHigh)
            .putExtra(CastService.EXTRA_REF, settingsStore.refFunction)
            .putExtra(CastService.EXTRA_AUTH, AuthModeResolve.coerceMode(settingsStore.authMode))
            .putExtra(
                CastService.EXTRA_LOCAL_MUTE_POLICY,
                if (settingsStore.keepLocalOutput) "keep_local" else "global_mute",
            )
            .putExtra(CastService.EXTRA_CAPTURE_ENGINE, allowed.effectiveEngine.id)
            .putExtra(CastService.EXTRA_CAPTURE_SCOPE, allowed.effectiveScope.id)
        speakerIds.forEach { speakerVolume[it] = masterVolume }
        clearStatusBanner()
        if (appMode && allowed.effectiveEngine == CaptureEngineMode.WIRELESS_DEBUG) {
            // 无感模式：零 MediaProjection 弹窗、直接启动 mediaPlayback 前台服务
            castRequested = true
            CastService.primeConnectingReceivers(speakerIds)
            updateLatencyAndSubtitleIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
            updateHeaderStatusPill()
            val svcIntent = base.putExtra(CastService.EXTRA_SOURCE, "app")
            if (needsTargetApp) {
                svcIntent
                    .putExtra(CastService.EXTRA_TARGET_PACKAGE, selectedPackage)
                    .putExtra(CastService.EXTRA_TARGET_LABEL, selectedLabel)
                    .putExtra(CastService.EXTRA_TARGET_CERT, selectedCert)
                    .putExtra(CastService.EXTRA_TARGET_AUTO_DETECTED, currentSelectionAutoDetected)
                    .putExtra(CastService.EXTRA_MANUAL_OVERRIDE_BLOCKED_PKG, manualOverrideBlockedPkg.orEmpty())
            }
            startForegroundService(svcIntent)
        } else if (appMode) {
            val grantSession = UUID.randomUUID().toString()
            lastGrantSession = grantSession
            pendingStart = base
                .putExtra(CastService.EXTRA_SOURCE, "app")
                .putExtra(CastService.EXTRA_TARGET_PACKAGE, selectedPackage)
                .putExtra(CastService.EXTRA_TARGET_LABEL, selectedLabel)
                .putExtra(CastService.EXTRA_TARGET_CERT, selectedCert)
                .putExtra(CastService.EXTRA_TARGET_AUTO_DETECTED, currentSelectionAutoDetected)
                .putExtra(CastService.EXTRA_MANUAL_OVERRIDE_BLOCKED_PKG, manualOverrideBlockedPkg.orEmpty())
                .putExtra(CastService.EXTRA_GRANT_SESSION, grantSession)
            startActivityForResult(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
                REQ_PROJECTION,
            )
        } else {
            castRequested = true
            CastService.primeConnectingReceivers(speakerIds)
            updateLatencyAndSubtitleIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
            updateHeaderStatusPill()
            startForegroundService(base.putExtra(CastService.EXTRA_SOURCE, "silent"))
        }
    }

    private fun showWirelessPrecheckGuideDialog(
        speakerIds: Set<String>,
        reasonZh: String,
        allowInPreview: Boolean = false,
    ) {
        if (isFinishing || isDestroyed) return
        if (!allowInPreview && intent?.getBooleanExtra("ui_preview", false) == true) return
        runCatching { wirelessPromptDialog?.dismiss() }
        val caps = CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT)
        val paired = PairingNotificationGuide.keyStore(this).isPaired()

        wirelessPromptDialog = when {
            !caps.supportsWirelessDebugEngine -> {
                UiComponents.showStyleBDialog(
                    activity = this,
                    palette = palette,
                    title = getString(R.string.wireless_dialog_unsupported_title),
                    message = reasonZh,
                    primaryLabel = getString(R.string.wireless_dialog_switch_standard_cast),
                    onPrimary = {
                        settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                        retrySpeakersAfterModeChange(speakerIds)
                    },
                    tertiaryLabel = getString(R.string.common_cancel),
                    onDismissed = { wirelessPromptDialog = null },
                )
            }
            !paired -> {
                UiComponents.showStyleBDialog(
                    activity = this,
                    palette = palette,
                    title = getString(R.string.wireless_dialog_need_pair_title),
                    message = reasonZh,
                    footnote = getString(R.string.wireless_dialog_need_pair_footnote),
                    primaryLabel = getString(R.string.wireless_dialog_open_5step_guide),
                    onPrimary = {
                        startActivity(Intent(this, WirelessDebugGuideActivity::class.java))
                    },
                    secondaryLabel = getString(R.string.wireless_dialog_use_standard_first),
                    onSecondary = {
                        settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                        retrySpeakersAfterModeChange(speakerIds)
                    },
                    tertiaryLabel = getString(R.string.common_cancel),
                    onDismissed = { wirelessPromptDialog = null },
                )
            }
            !caps.supportsShellSingleAppPolicy -> {
                UiComponents.showStyleBDialog(
                    activity = this,
                    palette = palette,
                    title = getString(R.string.wireless_dialog_api30_title),
                    message = reasonZh,
                    primaryLabel = getString(R.string.wireless_dialog_switch_global),
                    onPrimary = {
                        settingsStore.captureScopeMode = CaptureScopeMode.GLOBAL_MEDIA
                        refreshTargetFromStore()
                        retrySpeakersAfterModeChange(speakerIds)
                    },
                    secondaryLabel = getString(R.string.wireless_dialog_use_standard_instead),
                    onSecondary = {
                        settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                        retrySpeakersAfterModeChange(speakerIds)
                    },
                    tertiaryLabel = getString(R.string.common_cancel),
                    onDismissed = { wirelessPromptDialog = null },
                )
            }
            else -> {
                UiComponents.showStyleBDialog(
                    activity = this,
                    palette = palette,
                    title = getString(R.string.wireless_dialog_not_ready_title),
                    message = reasonZh,
                    primaryLabel = getString(R.string.wireless_dialog_open_guide),
                    onPrimary = {
                        startActivity(
                            Intent(this, WirelessDebugGuideActivity::class.java)
                                .putExtra(WirelessDebugGuideActivity.EXTRA_AUTO_VERIFY, true),
                        )
                    },
                    secondaryLabel = getString(R.string.wireless_dialog_use_standard_first),
                    onSecondary = {
                        settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                        retrySpeakersAfterModeChange(speakerIds)
                    },
                    tertiaryLabel = getString(R.string.common_cancel),
                    onDismissed = { wirelessPromptDialog = null },
                )
            }
        }
    }

    internal fun openWirelessPrecheckDialogForTest(reasonZh: String): Dialog {
        showWirelessPrecheckGuideDialog(setOf("192.0.2.10"), reasonZh, allowInPreview = true)
        return requireNotNull(wirelessPromptDialog)
    }

    private fun retrySpeakersAfterModeChange(speakerIds: Set<String>) {
        var next = routeState
        speakerIds.forEach { ip ->
            next = CastRouteSelection.setSpeakerChecked(next, ip, true).state
        }
        routeState = next
        renderDevices()
        startCastTo(speakerIds)
    }

    private fun maybePromptWirelessRecovery(failMessage: String) {
        if (isFinishing || isDestroyed || intent?.getBooleanExtra("ui_preview", false) == true) return
        if (settingsStore.captureEngineMode != CaptureEngineMode.WIRELESS_DEBUG) return
        if (failMessage == lastPromptedWirelessError) return
        val isWirelessError = failMessage.contains("无线调试") ||
            failMessage.contains("无感采集") ||
            failMessage.contains("Wireless Debugging", ignoreCase = true) ||
            failMessage.contains("ワイヤレスデバッグ")
        if (!isWirelessError) return
        lastPromptedWirelessError = failMessage

        runCatching { wirelessPromptDialog?.dismiss() }
        wirelessPromptDialog = UiComponents.showStyleBDialog(
            activity = this,
            palette = palette,
            title = getString(R.string.wireless_dialog_recovery_title),
            message = failMessage,
            footnote = getString(R.string.wireless_dialog_recovery_footnote),
            primaryLabel = getString(R.string.wireless_dialog_open_wireless_debug),
            onPrimary = {
                CastService.lastFailMessage = ""
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            },
            secondaryLabel = getString(R.string.wireless_dialog_open_guide),
            onSecondary = {
                CastService.lastFailMessage = ""
                startActivity(
                    Intent(this, WirelessDebugGuideActivity::class.java)
                        .putExtra(WirelessDebugGuideActivity.EXTRA_AUTO_VERIFY, true),
                )
            },
            tertiaryLabel = getString(R.string.wireless_dialog_switch_standard),
            onTertiary = {
                CastService.lastFailMessage = ""
                settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                refreshTargetFromStore()
                showTransientStatus(getString(R.string.main_status_switched_standard))
            },
            onDismissed = { wirelessPromptDialog = null },
        )
    }

    private fun refreshTargetFromStore() {
        val sel = store.loadSelection()
        if (sel != null) {
            selectedPackage = sel.packageName
            selectedLabel = sel.label
            selectedCert = sel.signingCertSha256
        }
        if (::mainTargetTitle.isInitialized) {
            val globalWireless = settingsStore.captureEngineMode == CaptureEngineMode.WIRELESS_DEBUG &&
                settingsStore.captureScopeMode == CaptureScopeMode.GLOBAL_MEDIA
            if (globalWireless) {
                mainTargetTitle.text = getString(R.string.main_source_global_title)
                mainTargetSubtitle.text = getString(R.string.main_source_global_sub)
                mainTargetIcon.setImageResource(R.drawable.ic_ui_music_note)
                mainTargetIcon.setColorFilter(palette.accentBright)
                if (::mainTargetAutoBadge.isInitialized) mainTargetAutoBadge.visibility = View.GONE
            } else if (selectedPackage.isBlank()) {
                mainTargetTitle.text = getString(R.string.main_source_unselected)
                mainTargetSubtitle.text = getString(R.string.main_source_unselected_sub_manage)
                mainTargetIcon.setImageResource(R.drawable.ic_ui_music_note)
                mainTargetIcon.setColorFilter(palette.accentBright)
                if (::mainTargetAutoBadge.isInitialized) mainTargetAutoBadge.visibility = View.GONE
            } else {
                mainTargetTitle.text = getString(R.string.main_source_selected, selectedLabel)
                mainTargetSubtitle.text = selectedPackage
                UiIconCache.bindAppIcon(this, mainTargetIcon, selectedPackage)
                if (::mainTargetAutoBadge.isInitialized) {
                    mainTargetAutoBadge.visibility = if (currentSelectionAutoDetected) View.VISIBLE else View.GONE
                }
            }
            if (!nowPlaying.available) {
                bindNowPlaying()
            }
        }
        renderQuickSwitchRow()
    }

    private fun loadLaunchableAppsAsync() {
        Thread {
            val apps = AndroidLaunchableApps.list(this)
            val favs = store.ensureFavoriteSourcesInitialized(apps, selfPackage = packageName)
            main.post {
                cachedLaunchableApps = apps
                favoriteSources = favs
                renderQuickSwitchRow()
                maybeAutoSelectFromNowPlaying()
            }
        }.start()
    }

    private fun syncCastStateFromService(forceSyncRoute: Boolean = false) {
        if (!::settingsStore.isInitialized || !::store.isInitialized || !::routeState.isInitialized) return
        val serviceRunning = CastService.running.get()
        if (stopRequested) {
            if (!serviceRunning) {
                stopRequested = false
                wasServiceRunning = false
            }
            return
        }
        if (serviceRunning) {
            wasServiceRunning = true
            castRequested = false
            val activeIps = CastService.activeReceiverIps()
            if (activeIps.isNotEmpty() && (forceSyncRoute || routeState.selectedSpeakerIds.isEmpty())) {
                val synced = CastRouteSelection.syncActiveSpeakers(routeState, activeIps)
                if (synced != routeState) {
                    routeState = synced
                    if (::deviceList.isInitialized) renderDevices()
                }
            }
            if (CastService.source == "app") {
                val activePkg = CastService.targetPackage
                val activeLabel = CastService.targetLabel
                val activeAuto = CastService.targetAutoDetected
                if (activePkg.isNotBlank() &&
                    (activePkg != selectedPackage || activeAuto != currentSelectionAutoDetected)
                ) {
                    selectedPackage = activePkg
                    selectedLabel = activeLabel.ifBlank { activePkg }
                    currentSelectionAutoDetected = activeAuto
                    refreshTargetFromStore()
                    nowPlayingTracker?.refresh()
                }
            }
            if (lastStatusKind == StatusBannerKind.WARNING_ALERT) {
                clearStatusBanner()
            }
            CastService.consumeHotSwitchFeedback()?.let { feedback ->
                showTransientStatus(feedback, durationMs = 3000L)
            }
        } else {
            val stoppedFromRunning = wasServiceRunning
            wasServiceRunning = false
            val failMsg = CastService.lastFailMessage
            if (failMsg.isNotBlank()) {
                castRequested = false
                showAlertStatus(failMsg)
                maybePromptWirelessRecovery(failMsg)
                if (pendingStart == null && routeState.selectedSpeakerIds.isNotEmpty()) {
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    if (::deviceList.isInitialized) renderDevices()
                }
            } else if (!castRequested && pendingStart == null &&
                (stoppedFromRunning || routeState.selectedSpeakerIds.isNotEmpty())
            ) {
                if (routeState.selectedSpeakerIds.isNotEmpty()) {
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    if (::deviceList.isInitialized) renderDevices()
                }
                if (stoppedFromRunning) {
                    clearStatusBanner()
                }
            }
        }
    }

    private fun maybeAutoSelectFromNowPlaying() {
        if (!::settingsStore.isInitialized || !::store.isInitialized) return
        // 投送进行中由 CastService 后台统一热切并回写状态，避免前后台双写导致 UI 与实际捕获不一致。
        if (CastService.running.get() || castRequested) return
        val favs = if (favoriteSources.isNotEmpty() || store.hasInitializedFavoriteSources()) {
            favoriteSources
        } else {
            return
        }
        val candidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = latestSessionSnapshots,
            favoriteSources = favs,
            isCasting = false,
            currentSelectedPackage = selectedPackage,
            selfPackage = packageName,
        )
        val candidatePkg = candidate?.packageName ?: nowPlaying.sourcePackage
        val candidatePlaying = candidate?.isPlaying ?: nowPlaying.isPlaying
        val decision = AutoSourceSelectionPolicy.decide(
            AutoSourceSelectionPolicy.Input(
                globalAutoDetectEnabled = settingsStore.autoDetectAudioSource,
                isCasting = false,
                sourceMode = settingsStore.sourceMode,
                currentSelectedPackage = selectedPackage,
                selfPackage = packageName,
                nowPlayingPackage = candidatePkg,
                nowPlayingIsPlaying = candidatePlaying,
                favoriteSources = favs,
                manualOverrideBlockedPackage = manualOverrideBlockedPkg,
            ),
        )
        val targetPkg = (decision as? AutoSourceSelectionPolicy.Decision.AutoSelect)?.packageName ?: return
        if (pendingAutoResolvePkg == targetPkg) return
        pendingAutoResolvePkg = targetPkg

        val labelHint = cachedLaunchableApps.firstOrNull { it.packageName == targetPkg }?.label
            ?: runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPkg, 0)).toString()
            }.getOrDefault(targetPkg)

        Thread {
            val resolver = CaptureTargetResolver(AndroidPackageIdentityLookup(this))
            val result = resolver.resolveForCapture(targetPkg)
            main.post {
                if (pendingAutoResolvePkg == targetPkg) pendingAutoResolvePkg = null
                if (CastService.running.get() || castRequested) return@post
                val stillCandidate = latestSessionSnapshots.any { it.packageName == targetPkg } ||
                    nowPlaying.sourcePackage == targetPkg
                if (!stillCandidate) return@post
                if (result is CaptureTargetResult.Ok) {
                    store.saveSelection(
                        TargetAppSelection(
                            packageName = result.packageName,
                            label = labelHint,
                            signingCertSha256 = result.signingCertSha256,
                        ),
                    )
                    selectedPackage = result.packageName
                    selectedLabel = labelHint
                    selectedCert = result.signingCertSha256
                    currentSelectionAutoDetected = true
                    lastGrantSession = null
                    refreshTargetFromStore()
                }
            }
        }.start()
    }

    private fun applyQuickChipBackground(chip: View, isCurrent: Boolean, dragging: Boolean) {
        val bgColor = if (isCurrent) palette.rowSelectedBg else palette.controlSurface
        val borderColor = when {
            dragging -> palette.accent
            isCurrent -> palette.rowSelectedBorder
            else -> palette.panelBorder
        }
        val strokeW = if (dragging) dp(2).coerceAtLeast(2) else dp(1).coerceAtLeast(1)
        chip.background = UiComponents.rippleSurface(
            fillColor = bgColor,
            radiusPx = dpf(999f),
            rippleColor = if (isCurrent) palette.rippleOnSelected else palette.ripple,
            strokeColor = borderColor,
            strokeWidthPx = strokeW,
        )
    }

    private fun renderQuickSwitchRow() {
        if (!::quickSwitchRow.isInitialized) return
        quickChipDragController?.cancel()
        quickChipSelectedFlags.clear()
        quickSwitchRow.removeAllViews()
        val useFavorites = store.hasInitializedFavoriteSources()
        val candidates = if (useFavorites) {
            LaunchableAppCatalog.quickSwitchCandidatesFromFavorites(
                apps = cachedLaunchableApps,
                favoriteSources = favoriteSources,
                currentPackage = selectedPackage.takeIf { it.isNotBlank() },
                limit = favoriteSources.size.coerceAtLeast(4),
                selfPackage = packageName,
            )
        } else {
            LaunchableAppCatalog.quickSwitchCandidates(
                apps = cachedLaunchableApps,
                recentPackages = store.loadRecentSelections().map { it.packageName },
                currentPackage = selectedPackage.takeIf { it.isNotBlank() },
                limit = 4,
                selfPackage = packageName,
            )
        }
        if (candidates.isEmpty()) {
            quickSwitchRow.visibility = View.GONE
            return
        }
        quickSwitchRow.visibility = View.VISIBLE
        val favoritePkgSet = if (useFavorites) favoriteSources.map { it.packageName }.toSet() else emptySet()
        candidates.forEach { app ->
            val isCurrent = app.packageName == selectedPackage
            val txtColor = if (isCurrent) palette.onSelectedPrimary else palette.textPrimary
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(5), dp(11), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                isClickable = true
                isFocusable = true
                if (useFavorites && app.packageName in favoritePkgSet) {
                    tag = app.packageName
                }
                setOnClickListener { quickSelectApp(app) }
            }
            quickChipSelectedFlags[chip] = isCurrent
            applyQuickChipBackground(chip, isCurrent = isCurrent, dragging = false)
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(6) }
                setColorFilter(txtColor)
            }
            UiIconCache.bindAppIcon(this, icon, app.packageName)
            val label = TextView(this).apply {
                text = app.label
                textSize = 12f
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(txtColor)
            }
            chip.addView(icon)
            chip.addView(label)
            quickSwitchRow.addView(chip)
        }
    }

    private fun quickSelectApp(app: LaunchableApp) {
        if (app.packageName == selectedPackage && settingsStore.sourceMode != "silent") {
            return
        }
        Thread {
            val resolver = CaptureTargetResolver(AndroidPackageIdentityLookup(this))
            when (val r = resolver.resolveForCapture(app.packageName)) {
                is CaptureTargetResult.Fail -> main.post { showAlertStatus(r.messageZh) }
                is CaptureTargetResult.Ok -> {
                    val sel = TargetAppSelection(
                        packageName = r.packageName,
                        label = app.label,
                        signingCertSha256 = r.signingCertSha256,
                    )
                    main.post {
                        applyManualTargetSelection(sel)
                    }
                }
            }
        }.start()
    }

    private fun applyManualTargetSelection(selection: TargetAppSelection) {
        val action = ManualSourceSwitchPolicy.decide(
            ManualSourceSwitchPolicy.Input(
                serviceRunning = CastService.running.get(),
                activeServiceSource = CastService.source,
                activeScopeMode = CastService.activeScopeMode,
                activeTargetPackage = CastService.targetPackage,
                pickedPackage = selection.packageName,
            ),
        )
        manualOverrideBlockedPkg = nowPlaying.sourcePackage?.takeIf {
            it.isNotBlank() && it != selection.packageName
        }
        settingsStore.sourceMode = "app"
        when (action) {
            ManualSourceSwitchPolicy.Action.HOT_SWITCH_IN_CAST -> {
                // 单 App 投送中热切源：不提前覆盖 store，待 CastService 热切成功后统一落盘，
                // 避免系统限制热切失败回滚原音源时主界面与实际捕获不一致。
                lastGrantSession = null
                service(CastService.ACTION_SWITCH_SOURCE) {
                    putExtra(CastService.EXTRA_TARGET_PACKAGE, selection.packageName)
                    putExtra(CastService.EXTRA_TARGET_LABEL, selection.label)
                    putExtra(CastService.EXTRA_MANUAL_OVERRIDE_BLOCKED_PKG, manualOverrideBlockedPkg.orEmpty())
                }
                showTransientStatus(getString(R.string.main_status_switching_source, selection.label))
                renderQuickSwitchRow()
            }
            ManualSourceSwitchPolicy.Action.KEEP_ACTIVE_TARGET -> {
                // 点选当前已在投送的同一个 App：不断线、不重复重建采集后端。
                CastService.targetAutoDetected = false
                currentSelectionAutoDetected = false
                selectedPackage = selection.packageName
                selectedLabel = selection.label
                selectedCert = selection.signingCertSha256
                store.saveSelection(selection)
                refreshTargetFromStore()
                if (lastStatusKind == StatusBannerKind.WARNING_ALERT) {
                    clearStatusBanner()
                }
            }
            ManualSourceSwitchPolicy.Action.PERSIST_SELECTION_ONLY -> {
                selectedPackage = selection.packageName
                selectedLabel = selection.label
                selectedCert = selection.signingCertSha256
                currentSelectionAutoDetected = false
                store.saveSelection(selection)
                lastGrantSession = null
                refreshTargetFromStore()
                if (lastStatusKind == StatusBannerKind.WARNING_ALERT) {
                    clearStatusBanner()
                }
            }
        }
    }

    private fun openAppPicker() {
        // 进入「常用音源与选择」页仅管理名单或切换目标，不中断当前投送；
        // 若用户选定新音源返回，由 onActivityResult(REQ_APP_PICKER) 走不断线热切换。
        startActivityForResult(Intent(this, AppPickerActivity::class.java), REQ_APP_PICKER)
    }

    private fun localIp(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val ip = wm.connectionInfo.ipAddress
        return if (ip == 0) "0.0.0.0" else "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun discover(forceRefreshNames: Boolean = false) {
        if (discovering) return
        discovering = true
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
            Log.i(CastService.TAG, "discoverJsonEx(force=$forceRefreshNames): $r")
            main.post {
                discovering = false
                stopRefreshSpinner()
                val parsed = CastRouteSelection.parseDiscoverJsonResult(r)
                if (parsed == null || parsed.error != null) {
                    if (!CastService.running.get() || stopRequested) {
                        showAlertStatus(getString(R.string.main_err_discover_failed))
                    }
                    return@post
                }
                parsed.rows.forEach { row ->
                    if (row.logLine.isNotBlank()) Log.i(CastService.TAG, row.logLine)
                }
                val mergedDevices = parsed.merged
                deviceStore.saveDevices(parsed.cacheable)
                val prevVisible = CastRouteSelection.filterVisibleDevices(
                    routeState.devices,
                    settingsStore.showAllDiscovered,
                )
                var next = CastRouteSelection.mergeDiscovered(routeState, mergedDevices)
                if (CastService.running.get() && !stopRequested) {
                    val activeIps = CastService.activeReceiverIps()
                    if (activeIps.isNotEmpty()) {
                        next = CastRouteSelection.syncActiveSpeakers(next, activeIps)
                    }
                }
                val hadPendingIps = !pendingIps.isNullOrEmpty()
                pendingIps?.forEach { ip ->
                    if (next.devices.any { it.id == ip && it.castable }) {
                        next = CastRouteSelection.setSpeakerChecked(next, ip, true).state
                    }
                }
                pendingIps = null
                lastDiscoverCompletedAtMs = System.currentTimeMillis()
                routeState = next
                val nextVisible = CastRouteSelection.filterVisibleDevices(
                    next.devices,
                    settingsStore.showAllDiscovered,
                )
                val castableSinks = nextVisible.count { it.isCastSink && it.castable }
                if (::deviceCountView.isInitialized) {
                    deviceCountView.text = if (castableSinks > 0) {
                        getString(R.string.main_found_castable_count, castableSinks)
                    } else {
                        getString(R.string.main_multiroom_support)
                    }
                }
                val listChanged = hadPendingIps ||
                    prevVisible != nextVisible ||
                    (::deviceList.isInitialized && deviceList.childCount != nextVisible.size)
                if (listChanged) {
                    if (draggingSpeakerVolume) {
                        pendingDeviceListRender = true
                    } else {
                        renderDevices()
                    }
                } else {
                    val stats = runCatching { JSONObject(CastService.lastStats) }.getOrNull()
                    updateLatencyAndSubtitleIndicators(stats)
                    updateHeaderStatusPill()
                }
                val foundCount = parsed.rows.size
                if (!CastService.running.get() || stopRequested) {
                    if (foundCount == 0 && next.devices.none { it.isCastSink }) {
                        showAlertStatus(getString(R.string.main_err_no_speakers_found))
                    } else if (lastStatusKind == StatusBannerKind.WARNING_ALERT) {
                        clearStatusBanner()
                    }
                }
            }
        }.start()
    }

    private fun handleAutomation(i: Intent?) {
        i ?: return
        i.getStringExtra("ips")?.let {
            pendingIps = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.toSet()
        }
        when (i.getStringExtra("source")) {
            "app", "system" -> settingsStore.sourceMode = "app"
            "silent" -> settingsStore.sourceMode = "silent"
        }
        i.getStringExtra("target_package")?.let { pkg ->
            selectedPackage = pkg
            selectedLabel = i.getStringExtra("target_label") ?: pkg
            selectedCert = i.getStringExtra("target_cert").orEmpty()
            refreshTargetFromStore()
        }
        AuthModeResolve.fromIntent(i)?.let { settingsStore.authMode = it }
        i.getStringExtra("ref")?.let { r ->
            settingsStore.refFunction = if (r == "自动") "" else r
        }
        if (i.getBooleanExtra("discover", false)) discover()
        if (i.getBooleanExtra("stop", false)) {
            castRequested = false
            stopRequested = true
            pendingStart = null
            stopCast()
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            clearStatusBanner()
        }
        if (i.getBooleanExtra("pause", false)) service(CastService.ACTION_PAUSE)
        if (i.getBooleanExtra("resume", false)) service(CastService.ACTION_RESUME)
        i.getStringExtra("add")?.let { ip ->
            val t = CastRouteSelection.setSpeakerChecked(routeState, ip, true)
            routeState = t.state
            renderDevices()
            applyEffects(t.effects)
        }
        i.getStringExtra("remove")?.let { ip ->
            val t = CastRouteSelection.setSpeakerChecked(routeState, ip, false)
            routeState = t.state
            renderDevices()
            applyEffects(t.effects)
        }
        if (i.hasExtra("set_volume")) setVolume(i.getStringExtra("ip").orEmpty(), i.getIntExtra("set_volume", 0))
        if (i.getBooleanExtra("autostart", false)) {
            val want = pendingIps
            if (!want.isNullOrEmpty()) {
                discover()
                main.postDelayed({
                    var s = routeState
                    want.forEach { ip ->
                        if (s.devices.none { it.id == ip }) {
                            s = CastRouteSelection.mergeDiscovered(
                                s,
                                s.devices.filter { it.kind != Kind.THIS_DEVICE } +
                                    CastRouteSelection.Device(
                                        ip,
                                        ip,
                                        Kind.SPEAKER,
                                        true,
                                        protocol = CastProtocol.MIPLAY,
                                    ),
                            )
                        }
                        val t = CastRouteSelection.setSpeakerChecked(s, ip, true)
                        s = t.state
                        routeState = s
                        renderDevices()
                        applyEffects(t.effects)
                    }
                }, 3500)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        suppressNextResumeAutoDiscover = true
        when (requestCode) {
            REQ_PROJECTION -> {
                val p = pendingStart ?: return
                pendingStart = null
                if (resultCode != RESULT_OK || data == null) {
                    castRequested = false
                    showTransientStatus(getString(R.string.main_status_projection_cancelled))
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    renderDevices()
                    lastGrantSession = null
                    return
                }
                val currentIps = routeState.selectedSpeakerIds
                if (currentIps.isEmpty()) {
                    castRequested = false
                    clearStatusBanner()
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    renderDevices()
                    lastGrantSession = null
                    return
                }
                p.putExtra(CastService.EXTRA_IPS, currentIps.joinToString(","))
                castRequested = true
                CastService.primeConnectingReceivers(currentIps)
                updateLatencyAndSubtitleIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
                updateHeaderStatusPill()
                clearStatusBanner()
                startForegroundService(
                    p.putExtra(CastService.EXTRA_PROJ_CODE, resultCode)
                        .putExtra(CastService.EXTRA_PROJ_DATA, data),
                )
            }
            REQ_APP_PICKER -> {
                if (store.hasInitializedFavoriteSources()) {
                    favoriteSources = store.loadFavoriteSources()
                }
                if (resultCode != RESULT_OK || data == null) {
                    renderQuickSwitchRow()
                    maybeAutoSelectFromNowPlaying()
                    return
                }
                val pickedPkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE).orEmpty()
                val pickedLabel = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty()
                val pickedCert = data.getStringExtra(AppPickerActivity.EXTRA_CERT).orEmpty()
                if (pickedPkg.isBlank()) {
                    renderQuickSwitchRow()
                    return
                }
                applyManualTargetSelection(
                    TargetAppSelection(
                        packageName = pickedPkg,
                        label = pickedLabel.ifBlank { pickedPkg },
                        signingCertSha256 = pickedCert,
                    ),
                )
            }
            REQ_SETTINGS -> {
                if (data?.getBooleanExtra(SettingsActivity.EXTRA_DEVICES_CHANGED, false) == true) {
                    val saved = deviceStore.loadDevices()
                    routeState = CastRouteSelection.mergeDiscovered(routeState, saved)
                    renderDevices()
                }
                if (data?.getBooleanExtra(SettingsActivity.EXTRA_TRIGGER_DISCOVER, false) == true) {
                    discover(forceRefreshNames = true)
                }
            }
        }
    }

    private fun service(action: String, extra: Intent.() -> Unit = {}) {
        startService(Intent(this, CastService::class.java).setAction(action).apply(extra))
    }

    private fun stopCast() = service(CastService.ACTION_STOP)

    private fun setVolume(ip: String, v: Int, confirmed: Boolean = false) = service(CastService.ACTION_VOLUME) {
        putExtra(CastService.EXTRA_VOLUME, v)
        putExtra(CastService.EXTRA_VOLUME_CONFIRMED, confirmed)
        if (ip.isNotEmpty()) putExtra(CastService.EXTRA_IP, ip)
    }

    private fun volumeWarningColor(onSelectedRow: Boolean): Int =
        if (onSelectedRow) Color.rgb(217, 119, 6) else palette.statusWarning

    private fun applyMasterVolumeVisual(value: Int) {
        if (!::volumeBar.isInitialized || !::volumePctView.isInitialized) return
        val policy = settingsStore.volumeGuardPolicy()
        val clamped = value.coerceIn(0, 100)
        val warning = VolumeSafetyGuard.isInHighVolumeZone(policy, clamped)
        volumeBar.updateGuardVisual(warning = warning)
        volumePctView.text = "$clamped%"
        volumePctView.setTextColor(
            if (warning) volumeWarningColor(onSelectedRow = false) else palette.textSecondary,
        )
    }

    private fun applySpeakerVolumeVisual(deviceId: String, value: Int) {
        val views = speakerVolumeViews[deviceId] ?: return
        val policy = settingsStore.volumeGuardPolicy()
        val clamped = value.coerceIn(0, 100)
        val warning = VolumeSafetyGuard.isInHighVolumeZone(policy, clamped)
        views.first.updateGuardVisual(warning = warning)
        views.second.text = "$clamped%"
        views.second.setTextColor(
            if (warning) volumeWarningColor(onSelectedRow = true) else palette.onSelectedSecondary,
        )
    }

    private fun refreshAllVolumeGuardVisuals() {
        applyMasterVolumeVisual(masterVolume)
        speakerVolumeViews.keys.forEach { id ->
            val v = speakerVolume[id] ?: masterVolume
            applySpeakerVolumeVisual(id, v)
        }
    }

    private fun revertMasterVolumeVisuals() {
        if (::volumeBar.isInitialized) {
            volumeBar.progress = masterVolume
        }
        applyMasterVolumeVisual(masterVolume)
        routeState.selectedSpeakerIds.forEach { id ->
            val prev = speakerVolume[id] ?: masterVolume
            speakerVolumeViews[id]?.first?.progress = prev
            applySpeakerVolumeVisual(id, prev)
        }
    }

    private fun commitMasterVolume(target: Int, confirmed: Boolean) {
        val clamped = target.coerceIn(0, 100)
        val policy = settingsStore.volumeGuardPolicy()
        masterVolume = clamped
        masterVolumeConfirmedHigh = when {
            !VolumeSafetyGuard.isInHighVolumeZone(policy, clamped) -> false
            confirmed -> true
            else -> masterVolumeConfirmedHigh
        }
        if (::volumeBar.isInitialized) {
            volumeBar.progress = clamped
        }
        applyMasterVolumeVisual(clamped)
        routeState.selectedSpeakerIds.forEach { id ->
            speakerVolume[id] = clamped
            speakerVolumeViews[id]?.first?.progress = clamped
            applySpeakerVolumeVisual(id, clamped)
        }
        if (CastService.running.get() || castRequested) {
            setVolume("", clamped, confirmed = confirmed)
        }
    }

    private fun revertSpeakerVolumeVisual(deviceId: String) {
        val prev = speakerVolume[deviceId] ?: masterVolume
        speakerVolumeViews[deviceId]?.first?.progress = prev
        applySpeakerVolumeVisual(deviceId, prev)
    }

    private fun commitSpeakerVolume(deviceId: String, target: Int, confirmed: Boolean) {
        val clamped = target.coerceIn(0, 100)
        speakerVolume[deviceId] = clamped
        speakerVolumeViews[deviceId]?.first?.progress = clamped
        applySpeakerVolumeVisual(deviceId, clamped)
        if (CastService.running.get() || castRequested) {
            setVolume(deviceId, clamped, confirmed = confirmed)
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

    internal fun triggerMasterVolumeChangeForTest(target: Int): Dialog? {
        handleMasterVolumeReleased(target)
        return volumeConfirmDialog?.takeIf { it.isShowing }
    }

    internal fun triggerSpeakerVolumeChangeForTest(deviceId: String, deviceName: String, target: Int): Dialog? {
        handleSpeakerVolumeReleased(deviceId, deviceName, target)
        return volumeConfirmDialog?.takeIf { it.isShowing }
    }

    internal fun currentMasterVolumeForTest(): Int = masterVolume

    internal fun currentSpeakerVolumeForTest(deviceId: String): Int? = speakerVolume[deviceId]

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

    /** 仅用于 JVM 截图预览单测注入确定性 UI 状态，不触发网络扫描或原生音频捕获。 */
    internal fun applyUiPreviewStateForTest(
        isDark: Boolean,
        previewNowPlaying: NowPlayingInfo,
        previewDevices: List<CastRouteSelection.Device>,
        previewSelectedSpeakerIds: Set<String> = emptySet(),
        previewLaunchableApps: List<com.miaocast.app.capture.LaunchableApp> = emptyList(),
        previewFavoriteSources: List<FavoriteAudioSource>? = null,
        previewSelectedPackage: String = "",
        previewSelectedLabel: String = "",
        previewAutoDetected: Boolean = false,
        previewMasterVolume: Int = 46,
        previewSpeakerVolumes: Map<String, Int> = emptyMap(),
        previewCasting: Boolean = false,
        previewStats: JSONObject? = null,
        previewStatusText: String = "",
    ) {
        main.removeCallbacks(refreshRunnable)
        stopRefreshSpinner()
        nowPlayingTracker?.stop()
        settingsStore.themeMode = if (isDark) ThemeMode.DARK else ThemeMode.LIGHT
        if (previewFavoriteSources != null) {
            store.saveFavoriteSources(previewFavoriteSources)
            favoriteSources = previewFavoriteSources
        } else {
            favoriteSources = emptyList()
        }
        if (previewSelectedPackage.isNotBlank()) {
            store.saveSelection(
                com.miaocast.app.capture.TargetAppSelection(
                    packageName = previewSelectedPackage,
                    label = previewSelectedLabel.ifBlank { previewSelectedPackage },
                    signingCertSha256 = "preview-cert",
                ),
            )
        } else {
            store.clearSelection()
        }
        selectedPackage = previewSelectedPackage
        selectedLabel = previewSelectedLabel
        currentSelectionAutoDetected = previewAutoDetected
        cachedLaunchableApps = previewLaunchableApps
        masterVolume = previewMasterVolume.coerceIn(0, 100)
        speakerVolume.clear()
        speakerVolume.putAll(previewSpeakerVolumes)
        castRequested = previewCasting
        stopRequested = false
        wasServiceRunning = previewCasting
        CastService.running.set(previewCasting)
        CastService.setActiveReceiverIps(if (previewCasting) previewSelectedSpeakerIds else emptySet())
        CastService.lastFailMessage = ""
        CastService.lastStats = previewStats?.toString().orEmpty()
        nowPlaying = previewNowPlaying
        nowPlayingUpdatedAtMs = System.currentTimeMillis()
        var nextRoute = CastRouteSelection.initial(previewDevices)
        previewSelectedSpeakerIds.forEach { speakerId ->
            nextRoute = CastRouteSelection.setSpeakerChecked(nextRoute, speakerId, true).state
        }
        routeState = nextRoute
        lastStatusMessage = ""
        lastStatusKind = StatusBannerKind.HIDDEN
        buildUi()
        updateLatencyAndSubtitleIndicators(previewStats)
        updateHeaderStatusPill()
        if (previewStatusText.contains("异常") || previewStatusText.contains("失败") || previewStatusText.contains("未授予")) {
            showAlertStatus(previewStatusText)
        }
    }
}
