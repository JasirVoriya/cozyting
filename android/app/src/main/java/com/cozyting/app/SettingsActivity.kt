package com.cozyting.app

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.cozyting.app.capture.CaptureEngineCapabilities
import com.cozyting.app.capture.CaptureEngineMode
import com.cozyting.app.capture.CaptureScopeMode
import com.cozyting.app.capture.LocalFilterAcceptanceGate
import com.cozyting.app.capture.PrefsTargetAppStore
import com.cozyting.app.capture.shell.PairingNotificationGuide
import com.cozyting.app.capture.shell.WirelessDebugGuideActivity
import com.cozyting.app.cast.CastProtocol
import com.cozyting.app.cast.CastRouteSelection
import com.cozyting.app.cast.PrefsDiscoveredDeviceStore
import com.cozyting.app.compat.CaptureSelectionLayers
import com.cozyting.app.compat.LocaleCompat
import com.cozyting.app.compat.QuickSettingsTileCompat
import com.cozyting.app.compat.WindowInsetsApplyCompat
import com.cozyting.app.diag.AppLog
import com.cozyting.app.diag.AppLogStore
import com.cozyting.app.diag.LogViewerActivity
import com.cozyting.app.ui.AppLanguage
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.PrefsCastSettingsStore
import com.cozyting.app.ui.ThemeMode
import com.cozyting.app.ui.UiComponents
import com.cozyting.app.ui.UiPalette
import com.cozyting.app.ui.VolumeSafetyGuard
import com.cozyting.app.update.AppUpdateManager
import com.cozyting.app.update.UpdateChannel
import com.cozyting.app.update.UpdateCheckState
import com.cozyting.app.update.UpdateConfig
import com.cozyting.app.update.UpdateDecisionStatus
import com.cozyting.app.update.UpdateDialogController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 独立分节设置页：将原堆在主界面底部的配置拆为「外观与发现 / 投送与系统权限 / 连接与调试」三张卡片。
 */
