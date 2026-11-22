package com.miplay.sender

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import com.miplay.sender.capture.AndroidLaunchableApps
import com.miplay.sender.capture.AndroidPackageIdentityLookup
import com.miplay.sender.capture.AutoSourceSelectionPolicy
import com.miplay.sender.capture.CaptureEngineCapabilities
import com.miplay.sender.capture.CaptureEngineMode
import com.miplay.sender.capture.CaptureScopeMode
import com.miplay.sender.capture.CaptureTargetResolver
import com.miplay.sender.capture.CaptureTargetResult
import com.miplay.sender.capture.FavoriteAudioSource
import com.miplay.sender.capture.LaunchableApp
import com.miplay.sender.capture.LaunchableAppCatalog
import com.miplay.sender.capture.PrefsTargetAppStore
import com.miplay.sender.capture.TargetAppSelection
import com.miplay.sender.capture.shell.PairingNotificationGuide
import com.miplay.sender.capture.shell.WirelessDebugGuideActivity
import com.miplay.sender.cast.ActiveMediaSessionSelector
import com.miplay.sender.cast.CastProtocol
import com.miplay.sender.cast.CastRouteSelection
import com.miplay.sender.cast.CastRouteSelection.Effect
import com.miplay.sender.cast.CastRouteSelection.Kind
import com.miplay.sender.cast.NowPlayingInfo
import com.miplay.sender.cast.NowPlayingTracker
import com.miplay.sender.cast.PrefsDiscoveredDeviceStore
import com.miplay.sender.cast.ProtocolBadge
import com.miplay.sender.compat.AcceptanceAudioDefaults
import com.miplay.sender.compat.BlurCompat
import com.miplay.sender.compat.NotificationPermissionCompat
import com.miplay.sender.compat.WindowInsetsApplyCompat
import com.miplay.sender.ui.LatencyModePreset
import com.miplay.sender.ui.LatencyModeSelection
import com.miplay.sender.ui.LatencyModeTone
import com.miplay.sender.ui.LinearDragReorderController
import com.miplay.sender.ui.PrefsCastSettingsStore
import com.miplay.sender.ui.ThemeMode
import com.miplay.sender.ui.UiComponents
import com.miplay.sender.ui.UiIconCache
import com.miplay.sender.ui.UiPalette
import com.miplay.sender.update.AppUpdateManager
import com.miplay.sender.update.UpdateCheckState
import com.miplay.sender.update.UpdateDialogController
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
    private lateinit var volumeBar: SeekBar
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
    private val speakerVolume = linkedMapOf<String, Int>()
    private val latencyIndicators = linkedMapOf<String, LatencyIndicatorViews>()
    private val speakerSubtitles = linkedMapOf<String, TextView>()
    private val speakerVolumeViews = linkedMapOf<String, Pair<SeekBar, TextView>>()
    private var castRequested: Boolean = false
    private var stopRequested: Boolean = false
    private var wasServiceRunning: Boolean = false
    private var discovering: Boolean = false
    private var refreshAnim: ObjectAnimator? = null
    private var lastRenderedDark: Boolean = true
    private var lastPromptedWirelessError: String = ""
    private var lastStatusMessage: String = "本机出声 · 点击下方音箱开始投送"

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
            tickNowPlayingProgress()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        store = PrefsTargetAppStore(this)
        deviceStore = PrefsDiscoveredDeviceStore(this)
        settingsStore = PrefsCastSettingsStore(this)
        masterVolume = AcceptanceAudioDefaults.speakerPremutePercent(BuildConfig.IS_ACCEPTANCE)
        val cachedDevices = deviceStore.loadDevices()
        routeState = CastRouteSelection.initial(
            listOf(CastRouteSelection.Device("this", "本机播放", Kind.THIS_DEVICE, true)) + cachedDevices,
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
        if (intent?.extras == null) {
            discover()
            main.postDelayed(autoUpdateCheckRunnable, 1500)
        }
        handleAutomation(intent)
    }

    override fun onDestroy() {
        AppUpdateManager.removeListener(updateCheckListener)
        main.removeCallbacks(autoUpdateCheckRunnable)
        runCatching { latencyPopup?.dismiss() }
        latencyPopup = null
        runCatching { latencyDetailsDialog?.dismiss() }
        latencyDetailsDialog = null
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
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
        if (store.hasInitializedFavoriteSources()) {
            favoriteSources = store.loadFavoriteSources()
        }
        val wantDark = settingsStore.isDark(this)
        if (wantDark != lastRenderedDark) {
            buildUi()
        }
        refreshTargetFromStore()
        syncCastStateFromService(forceSyncRoute = true)
        updateLatencyCapsuleUi()
        refreshUpdateUi()
        renderDevices()
        nowPlayingTracker?.refresh()
        maybeAutoSelectFromNowPlaying()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            if (p == Manifest.permission.RECORD_AUDIO && !ok) {
                setStatusText("未授予 RECORD_AUDIO：无法进行播放捕获（不采集麦克风）")
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
            val suffix = if (bannerDecision.versionsBehind > 1) {
                "（${bannerDecision.versionsBehind} 个版本更新）"
            } else {
                ""
            }
            updateBannerText?.text = "发现新版本 ${bannerDecision.latestTag}$suffix"
            banner.visibility = View.VISIBLE
        }
    }

    private fun setStatusText(msg: String) {
        lastStatusMessage = msg
        if (::statusLine.isInitialized) statusLine.text = msg
    }

    private fun buildUi() {
        runCatching { latencyPopup?.dismiss() }
        latencyPopup = null
        runCatching { latencyDetailsDialog?.dismiss() }
        latencyDetailsDialog = null
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
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
            text = "本机出声"
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
            contentDescription = "切换播放延迟模式"
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

        val settingsBtn = UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_settings, "设置") {
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
            text = "查看"
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
            contentDescription = "关闭更新提示"
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
            contentDescription = "打开正在播放的音乐应用"
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
            text = "未知标题"
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
            text = "未取得正在播放"
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
        controls.addView(transportBtn(R.drawable.ic_ui_skip_prev, "上一首") { nowPlayingTracker?.skipPrevious() })
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
            contentDescription = "播放或暂停"
            setOnClickListener { nowPlayingTracker?.playPause() }
            addView(playBtnIcon)
        }
        controls.addView(playWrap)
        controls.addView(transportBtn(R.drawable.ic_ui_skip_next, "下一首") { nowPlayingTracker?.skipNext() })
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
                    text = "开启通知使用权以显示曲目与自动识别音源"
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

        // —— 总音量条（带实时百分比，拖动时增量更新音箱行不整表重建） ——
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
        volumePctView = TextView(this).apply {
            text = "$masterVolume%"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.textSecondary)
            gravity = Gravity.END
            minWidth = dp(34)
        }
        volumeBar = UiComponents.styledSeekBar(
            context = this,
            activeColor = palette.accent,
            trackColor = palette.trackIdle,
            thumbFillColor = Color.WHITE,
            thumbStrokeColor = palette.accent,
        ).apply {
            max = 100
            progress = masterVolume
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    masterVolume = p
                    volumePctView.text = "$p%"
                    if (fromUser) {
                        routeState.selectedSpeakerIds.forEach { id ->
                            speakerVolume[id] = p
                            speakerVolumeViews[id]?.let { (bar, pct) ->
                                bar.progress = p
                                pct.text = "$p%"
                            }
                        }
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (CastService.running.get() || castRequested) {
                        setVolume("", masterVolume)
                    }
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
            text = "投送音源：未选择"
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
            text = "自动识别"
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
            text = "点击选择需要投送的音乐或音频 App"
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
            label = "管理",
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
                text = "输出设备"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        deviceCountView = TextView(this).apply {
            text = "支持多音箱同频投送"
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
            contentDescription = "重新发现输出设备"
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

        statusLine = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 11.5f
            text = lastStatusMessage
            setPadding(dp(4), dp(8), dp(4), dp(2))
        }
        mainCard.addView(statusLine)

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
            failed -> palette.statusError to "投送异常"
            running && activeCount > 0 -> palette.statusSuccess to "投送中 · $activeCount 台"
            running -> palette.statusWarning to "连接中…"
            castRequested && selectedCount > 0 -> palette.statusWarning to "连接中…"
            settingsStore.sourceMode == "silent" -> palette.textTertiary to "静音测试模式"
            else -> palette.textTertiary to "本机出声"
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
        latencyPillLabel.text = sel.capsuleLabelZh
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
                    title = preset.nameZh,
                    trailing = preset.shortMsZh,
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
                    title = "自定义",
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
                        text = "档位说明"
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
        if (CastService.running.get()) {
            startService(
                Intent(this, CastService::class.java)
                    .setAction(CastService.ACTION_SET_DELAY)
                    .putExtra(CastService.EXTRA_DELAY, effectiveMs),
            )
            val multiWarn = if (preset == LatencyModePreset.ULTRA && routeState.selectedSpeakerIds.size >= 2) {
                "（多音箱若卡顿建议切均衡）"
            } else {
                "（实时热生效，免重连）"
            }
            setStatusText("已切换至「${preset.nameZh} · ${preset.shortMsZh}」$multiWarn")
        } else {
            setStatusText("已设为「${preset.nameZh} · ${preset.shortMsZh}」，下次连接音箱时生效")
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
                text = "延迟档位说明"
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
                contentDescription = "关闭"
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
                text = "投送中切换档位会通过 TIME_OFFSET 实时热重锚生效，无需断开重连。"
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
                    text = if (active) "${preset.nameZh} · 当前" else preset.nameZh
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            titleRow.addView(
                TextView(this).apply {
                    text = preset.fullMsZh
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(iconCol)
                },
            )
            itemBox.addView(titleRow)
            itemBox.addView(
                TextView(this).apply {
                    text = preset.detailZh
                    textSize = 11.5f
                    setTextColor(palette.textSecondary)
                    setLineSpacing(dpf(1.5f), 1f)
                    setPadding(dp(23), dp(4), 0, 0)
                },
            )
            itemBox.addView(
                TextView(this).apply {
                    text = "冷启动预热：${preset.runwaySummaryZh}"
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
            UiComponents.tonalButton(this, palette, "去设置自定义 ms") {
                dialog.dismiss()
                startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            },
        )
        btnRow.addView(
            UiComponents.tonalButton(this, palette, "知道了", primary = true) {
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

    private fun bindNowPlaying() {
        if (!::nowPlayingFullBox.isInitialized) return
        val available = nowPlaying.available
        nowPlayingFullBox.visibility = if (available) View.VISIBLE else View.GONE
        nowPlayingHintBanner.visibility = if (available) View.GONE else View.VISIBLE

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

        if (!available) return
        titleView.text = NowPlayingInfo.displayTitle(nowPlaying)
        artistView.text = NowPlayingInfo.displayArtist(nowPlaying)
        playBtnIcon.setImageResource(
            if (nowPlaying.isPlaying == true) R.drawable.ic_ui_pause else R.drawable.ic_ui_play,
        )
        val badgePkg = nowPlaying.sourcePackage?.takeIf { it.isNotBlank() }
            ?: selectedPackage.takeIf { it.isNotBlank() }
        if (badgePkg != null) {
            albumAppBadgeWrap.visibility = View.VISIBLE
            UiIconCache.bindAppIcon(this, albumAppBadgeIcon, badgePkg)
        } else {
            albumAppBadgeWrap.visibility = View.GONE
        }
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
        deviceCountView.text = if (castableSinks > 0) "发现 $castableSinks 台可投设备" else "支持多音箱同频投送"
        visible.forEach { d ->
            deviceList.addView(deviceRow(d))
        }
        suppressRoute = false
        val stats = runCatching { JSONObject(CastService.lastStats) }.getOrNull()
        updateLatencyAndSubtitleIndicators(stats)
        updateHeaderStatusPill()
    }

    private fun deviceRow(d: CastRouteSelection.Device): View {
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
            ).apply { bottomMargin = dp(8) }
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
        val displayName = if (d.kind == Kind.THIS_DEVICE) "本机播放" else d.name
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
                d.kind == Kind.THIS_DEVICE -> if (selected) "手机扬声器 · 当前输出" else "手机扬声器"
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
            val volLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val pctView = TextView(this).apply {
                text = "$initialVol%"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(subColor)
                gravity = Gravity.END
                minWidth = dp(32)
            }
            val vol = UiComponents.styledSeekBar(
                context = this,
                activeColor = palette.accent,
                trackColor = palette.trackOnSelected,
                thumbFillColor = Color.WHITE,
                thumbStrokeColor = palette.accent,
            ).apply {
                max = 100
                progress = initialVol
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        pctView.text = "$p%"
                        if (fromUser) speakerVolume[d.id] = p
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        speakerVolume[d.id] = progress
                        if (CastService.running.get() || castRequested) setVolume(d.id, progress)
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
                    setStatusText("已切回本机播放")
                }
                is Effect.StartCast -> startCastTo(e.speakerIds)
                is Effect.AddSpeaker -> {
                    stopRequested = false
                    if (CastService.running.get() || castRequested) {
                        CastService.primeConnectingReceivers(setOf(e.id))
                        updateLatencyAndSubtitleIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
                        service(CastService.ACTION_ADD) { putExtra(CastService.EXTRA_IP, e.id) }
                        setStatusText("正在加入音箱 ${e.id}…")
                    } else if (pendingStart != null) {
                        val ips = routeState.selectedSpeakerIds.joinToString(",")
                        pendingStart?.putExtra(CastService.EXTRA_IPS, ips)
                        setStatusText("已加入待投送列表：${e.id}")
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
                    setStatusText("已移除音箱 ${e.id}")
                }
                is Effect.RejectNonCastable -> {
                    setStatusText("无法投送：${e.reason}")
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
            setStatusText(validation.messageZh)
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            showWirelessPrecheckGuideDialog(speakerIds, validation.messageZh)
            return
        }
        val allowed = validation as CaptureEngineCapabilities.ValidationResult.Allowed
        val needsTargetApp = appMode && allowed.effectiveScope == CaptureScopeMode.SINGLE_APP

        if (needsTargetApp && selectedPackage.isBlank()) {
            setStatusText("请先选择要投送的音频 App")
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            openAppPicker()
            return
        }
        if (appMode &&
            allowed.effectiveEngine == CaptureEngineMode.STANDARD &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            setStatusText("需要音频捕获权限（仅用于播放捕获，不采集麦克风）")
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
            .putExtra(CastService.EXTRA_REF, settingsStore.refFunction)
            .putExtra(CastService.EXTRA_AUTH, AuthModeResolve.coerceMode(settingsStore.authMode))
            .putExtra(
                CastService.EXTRA_LOCAL_MUTE_POLICY,
                if (settingsStore.keepLocalOutput) "keep_local" else "global_mute",
            )
            .putExtra(CastService.EXTRA_CAPTURE_ENGINE, allowed.effectiveEngine.id)
            .putExtra(CastService.EXTRA_CAPTURE_SCOPE, allowed.effectiveScope.id)
        speakerIds.forEach { speakerVolume[it] = masterVolume }
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
            setStatusText(
                if (allowed.effectiveScope == CaptureScopeMode.GLOBAL_MEDIA) {
                    "正在启动无感全局媒体投送…"
                } else {
                    "正在无感连接并投送 $selectedLabel…"
                },
            )
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
            setStatusText("等待系统播放捕获授权（UID 锁定 $selectedLabel）…")
        } else {
            castRequested = true
            CastService.primeConnectingReceivers(speakerIds)
            updateLatencyAndSubtitleIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
            updateHeaderStatusPill()
            startForegroundService(base.putExtra(CastService.EXTRA_SOURCE, "silent"))
            setStatusText("正在启动静音测试投送…")
        }
    }

    private fun showWirelessPrecheckGuideDialog(speakerIds: Set<String>, reasonZh: String) {
        if (isFinishing || isDestroyed || intent?.getBooleanExtra("ui_preview", false) == true) return
        val caps = CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT)
        val paired = PairingNotificationGuide.keyStore(this).isPaired()
        val builder = AlertDialog.Builder(this)
            .setMessage(reasonZh)
            .setNegativeButton("取消", null)

        when {
            !caps.supportsWirelessDebugEngine -> {
                builder.setTitle("当前系统不支持无感采集")
                    .setPositiveButton("切回标准模式投送") { _, _ ->
                        settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                        retrySpeakersAfterModeChange(speakerIds)
                    }
            }
            !paired -> {
                builder.setTitle("首次使用无感模式需配对")
                    .setPositiveButton("打开 4 步配对引导") { _, _ ->
                        startActivity(Intent(this, WirelessDebugGuideActivity::class.java))
                    }
                    .setNeutralButton("先用标准模式投送") { _, _ ->
                        settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                        retrySpeakersAfterModeChange(speakerIds)
                    }
            }
            !caps.supportsShellSingleAppPolicy -> {
                builder.setTitle("Android 11–12 仅支持全局媒体")
                    .setPositiveButton("切换显式全局媒体") { _, _ ->
                        settingsStore.captureScopeMode = CaptureScopeMode.GLOBAL_MEDIA
                        refreshTargetFromStore()
                        retrySpeakersAfterModeChange(speakerIds)
                    }
                    .setNeutralButton("改用标准模式投送") { _, _ ->
                        settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                        retrySpeakersAfterModeChange(speakerIds)
                    }
            }
            else -> {
                builder.setTitle("无感采集未就绪")
                    .setPositiveButton("打开配置向导") { _, _ ->
                        startActivity(
                            Intent(this, WirelessDebugGuideActivity::class.java)
                                .putExtra(WirelessDebugGuideActivity.EXTRA_AUTO_VERIFY, true),
                        )
                    }
                    .setNeutralButton("先用标准模式投送") { _, _ ->
                        settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                        retrySpeakersAfterModeChange(speakerIds)
                    }
            }
        }
        builder.show()
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
        val isWirelessError = failMessage.contains("无线调试") || failMessage.contains("无感采集")
        if (!isWirelessError) return
        lastPromptedWirelessError = failMessage

        val body = "$failMessage\n\n提示：手机重启后 Android 会自动关闭「无线调试」，只需重新点开开关即可恢复，无需重新输入 6 位配对码。"
        AlertDialog.Builder(this)
            .setTitle("无感采集需要检查无线调试")
            .setMessage(body)
            .setPositiveButton("去开启无线调试") { _, _ ->
                CastService.lastFailMessage = ""
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            }
            .setNeutralButton("打开配置向导") { _, _ ->
                CastService.lastFailMessage = ""
                startActivity(
                    Intent(this, WirelessDebugGuideActivity::class.java)
                        .putExtra(WirelessDebugGuideActivity.EXTRA_AUTO_VERIFY, true),
                )
            }
            .setNegativeButton("切回标准模式") { _, _ ->
                CastService.lastFailMessage = ""
                settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                refreshTargetFromStore()
                setStatusText("已切回标准投屏模式，点击音箱即可投送")
            }
            .show()
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
                mainTargetTitle.text = "全局媒体模式（无感采集）"
                mainTargetSubtitle.text = "当前将捕获本机所有媒体音频 · 可在设置中切回单 App 隔离"
                mainTargetIcon.setImageResource(R.drawable.ic_ui_music_note)
                mainTargetIcon.setColorFilter(palette.accentBright)
                if (::mainTargetAutoBadge.isInitialized) mainTargetAutoBadge.visibility = View.GONE
            } else if (selectedPackage.isBlank()) {
                mainTargetTitle.text = "投送音源：未选择"
                mainTargetSubtitle.text = "点击管理常用音源或自动识别正在播放的 App"
                mainTargetIcon.setImageResource(R.drawable.ic_ui_music_note)
                mainTargetIcon.setColorFilter(palette.accentBright)
                if (::mainTargetAutoBadge.isInitialized) mainTargetAutoBadge.visibility = View.GONE
            } else {
                mainTargetTitle.text = "音源 · $selectedLabel"
                mainTargetSubtitle.text = selectedPackage
                UiIconCache.bindAppIcon(this, mainTargetIcon, selectedPackage)
                if (::mainTargetAutoBadge.isInitialized) {
                    mainTargetAutoBadge.visibility = if (currentSelectionAutoDetected) View.VISIBLE else View.GONE
                }
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
            val ev = CastService.lastEvent
            if (ev.isNotBlank()) setStatusText(ev)
        } else {
            val stoppedFromRunning = wasServiceRunning
            wasServiceRunning = false
            val failMsg = CastService.lastFailMessage
            if (failMsg.isNotBlank()) {
                castRequested = false
                setStatusText(failMsg)
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
                    setStatusText("已切回本机播放")
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
                    setStatusText("已自动识别音源：$labelHint")
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
            setStatusText("当前音源已是 ${app.label}")
            return
        }
        Thread {
            val resolver = CaptureTargetResolver(AndroidPackageIdentityLookup(this))
            when (val r = resolver.resolveForCapture(app.packageName)) {
                is CaptureTargetResult.Fail -> main.post { setStatusText(r.messageZh) }
                is CaptureTargetResult.Ok -> {
                    store.saveSelection(
                        TargetAppSelection(
                            packageName = r.packageName,
                            label = app.label,
                            signingCertSha256 = r.signingCertSha256,
                        ),
                    )
                    main.post {
                        selectedPackage = r.packageName
                        selectedLabel = app.label
                        selectedCert = r.signingCertSha256
                        currentSelectionAutoDetected = false
                        manualOverrideBlockedPkg = nowPlaying.sourcePackage?.takeIf {
                            it.isNotBlank() && it != r.packageName
                        }
                        lastGrantSession = null
                        settingsStore.sourceMode = "app"
                        refreshTargetFromStore()
                        if (CastService.running.get() && CastService.source == "app") {
                            service(CastService.ACTION_SWITCH_SOURCE) {
                                putExtra(CastService.EXTRA_TARGET_PACKAGE, r.packageName)
                                putExtra(CastService.EXTRA_TARGET_LABEL, app.label)
                                putExtra(CastService.EXTRA_MANUAL_OVERRIDE_BLOCKED_PKG, manualOverrideBlockedPkg.orEmpty())
                            }
                            setStatusText("正在切换投送音源为 ${app.label}…")
                        } else {
                            setStatusText("已选择音源：${app.label}")
                        }
                    }
                }
            }
        }.start()
    }

    private fun openAppPicker() {
        if (CastService.running.get() && CastService.source == "app") {
            castRequested = false
            stopRequested = true
            pendingStart = null
            stopCast()
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            setStatusText("已切回本机播放")
        }
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
        if (!CastService.running.get() || stopRequested) {
            setStatusText("正在扫描局域网音箱…")
        }
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
                        setStatusText("扫描失败，请点击刷新重试")
                    }
                    return@post
                }
                parsed.rows.forEach { row ->
                    if (row.logLine.isNotBlank()) Log.i(CastService.TAG, row.logLine)
                }
                val mergedDevices = parsed.merged
                deviceStore.saveDevices(parsed.cacheable)
                var next = CastRouteSelection.mergeDiscovered(routeState, mergedDevices)
                if (CastService.running.get() && !stopRequested) {
                    val activeIps = CastService.activeReceiverIps()
                    if (activeIps.isNotEmpty()) {
                        next = CastRouteSelection.syncActiveSpeakers(next, activeIps)
                    }
                }
                pendingIps?.forEach { ip ->
                    if (next.devices.any { it.id == ip && it.castable }) {
                        next = CastRouteSelection.setSpeakerChecked(next, ip, true).state
                    }
                }
                pendingIps = null
                routeState = next
                renderDevices()
                val foundCount = parsed.rows.size
                val castableCount = parsed.castableCount
                val hiddenCount = parsed.hiddenCount
                if (!CastService.running.get() || stopRequested) {
                    setStatusText(
                        when {
                            foundCount == 0 -> "未扫描到在线音箱（可点击右上角刷新或手动添加 IP）"
                            hiddenCount > 0 && !settingsStore.showAllDiscovered ->
                                "发现 $castableCount 台可投设备（已隐藏 $hiddenCount 台未声明 MiPlay 节点）"
                            else -> "发现 $foundCount 台设备 · 点击音箱即可投送"
                        },
                    )
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
            setStatusText("已切回本机播放")
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
        when (requestCode) {
            REQ_PROJECTION -> {
                val p = pendingStart ?: return
                pendingStart = null
                if (resultCode != RESULT_OK || data == null) {
                    castRequested = false
                    setStatusText("播放捕获授权已取消 · 已切回本机播放")
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    renderDevices()
                    lastGrantSession = null
                    return
                }
                val currentIps = routeState.selectedSpeakerIds
                if (currentIps.isEmpty()) {
                    castRequested = false
                    setStatusText("未选择投送音箱 · 已切回本机播放")
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
                startForegroundService(
                    p.putExtra(CastService.EXTRA_PROJ_CODE, resultCode)
                        .putExtra(CastService.EXTRA_PROJ_DATA, data),
                )
                setStatusText("正在连接并投送到音箱…")
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
                selectedPackage = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE).orEmpty()
                selectedLabel = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty()
                selectedCert = data.getStringExtra(AppPickerActivity.EXTRA_CERT).orEmpty()
                currentSelectionAutoDetected = false
                manualOverrideBlockedPkg = nowPlaying.sourcePackage?.takeIf {
                    it.isNotBlank() && it != selectedPackage
                }
                store.saveSelection(
                    TargetAppSelection(
                        packageName = selectedPackage,
                        label = selectedLabel,
                        signingCertSha256 = selectedCert,
                    ),
                )
                lastGrantSession = null
                settingsStore.sourceMode = "app"
                refreshTargetFromStore()
                setStatusText("已选择音源：$selectedLabel")
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

    private fun setVolume(ip: String, v: Int) = service(CastService.ACTION_VOLUME) {
        putExtra(CastService.EXTRA_VOLUME, v)
        if (ip.isNotEmpty()) putExtra(CastService.EXTRA_IP, ip)
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
                        uiState == "reconnecting" -> "${device.id} · 重连中…"
                        uiState == "error" -> "${device.id} · 连接异常"
                        uiState == "streaming" -> "${device.id} · 投送中"
                        else -> "${device.id} · 连接中…"
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
        previewLaunchableApps: List<com.miplay.sender.capture.LaunchableApp> = emptyList(),
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
                com.miplay.sender.capture.TargetAppSelection(
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
        lastStatusMessage = previewStatusText
        buildUi()
        updateLatencyAndSubtitleIndicators(previewStats)
        updateHeaderStatusPill()
        if (previewStatusText.isNotBlank()) setStatusText(previewStatusText)
    }
}