class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_TRIGGER_DISCOVER = "trigger_discover"
        const val EXTRA_DEVICES_CHANGED = "devices_changed"
        private const val REQ_LOCAL_PROBE = 44
    }

    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var deviceStore: PrefsDiscoveredDeviceStore
    private lateinit var targetStore: PrefsTargetAppStore
    private lateinit var palette: UiPalette

    private val main = Handler(Looper.getMainLooper())
    private val scrollHost by lazy(LazyThreadSafetyMode.NONE) { UiComponents.ScrollContentHost(this) }
    private var debugExpanded = false
    private var pendingLocalProbe: Intent? = null
    private var localProbeStatus: TextView? = null
    private var localProbeBtn: TextView? = null
    private var quickTileDialog: Dialog? = null
    private var devicesChanged = false
    private var triggerDiscover = false
    private val updateListener: (UpdateCheckState) -> Unit = {
        if (!isFinishing && !isDestroyed) {
            renderUi()
        }
    }

    private val probeTicker = object : Runnable {
        override fun run() {
            if (BuildConfig.IS_ACCEPTANCE && LocalFilterProbeService.running.get()) {
                localProbeStatus?.text = LocalFilterProbeService.lastProbe.ifBlank {
                    LocalFilterProbeService.lastEvent
                }
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
        settingsStore = PrefsCastSettingsStore(this)
        deviceStore = PrefsDiscoveredDeviceStore(this)
        targetStore = PrefsTargetAppStore(this)
        AppUpdateManager.addListener(updateListener)
        renderUi()
        if (BuildConfig.IS_ACCEPTANCE) main.postDelayed(probeTicker, 1000)
    }

    override fun onResume() {
        super.onResume()
        if (::settingsStore.isInitialized) renderUi()
    }

    override fun onDestroy() {
        AppUpdateManager.removeListener(updateListener)
        main.removeCallbacks(probeTicker)
        runCatching { quickTileDialog?.dismiss() }
        super.onDestroy()
    }

    override fun finish() {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_TRIGGER_DISCOVER, triggerDiscover)
                .putExtra(EXTRA_DEVICES_CHANGED, devicesChanged),
        )
        super.finish()
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
            setPadding(0, 0, 0, dp(14))
        }
        topBar.addView(
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_back, getString(R.string.common_back)) {
                finish()
            },
        )
        topBar.addView(
            TextView(this).apply {
                text = getString(R.string.title_settings)
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(topBar)

        // —— 卡片 1：外观与发现 ——
        root.addView(UiComponents.sectionTitle(this, palette, getString(R.string.settings_section_appearance)))
        val appearanceCard = UiComponents.panelCard(this, palette, padDp = 14)
        appearanceCard.addView(
            TextView(this).apply {
                text = getString(R.string.settings_theme_title)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(2), 0, 0, dp(8))
            },
        )
        val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
        val modeIdx = modes.indexOf(settingsStore.themeMode).coerceAtLeast(0)
        appearanceCard.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = modes.map { it.label(this) },
                initialIndex = modeIdx,
            ) { idx ->
                settingsStore.themeMode = modes[idx]
                renderUi()
            },
        )
        appearanceCard.addView(spacer(10))
        appearanceCard.addView(
            TextView(this).apply {
                text = getString(R.string.settings_language_title)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(2), 0, 0, dp(8))
            },
        )
        val languages = listOf(AppLanguage.SYSTEM, AppLanguage.ZH_CN, AppLanguage.EN, AppLanguage.JA)
        val currentLang = AppLocaleManager.readSelectedLanguage(this)
        val langIdx = languages.indexOf(currentLang).coerceAtLeast(0)
        appearanceCard.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = languages.map { it.displayLabel(this) },
                initialIndex = langIdx,
            ) { idx ->
                val chosen = languages[idx]
                if (chosen != AppLocaleManager.readSelectedLanguage(this)) {
                    AppLocaleManager.saveSelectedLanguage(this, chosen)
                    LocaleCompat.syncSystemAppLocales(this, chosen)
                    recreate()
                }
            },
        )
        appearanceCard.addView(spacer(10))
        appearanceCard.addView(
            UiComponents.switchRow(
                context = this,
                palette = palette,
                title = getString(R.string.settings_show_all_title),
                subtitle = getString(R.string.settings_show_all_sub),
                initialChecked = settingsStore.showAllDiscovered,
            ) { checked ->
                settingsStore.showAllDiscovered = checked
                devicesChanged = true
            },
        )
        appearanceCard.addView(spacer(10))
        appearanceCard.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.settings_rediscover_btn)) {
                triggerDiscover = true
                finish()
            },
        )
        root.addView(appearanceCard)

        // —— 卡片 2：投送与系统权限 ——
        root.addView(UiComponents.sectionTitle(this, palette, getString(R.string.settings_section_cast)))
        val castCard = UiComponents.panelCard(this, palette, padDp = 14)
        buildCaptureEngineSection(castCard)
        castCard.addView(spacer(10))

        val caps = CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT)
        val trueSingleAppMuteActive = settingsStore.captureEngineMode == CaptureEngineMode.WIRELESS_DEBUG &&
            settingsStore.captureScopeMode == CaptureScopeMode.SINGLE_APP &&
            caps.supportsTrueSingleAppLocalMute
        castCard.addView(
            UiComponents.switchRow(
                context = this,
                palette = palette,
                title = getString(R.string.settings_local_mute_title),
                subtitle = if (trueSingleAppMuteActive) {
                    getString(R.string.settings_local_mute_sub_single)
                } else {
                    getString(R.string.settings_local_mute_sub_standard)
                },
                initialChecked = !settingsStore.keepLocalOutput,
            ) { checked ->
                settingsStore.keepLocalOutput = !checked
            },
        )
        castCard.addView(
            TextView(this).apply {
                text = getString(R.string.settings_current_status, CastService.localizedLocalMuteStatus(this@SettingsActivity))
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(dp(4), dp(6), dp(4), dp(10))
            },
        )

        buildVolumeGuardSection(castCard)
        castCard.addView(spacer(10))

        val notifGranted = isNotificationListenerEnabled()
        castCard.addView(
            UiComponents.switchRow(
                context = this,
                palette = palette,
                title = getString(R.string.settings_auto_detect_title),
                subtitle = getString(R.string.settings_auto_detect_sub),
                initialChecked = settingsStore.autoDetectAudioSource,
            ) { checked ->
                settingsStore.autoDetectAudioSource = checked
            },
        )
        castCard.addView(spacer(8))

        castCard.addView(
            permissionRow(
                title = getString(R.string.settings_notif_access_title),
                subtitle = getString(R.string.settings_notif_access_sub),
                granted = notifGranted,
                grantedLabel = getString(R.string.settings_status_enabled),
                missingLabel = getString(R.string.settings_status_disabled),
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        )
        castCard.addView(spacer(8))

        val batteryExempt = isBatteryOptimizationIgnored()
        castCard.addView(
            permissionRow(
                title = getString(R.string.settings_battery_title),
                subtitle = getString(R.string.settings_battery_sub),
                granted = batteryExempt,
                grantedLabel = getString(R.string.settings_battery_exempt),
                missingLabel = getString(R.string.settings_battery_not_exempt),
            ) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )
        castCard.addView(spacer(8))
        castCard.addView(buildQuickTileRow())
        root.addView(castCard)

        // —— 卡片 3：连接与调试（运行日志直达 + 高级调试默认折叠） ——
        root.addView(UiComponents.sectionTitle(this, palette, getString(R.string.settings_section_debug)))
        val debugCard = UiComponents.panelCard(this, palette, padDp = 14)

        val logSizeLabel = AppLogStore.formatSize(AppLog.totalSizeBytes(this))
        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, LogViewerActivity::class.java))
            }
        }
        val logMeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        logMeta.addView(
            TextView(this).apply {
                text = getString(R.string.settings_logs_title)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        logMeta.addView(
            TextView(this).apply {
                text = getString(R.string.settings_logs_sub_fmt, logSizeLabel)
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(2), 0, 0)
            },
        )
        logRow.addView(logMeta)
        logRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_ui_chevron_right)
                setColorFilter(palette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            },
        )
        debugCard.addView(logRow)
        debugCard.addView(
            View(this).apply {
                setBackgroundColor(palette.panelBorder)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1).coerceAtLeast(1),
                ).apply { bottomMargin = dp(10) }
            },
        )

        val debugBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (debugExpanded) View.VISIBLE else View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        val chevron = ImageView(this).apply {
            setImageResource(if (debugExpanded) R.drawable.ic_ui_chevron_down else R.drawable.ic_ui_chevron_right)
            setColorFilter(palette.textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        val debugHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                debugExpanded = !debugExpanded
                debugBody.visibility = if (debugExpanded) View.VISIBLE else View.GONE
                chevron.setImageResource(
                    if (debugExpanded) R.drawable.ic_ui_chevron_down else R.drawable.ic_ui_chevron_right,
                )
            }
        }
        val debugMeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        debugMeta.addView(
            TextView(this).apply {
                text = getString(R.string.settings_debug_header)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        debugMeta.addView(
            TextView(this).apply {
                text = getString(R.string.settings_debug_sub)
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(2), 0, 0)
            },
        )
        debugHeader.addView(debugMeta)
        debugHeader.addView(chevron)
        debugCard.addView(debugHeader)

        buildDebugBody(debugBody)
        debugCard.addView(debugBody)
        root.addView(debugCard)

        // —— 卡片 4：关于与更新 ——
        root.addView(UiComponents.sectionTitle(this, palette, getString(R.string.settings_section_about)))
        val updateCard = UiComponents.panelCard(this, palette, padDp = 14)
        buildAboutAndUpdateCard(updateCard)
        root.addView(updateCard)

        scrollHost.setContent(root, palette.bgCanvas)
    }

    private fun buildDebugBody(body: LinearLayout) {
        // 手动添加音箱 IP
        body.addView(fieldLabel(getString(R.string.settings_manual_ip_label)))
        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val manualFeedback = TextView(this).apply {
            textSize = 11.5f
            setTextColor(palette.textSecondary)
            setPadding(dp(2), dp(4), 0, dp(8))
            visibility = View.GONE
        }
        val manualIps = UiComponents.styledEditText(this, palette, getString(R.string.settings_manual_ip_hint)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        manualRow.addView(manualIps)
        manualRow.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.settings_manual_ip_add), primary = true) {
                val ips = manualIps.text.toString().split(',')
                    .map { it.trim() }
                    .filter { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }
                if (ips.isEmpty()) {
                    manualFeedback.text = getString(R.string.settings_manual_ip_invalid)
                    manualFeedback.setTextColor(palette.statusWarning)
                    manualFeedback.visibility = View.VISIBLE
                    return@tonalButton
                }
                val existing = deviceStore.loadDevices()
                val extra = ips.map { ip ->
                    CastRouteSelection.Device(
                        id = ip,
                        name = getString(R.string.settings_manual_ip_prefix, ip),
                        kind = CastRouteSelection.Kind.SPEAKER,
                        castable = true,
                        protocol = CastProtocol.MIPLAY,
                    )
                }
                val merged = (existing + extra).distinctBy { it.id }
                deviceStore.saveDevices(merged)
                devicesChanged = true
                manualIps.setText("")
                manualFeedback.text = getString(R.string.settings_manual_ip_added, ips.joinToString(", "))
                manualFeedback.setTextColor(palette.statusSuccess)
                manualFeedback.visibility = View.VISIBLE
            },
        )
        body.addView(manualRow)
        body.addView(manualFeedback)

        // 音源模式
        body.addView(fieldLabel(getString(R.string.settings_source_mode_label)))
        body.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = listOf(
                    getString(R.string.settings_source_mode_app),
                    getString(R.string.settings_source_mode_silent),
                ),
                initialIndex = if (settingsStore.sourceMode == "silent") 1 else 0,
            ) { idx ->
                settingsStore.sourceMode = if (idx == 1) "silent" else "app"
            },
        )
        body.addView(spacer(10))

        // 播放缓冲延迟（输入框 + 生效按钮：投送中直接热重锚 TIME_OFFSET 生效，待机时保存并下次连接生效）
        val autoMs = CastService.autoDelayMs(this)
        body.addView(fieldLabel(getString(R.string.settings_delay_label, autoMs)))
        val delayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val delayFeedback = TextView(this).apply {
            textSize = 11.5f
            setTextColor(palette.textTertiary)
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        val delayEdit = UiComponents.styledEditText(
            context = this,
            palette = palette,
            hintText = getString(R.string.settings_delay_hint, autoMs),
            inputTypeFlags = InputType.TYPE_CLASS_NUMBER,
        ).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            if (settingsStore.delayMs > 0) setText(settingsStore.delayMs.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val applyDelayAction = {
            val raw = delayEdit.text.toString().trim()
            val parsed = if (raw.isEmpty()) 0 else raw.toIntOrNull()
            if (parsed == null || (parsed != 0 && parsed !in 50..5000)) {
                delayFeedback.text = getString(R.string.settings_delay_invalid, autoMs)
                delayFeedback.setTextColor(palette.statusWarning)
                delayFeedback.visibility = View.VISIBLE
            } else {
                settingsStore.delayMs = parsed
                val effectiveMs = if (parsed > 0) parsed else autoMs
                val autoSuffix = if (parsed == 0) getString(R.string.settings_delay_auto_suffix) else ""
                val lowHint = if (effectiveMs < 250) getString(R.string.settings_delay_low_hint) else ""
                runCatching {
                    getSystemService(InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(delayEdit.windowToken, 0)
                }
                delayEdit.clearFocus()
                if (CastService.running.get()) {
                    startService(
                        Intent(this, CastService::class.java)
                            .setAction(CastService.ACTION_SET_DELAY)
                            .putExtra(CastService.EXTRA_DELAY, effectiveMs),
                    )
                    delayFeedback.text = getString(R.string.settings_delay_applied_live, effectiveMs, autoSuffix, lowHint)
                    delayFeedback.setTextColor(if (effectiveMs < 250) palette.statusWarning else palette.statusSuccess)
                } else {
                    delayFeedback.text = getString(R.string.settings_delay_saved, effectiveMs, autoSuffix, lowHint)
                    delayFeedback.setTextColor(if (effectiveMs < 250) palette.statusWarning else palette.statusSuccess)
                }
                delayFeedback.visibility = View.VISIBLE
            }
        }
        delayEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyDelayAction()
                true
            } else {
                false
            }
        }
        delayRow.addView(delayEdit)
        delayRow.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.settings_delay_apply), primary = true) {
                applyDelayAction()
            },
        )
        body.addView(delayRow)
        body.addView(delayFeedback)
        body.addView(spacer(10))

        // 房间参考模式
        body.addView(fieldLabel(getString(R.string.settings_ref_label)))
        val refValues = listOf("", "single_room", "multi_room", "stereo")
        val refLabels = listOf(
            getString(R.string.settings_ref_auto),
            getString(R.string.settings_ref_single),
            getString(R.string.settings_ref_multi),
            getString(R.string.settings_ref_stereo),
        )
        body.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = refLabels,
                initialIndex = refValues.indexOf(settingsStore.refFunction).coerceAtLeast(0),
            ) { idx ->
                settingsStore.refFunction = refValues[idx]
            },
        )
        body.addView(spacer(10))

        // 认证模式
        body.addView(fieldLabel(getString(R.string.settings_auth_label)))
        body.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = listOf(
                    getString(R.string.settings_auth_auto),
                    getString(R.string.settings_auth_legacy),
                    getString(R.string.settings_auth_safety),
                ),
                initialIndex = settingsStore.authMode,
            ) { idx ->
                settingsStore.authMode = idx
            },
        )
        body.addView(
            TextView(this).apply {
                text = CaptureSelectionLayers.settingsHint(this@SettingsActivity)
                textSize = 11f
                setTextColor(palette.textTertiary)
                setPadding(dp(2), dp(10), dp(2), 0)
            },
        )

        if (BuildConfig.IS_ACCEPTANCE) {
            body.addView(
                TextView(this).apply {
                    text = getString(R.string.settings_acceptance_hint)
                    textSize = 11f
                    setTextColor(palette.statusWarning)
                    setPadding(dp(2), dp(8), dp(2), 0)
                },
            )
        }
        if (LocalFilterAcceptanceGate.exposeLocalProbeEntry(BuildConfig.IS_ACCEPTANCE)) {
            body.addView(spacer(10))
            localProbeStatus = TextView(this).apply {
                text = if (LocalFilterProbeService.running.get()) {
                    LocalFilterProbeService.lastProbe.ifBlank { LocalFilterProbeService.lastEvent }
                } else {
                    getString(R.string.settings_local_probe_idle)
                }
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(dp(2), 0, dp(2), dp(6))
            }
            body.addView(localProbeStatus)
            localProbeBtn = UiComponents.tonalButton(
                context = this,
                palette = palette,
                label = if (LocalFilterProbeService.running.get()) {
                    getString(R.string.settings_local_probe_stop)
                } else {
                    getString(R.string.settings_local_probe_start)
                },
            ) {
                if (LocalFilterProbeService.running.get()) stopLocalProbe() else startLocalFilterAcceptance()
            }
            body.addView(localProbeBtn)
        }
    }

    private fun permissionRow(
        title: String,
        subtitle: String,
        granted: Boolean,
        grantedLabel: String,
        missingLabel: String,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = UiComponents.rippleSurface(
            fillColor = palette.rowIdleBg,
            radiusPx = UiComponents.dpf(this@SettingsActivity, UiPalette.RADIUS_CONTROL_DP.toFloat()),
            rippleColor = palette.ripple,
            strokeColor = palette.rowIdleBorder,
            strokeWidthPx = dp(1).coerceAtLeast(1),
        )
        setPadding(dp(12), dp(11), dp(12), dp(11))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        val meta = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        meta.addView(
            TextView(this@SettingsActivity).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        meta.addView(
            TextView(this@SettingsActivity).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(2), 0, 0)
            },
        )
        addView(meta)
        addView(
            UiComponents.pillBadge(
                context = this@SettingsActivity,
                bgColor = if (granted) palette.accentSoftBg else palette.controlSurface,
                textColor = if (granted) palette.statusSuccess else palette.textSecondary,
                label = if (granted) grantedLabel else missingLabel,
                dotColor = if (granted) palette.statusSuccess else palette.textTertiary,
            ),
        )
        addView(
            ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_ui_chevron_right)
                setColorFilter(palette.textTertiary)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) }
            },
        )
    }

    private fun buildQuickTileRow(): View {
        val canDirectAdd = QuickSettingsTileCompat.canRequestAddTile()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = palette.rowIdleBg,
                radiusPx = UiComponents.dpf(this@SettingsActivity, 14f),
                rippleColor = palette.ripple,
                strokeColor = palette.rowIdleBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { triggerQuickTileAction() }
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        }
        meta.addView(
            TextView(this).apply {
                text = getString(R.string.settings_qs_tile_title)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        meta.addView(
            TextView(this).apply {
                text = getString(R.string.settings_qs_tile_sub)
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(2), 0, 0)
            },
        )
        row.addView(meta)
        row.addView(
            UiComponents.pillBadge(
                context = this,
                bgColor = palette.accentSoftBg,
                textColor = palette.accentBright,
                label = getString(
                    if (canDirectAdd) R.string.settings_qs_tile_add_btn else R.string.settings_qs_tile_guide_btn,
                ),
            ),
        )
        return row
    }

    private fun triggerQuickTileAction() {
        if (!QuickSettingsTileCompat.canRequestAddTile()) {
            showQuickTileGuideDialog(
                title = getString(R.string.settings_qs_tile_guide_title),
                message = getString(R.string.settings_qs_tile_guide_msg),
            )
            return
        }
        QuickSettingsTileCompat.requestAddTile(
            context = this,
            label = getString(R.string.qs_tile_label),
            executor = { r -> main.post(r) },
        ) { result ->
            if (isFinishing || isDestroyed) return@requestAddTile
            when (result) {
                QuickSettingsTileCompat.ADD_RESULT_ADDED -> showQuickTileGuideDialog(
                    title = getString(R.string.settings_qs_tile_title),
                    message = getString(R.string.settings_qs_tile_added),
                )
                QuickSettingsTileCompat.ADD_RESULT_ALREADY_ADDED -> showQuickTileGuideDialog(
                    title = getString(R.string.settings_qs_tile_title),
                    message = getString(R.string.settings_qs_tile_already_added),
                )
                else -> showQuickTileGuideDialog(
                    title = getString(R.string.settings_qs_tile_guide_title),
                    message = getString(R.string.settings_qs_tile_guide_msg),
                )
            }
        }
    }

    private fun showQuickTileGuideDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        runCatching { quickTileDialog?.dismiss() }
        quickTileDialog = UiComponents.showStyleBDialog(
            activity = this,
            palette = palette,
            title = title,
            message = message,
            primaryLabel = getString(R.string.settings_qs_tile_guide_ok),
            onPrimary = {},
            secondaryLabel = getString(R.string.settings_qs_tile_preview_btn),
            onSecondary = {
                startActivity(QuickCastPanelActivity.createLaunchIntent(this))
            },
            onDismissed = { quickTileDialog = null },
        )
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val cn = ComponentName(this, NowPlayingNotificationListener::class.java).flattenToString()
        val shortCn = ComponentName(this, NowPlayingNotificationListener::class.java).flattenToShortString()
        return flat.split(':').any { it.equals(cn, ignoreCase = true) || it.equals(shortCn, ignoreCase = true) }
    }

    private fun isBatteryOptimizationIgnored(): Boolean =
        runCatching {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)

    private fun startLocalFilterAcceptance() {
        if (!LocalFilterAcceptanceGate.exposeLocalProbeEntry(BuildConfig.IS_ACCEPTANCE)) return
        val sel = targetStore.loadSelection()
        val pkg = sel?.packageName.orEmpty()
        val cert = sel?.signingCertSha256.orEmpty()
        val gate = LocalFilterAcceptanceGate.validateStart(
            LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE,
            pkg.isNotBlank(),
            0,
        )
        if (gate is LocalFilterAcceptanceGate.StartDecision.Block) {
            localProbeStatus?.text = gate.messageZh
            return
        }
        if (CastService.running.get()) {
            localProbeStatus?.text = getString(R.string.settings_local_probe_stop_cast_first)
            return
        }
        pendingLocalProbe = Intent(this, LocalFilterProbeService::class.java)
            .setAction(LocalFilterProbeService.ACTION_START)
            .putExtra(LocalFilterProbeService.EXTRA_TARGET_PACKAGE, pkg)
            .putExtra(LocalFilterProbeService.EXTRA_TARGET_CERT, cert)
            .putExtra(LocalFilterProbeService.EXTRA_GRANT_SESSION, UUID.randomUUID().toString())
        startActivityForResult(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
            REQ_LOCAL_PROBE,
        )
        localProbeStatus?.text = getString(R.string.settings_local_probe_waiting)
    }

    private fun stopLocalProbe() {
        startService(Intent(this, LocalFilterProbeService::class.java).setAction(LocalFilterProbeService.ACTION_STOP))
        localProbeBtn?.text = getString(R.string.settings_local_probe_start)
        localProbeStatus?.text = getString(R.string.settings_local_probe_stopped)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LOCAL_PROBE) return
        val p = pendingLocalProbe ?: return
        pendingLocalProbe = null
        if (resultCode != RESULT_OK || data == null) {
            localProbeStatus?.text = getString(R.string.settings_local_probe_denied)
            return
        }
        startForegroundService(
            p.putExtra(LocalFilterProbeService.EXTRA_PROJ_CODE, resultCode)
                .putExtra(LocalFilterProbeService.EXTRA_PROJ_DATA, data),
        )
        localProbeBtn?.text = getString(R.string.settings_local_probe_stop)
        localProbeStatus?.text = getString(R.string.settings_local_probe_started)
    }

    private fun buildVolumeGuardSection(card: LinearLayout) {
        val enabled = settingsStore.volumeGuardEnabled
        card.addView(
            UiComponents.switchRow(
                context = this,
                palette = palette,
                title = getString(R.string.settings_volume_guard_title),
                subtitle = getString(R.string.settings_volume_guard_sub),
                initialChecked = enabled,
            ) { checked ->
                settingsStore.volumeGuardEnabled = checked
                VolumeSafetyGuard.syncToNative(settingsStore.volumeGuardPolicy())
                renderUi()
            },
        )
        if (!enabled) return

        val initialLimit = VolumeSafetyGuard.snapSafeLimit(settingsStore.volumeGuardSafeLimit)
        val stepCount = (VolumeSafetyGuard.MAX_SAFE_LIMIT - VolumeSafetyGuard.MIN_SAFE_LIMIT) / VolumeSafetyGuard.STEP
        val limitBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = palette.rowIdleBg,
                radiusPx = UiComponents.dpf(this@SettingsActivity, UiPalette.RADIUS_CONTROL_DP.toFloat()),
                strokeColor = palette.rowIdleBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val limitValueView = TextView(this).apply {
            text = "$initialLimit%"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.accentBright)
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.settings_volume_limit_title)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(limitValueView)
        limitBox.addView(header)

        val hintView = TextView(this).apply {
            text = getString(R.string.settings_volume_limit_hint, initialLimit)
            textSize = 11.5f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(4), 0, dp(4))
        }
        val limitSeek = UiComponents.styledSeekBar(
            context = this,
            activeColor = palette.accent,
            trackColor = palette.trackIdle,
            thumbFillColor = Color.WHITE,
            thumbStrokeColor = palette.accent,
        ).apply {
            max = stepCount
            progress = ((initialLimit - VolumeSafetyGuard.MIN_SAFE_LIMIT) / VolumeSafetyGuard.STEP)
                .coerceIn(0, stepCount)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (VolumeSafetyGuard.MIN_SAFE_LIMIT + p * VolumeSafetyGuard.STEP)
                        .coerceIn(VolumeSafetyGuard.MIN_SAFE_LIMIT, VolumeSafetyGuard.MAX_SAFE_LIMIT)
                    limitValueView.text = "$v%"
                    hintView.text = getString(R.string.settings_volume_limit_hint, v)
                    if (fromUser) {
                        settingsStore.volumeGuardSafeLimit = v
                        VolumeSafetyGuard.syncToNative(settingsStore.volumeGuardPolicy())
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val v = (VolumeSafetyGuard.MIN_SAFE_LIMIT + progress * VolumeSafetyGuard.STEP)
                        .coerceIn(VolumeSafetyGuard.MIN_SAFE_LIMIT, VolumeSafetyGuard.MAX_SAFE_LIMIT)
                    settingsStore.volumeGuardSafeLimit = v
                    VolumeSafetyGuard.syncToNative(settingsStore.volumeGuardPolicy())
                }
            })
        }
        limitBox.addView(limitSeek)
        limitBox.addView(hintView)
        card.addView(limitBox)
    }

    private fun buildCaptureEngineSection(card: LinearLayout) {
        val caps = CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT)
        card.addView(
            TextView(this).apply {
                text = getString(R.string.settings_capture_engine_title)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(2), 0, 0, dp(8))
            },
        )
        val engines = listOf(CaptureEngineMode.STANDARD, CaptureEngineMode.WIRELESS_DEBUG)
        val engineIdx = engines.indexOf(settingsStore.captureEngineMode).coerceAtLeast(0)
        card.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = engines.map { it.label(this) },
                initialIndex = engineIdx,
            ) { idx ->
                settingsStore.captureEngineMode = engines[idx]
                renderUi()
            },
        )
        card.addView(
            TextView(this).apply {
                text = caps.tierSummary(this@SettingsActivity)
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(dp(4), dp(6), dp(4), dp(4))
            },
        )

        if (settingsStore.captureEngineMode != CaptureEngineMode.WIRELESS_DEBUG) return

        if (!caps.supportsWirelessDebugEngine) {
            card.addView(
                TextView(this).apply {
                    text = getString(R.string.settings_wireless_unsupported_hint)
                    textSize = 11.5f
                    setTextColor(palette.statusWarning)
                    setPadding(dp(4), dp(2), dp(4), dp(4))
                },
            )
            return
        }

        card.addView(spacer(6))
        card.addView(fieldLabel(getString(R.string.settings_capture_scope_title)))
        val scopes = listOf(CaptureScopeMode.SINGLE_APP, CaptureScopeMode.GLOBAL_MEDIA)
        val scopeIdx = scopes.indexOf(settingsStore.captureScopeMode).coerceAtLeast(0)
        card.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = scopes.map { it.label(this) },
                initialIndex = scopeIdx,
            ) { idx ->
                settingsStore.captureScopeMode = scopes[idx]
                renderUi()
            },
        )
        if (settingsStore.captureScopeMode == CaptureScopeMode.SINGLE_APP && !caps.supportsShellSingleAppPolicy) {
            card.addView(
                TextView(this).apply {
                    text = getString(R.string.settings_scope_api30_hint)
                    textSize = 11.5f
                    setTextColor(palette.statusWarning)
                    setPadding(dp(4), dp(6), dp(4), dp(2))
                },
            )
        }

        val paired = PairingNotificationGuide.keyStore(this).isPaired()
        card.addView(spacer(8))
        card.addView(
            permissionRow(
                title = getString(R.string.settings_wireless_pair_title),
                subtitle = if (paired) {
                    getString(R.string.settings_wireless_pair_sub_done)
                } else {
                    getString(R.string.settings_wireless_pair_sub_todo)
                },
                granted = paired,
                grantedLabel = getString(R.string.settings_wireless_paired),
                missingLabel = getString(R.string.settings_wireless_unpaired),
            ) {
                openWirelessDebugGuide(autoVerify = paired)
            },
        )
    }

    private fun openWirelessDebugGuide(autoVerify: Boolean) {
        startActivity(
            Intent(this, WirelessDebugGuideActivity::class.java)
                .putExtra(WirelessDebugGuideActivity.EXTRA_AUTO_VERIFY, autoVerify),
        )
    }

    private fun buildAboutAndUpdateCard(card: LinearLayout) {
        val state = AppUpdateManager.state
        val displayVer = AppUpdateManager.currentDisplayVersion()
        val readyDecision = (state as? UpdateCheckState.Ready)?.decision
        val hasNew = readyDecision?.hasNewerVersion == true
        val isChecking = state is UpdateCheckState.Checking

        card.setPadding(dp(14), dp(4), dp(14), dp(4))
        val aboutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = Color.TRANSPARENT,
                radiusPx = UiComponents.dpf(this@SettingsActivity, 10f),
                rippleColor = palette.ripple,
            )
            setPadding(dp(2), dp(12), dp(2), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
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
                text = getString(R.string.title_about)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        meta.addView(
            TextView(this).apply {
                text = AboutActivity.formatUpdateSubtitle(this@SettingsActivity, settingsStore, state)
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
        aboutRow.addView(meta)

        when {
            isChecking -> {
                aboutRow.addView(
                    UiComponents.pillBadge(
                        this,
                        palette.accentSoftBg,
                        palette.accentBright,
                        getString(R.string.settings_update_pill_checking),
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = dp(4) }
                    },
                )
            }
            hasNew -> {
                val dotSize = dp(7)
                aboutRow.addView(
                    View(this).apply {
                        background = UiComponents.roundedDrawable(palette.accentBright, dotSize / 2f)
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            marginEnd = dp(6)
                        }
                    },
                )
                aboutRow.addView(
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
            }
            else -> {
                aboutRow.addView(
                    TextView(this).apply {
                        text = "v$displayVer"
                        textSize = 12f
                        setTextColor(palette.textSecondary)
                        setPadding(0, 0, dp(4), 0)
                    },
                )
            }
        }
        aboutRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_ui_chevron_right)
                setColorFilter(palette.textTertiary)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            },
        )
        card.addView(aboutRow)
    }

    private fun fieldLabel(textStr: String): TextView = TextView(this).apply {
        text = textStr
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(palette.textSecondary)
        setPadding(dp(2), dp(2), 0, dp(6))
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun dp(v: Int) = UiComponents.dp(this, v)
}
