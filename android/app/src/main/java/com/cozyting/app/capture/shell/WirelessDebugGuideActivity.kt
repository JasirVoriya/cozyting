package com.cozyting.app.capture.shell

import android.app.Activity
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cozyting.app.R
import com.cozyting.app.capture.CaptureEngineCapabilities
import com.cozyting.app.capture.CaptureEngineMode
import com.cozyting.app.capture.CaptureScopeMode
import com.cozyting.app.compat.NotificationPermissionCompat
import com.cozyting.app.compat.WindowInsetsApplyCompat
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.PrefsCastSettingsStore
import com.cozyting.app.ui.UiComponents
import com.cozyting.app.ui.UiPalette

/**
 * Android 内置无线调试无感采集 5 步交互式配置向导（仅 Android 端使用，严格沿用 Style B 明暗主题）。
 *
 * 实时检测系统状态并在 `onResume()` 自动刷新进度：
 * 1. 通知权限（用于展示后台投送状态与通知栏配对码输入兜底横幅）；
 * 2. 系统「开发者选项」（`DEVELOPMENT_SETTINGS_ENABLED`）；
 * 3. 无障碍自动配对服务（无需悬浮窗权限：在系统设置 PIN 码页用 `TYPE_ACCESSIBILITY_OVERLAY` 展示悬浮数字键盘，
 *    并自动读取弹窗中的 6 位配对码与端口完成秒配；支持跳过直接走通知栏长按输入兜底）；
 * 4. 本机 TLS 配对证书（无障碍自动识别 / 悬浮数字键盘 / 通知栏长按展开输入 / 手动填码兜底）；
 * 5. 连通性验证（`WirelessAdbShellClient` 执行 `id` 确认 `uid=2000` 可达）+ 一键启用。
 */
class WirelessDebugGuideActivity : Activity() {
    companion object {
        const val EXTRA_AUTO_VERIFY = "auto_verify"
        const val EXTRA_ENABLED_WIRELESS = "enabled_wireless"
        private const val REQ_POST_NOTIF = 61
    }

    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var palette: UiPalette
    private val main = Handler(Looper.getMainLooper())
    private val scrollHost by lazy(LazyThreadSafetyMode.NONE) { UiComponents.ScrollContentHost(this) }

    private var connectionVerified = false
    private var connectedPort = 0
    private var testingConnection = false
    private var accessibilitySkipped = false
    private var manualPairExpanded = false
    private var tipsExpanded = false
    private var feedbackText = ""
    private var feedbackIsError = false
    private var autoCheckPending = false
    private var clearPairingDialog: Dialog? = null

    // 供 Robolectric UI 预览单测注入状态
    private var previewStateOverride: WirelessDebugOnboardingState? = null
    private var previewDarkOverride: Boolean? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        settingsStore = PrefsCastSettingsStore(this)
        autoCheckPending = intent?.getBooleanExtra(EXTRA_AUTO_VERIFY, false) == true
        renderUi()
    }

    override fun onDestroy() {
        runCatching { clearPairingDialog?.dismiss() }
        clearPairingDialog = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_VERIFY, false)) {
            autoCheckPending = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::settingsStore.isInitialized || previewStateOverride != null) return
        val paired = PairingNotificationGuide.keyStore(this).isPaired()
        if (paired) {
            PairingNotificationGuide.cancelNotification(this)
        }
        renderUi()
        // 从浮动框配对成功自动返回或从系统设置返回后，若已配对且尚未验证，自动静默验证一次连接
        if (paired && !connectionVerified && !testingConnection && (autoCheckPending || feedbackText.contains("配对") || feedbackText.contains("浮动"))) {
            autoCheckPending = false
            testConnection(silent = false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIF) {
            if (isNotificationsReady()) {
                feedbackText = getString(R.string.guide_feedback_notif_granted)
                feedbackIsError = false
            } else {
                feedbackText = getString(R.string.guide_feedback_notif_denied)
                feedbackIsError = true
            }
            renderUi()
        }
    }

    internal fun applyUiPreviewStateForTest(
        isDark: Boolean,
        state: WirelessDebugOnboardingState,
        expandTips: Boolean = true,
        feedback: String = "",
    ) {
        previewDarkOverride = isDark
        previewStateOverride = state
        tipsExpanded = expandTips
        feedbackText = feedback
        feedbackIsError = false
        renderUi()
    }

    private fun computeLiveState(): WirelessDebugOnboardingState {
        previewStateOverride?.let { return it }
        return WirelessDebugOnboardingState.evaluate(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsGranted = isNotificationsReady(),
            developerOptionsEnabled = isDeveloperOptionsEnabled(),
            accessibilityEnabled = isAccessibilityHelperEnabled(),
            accessibilitySkipped = accessibilitySkipped,
            paired = PairingNotificationGuide.keyStore(this).isPaired(),
            connectionVerified = connectionVerified,
            connectedPort = connectedPort,
            strings = WirelessDebugOnboardingState.Strings.fromContext(this),
        )
    }

    private fun renderUi() {
        val isDark = previewDarkOverride ?: settingsStore.isDark(this)
        palette = UiPalette.forMode(isDark)
        WindowInsetsApplyCompat.applyEdgeToEdge(this, isDark)
        val state = computeLiveState()

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(10), pad, dp(28))
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
                text = getString(R.string.guide_title)
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(topBar)

        // —— 顶部总览进度卡 ——
        val heroCard = UiComponents.panelCard(this, palette, padDp = 16)
        val badgeColor = when {
            !state.supported -> palette.statusWarning
            state.connectionVerified -> palette.statusSuccess
            else -> palette.accent
        }
        val totalSteps = state.steps.size.coerceAtLeast(5)
        val badgeText = when {
            !state.supported -> getString(R.string.guide_badge_unsupported, state.sdkInt)
            state.connectionVerified -> getString(R.string.guide_badge_all_done, totalSteps)
            else -> getString(R.string.guide_badge_progress, state.completedCount, totalSteps)
        }
        heroCard.addView(
            TextView(this).apply {
                text = badgeText
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(badgeColor)
                background = UiComponents.roundedDrawable(
                    fillColor = palette.controlSurface,
                    radiusPx = dpf(999f),
                    strokeColor = badgeColor,
                    strokeWidthPx = dp(1).coerceAtLeast(1),
                )
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        heroCard.addView(
            TextView(this).apply {
                text = state.headlineZh
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(0, dp(10), 0, dp(4))
            },
        )
        heroCard.addView(
            TextView(this).apply {
                text = state.subheadlineZh
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                setLineSpacing(dpf(2f), 1f)
            },
        )
        if (state.supported) {
            heroCard.addView(
                TextView(this).apply {
                    text = CaptureEngineCapabilities.forSdkInt(state.sdkInt).tierSummary(this@WirelessDebugGuideActivity)
                    textSize = 11.5f
                    setTextColor(palette.textTertiary)
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
        root.addView(heroCard)

        if (!state.supported) {
            root.addView(spacer(14))
            root.addView(
                UiComponents.tonalButton(this, palette, getString(R.string.guide_btn_back_standard), primary = true) {
                    settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                    finish()
                },
            )
            scrollHost.setContent(root, palette.bgCanvas)
            return
        }

        // —— 4 步交互清单 ——
        root.addView(UiComponents.sectionTitle(this, palette, getString(R.string.guide_section_steps)))
        state.steps.forEach { step ->
            root.addView(buildStepCard(step, state))
            root.addView(spacer(10))
        }

        // —— 实时反馈横条 ——
        if (feedbackText.isNotBlank()) {
            val fbCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val borderColor = if (feedbackIsError) palette.statusWarning else palette.statusSuccess
                background = UiComponents.roundedDrawable(
                    fillColor = palette.panelBg,
                    radiusPx = dpf(14f),
                    strokeColor = borderColor,
                    strokeWidthPx = dp(1).coerceAtLeast(1),
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            fbCard.addView(
                TextView(this).apply {
                    text = feedbackText
                    textSize = 12f
                    setTextColor(if (feedbackIsError) palette.statusWarning else palette.statusSuccess)
                },
            )
            root.addView(fbCard)
            root.addView(spacer(10))
        }

        // —— 底部主操作：完成并启用无感模式 ——
        if (state.paired) {
            val enableLabel = if (state.connectionVerified) {
                getString(R.string.guide_btn_enable_done)
            } else {
                getString(R.string.guide_btn_enable_paired)
            }
            root.addView(
                UiComponents.tonalButton(this, palette, enableLabel, primary = true) {
                    applyAndFinish()
                },
            )
            root.addView(spacer(10))
        }

        // —— 常见机型避坑与重启说明（折叠卡片） ——
        root.addView(buildRomTipsCard())

        // —— 低频证书重置入口（已配对时收口至向导底部，点击二次确认防误触） ——
        if (state.paired) {
            root.addView(spacer(10))
            root.addView(
                UiComponents.tonalButton(this, palette, getString(R.string.guide_btn_clear_cert)) {
                    confirmClearPairing()
                }.apply {
                    setTextColor(palette.statusError)
                },
            )
        }

        scrollHost.setContent(root, palette.bgCanvas)
    }

    private fun confirmClearPairing() {
        runCatching { clearPairingDialog?.dismiss() }
        clearPairingDialog = UiComponents.showStyleBDialog(
            activity = this,
            palette = palette,
            title = getString(R.string.guide_clear_dialog_title),
            message = getString(R.string.guide_clear_dialog_msg),
            primaryLabel = getString(R.string.guide_clear_dialog_confirm),
            onPrimary = {
                PairingNotificationGuide.keyStore(this).clearPairing()
                PairingNotificationGuide.cancelNotification(this)
                connectionVerified = false
                connectedPort = 0
                autoCheckPending = false
                previewStateOverride = null
                feedbackText = getString(R.string.guide_feedback_cert_cleared)
                feedbackIsError = false
                renderUi()
            },
            secondaryLabel = getString(R.string.common_cancel),
            onDismissed = { clearPairingDialog = null },
        )
    }

    private fun buildStepCard(
        step: WirelessDebugOnboardingState.StepItem,
        state: WirelessDebugOnboardingState,
    ): View {
        val isActive = step.status == WirelessDebugOnboardingState.StepStatus.ACTIVE
        val isDone = step.status == WirelessDebugOnboardingState.StepStatus.DONE
        val cardBg = if (isActive && !palette.isDark) palette.rowSelectedBg else palette.panelBg
        val borderColor = when {
            isActive -> palette.accent
            isDone -> palette.panelBorder
            else -> palette.panelBorder
        }
        val borderW = if (isActive) dp(2).coerceAtLeast(2) else dp(1).coerceAtLeast(1)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = cardBg,
                radiusPx = dpf(18f),
                strokeColor = borderColor,
                strokeWidthPx = borderW,
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // 标题行：序号圆徽章 + 标题 + 状态胶囊
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val numColor = when {
            isDone -> palette.statusSuccess
            isActive -> palette.accent
            else -> palette.controlSurface
        }
        val numTextColor = if (isDone || isActive) Color.WHITE else palette.textSecondary
        header.addView(
            TextView(this).apply {
                text = if (isDone) "✓" else step.id.index.toString()
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(numTextColor)
                gravity = Gravity.CENTER
                background = UiComponents.roundedDrawable(numColor, dpf(999f))
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
            },
        )
        header.addView(
            TextView(this).apply {
                text = step.titleZh
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val badgeColor = when {
            isDone -> palette.statusSuccess
            isActive -> palette.accentBright
            else -> palette.textTertiary
        }
        header.addView(
            TextView(this).apply {
                text = step.statusBadgeZh
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(badgeColor)
                background = UiComponents.roundedDrawable(palette.controlSurface, dpf(999f))
                setPadding(dp(9), dp(3), dp(9), dp(3))
            },
        )
        card.addView(header)

        // 说明正文
        card.addView(
            TextView(this).apply {
                text = step.detailZh
                textSize = 12f
                setTextColor(palette.textSecondary)
                setLineSpacing(dpf(2f), 1f)
                setPadding(dp(34), dp(6), 0, 0)
            },
        )

        // 步骤动作区
        val actionLabel = step.primaryActionLabelZh
        if (actionLabel != null) {
            val actionBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(34), dp(10), 0, 0)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val primaryBtn = UiComponents.tonalButton(
                context = this,
                palette = palette,
                label = if (step.id == WirelessDebugOnboardingState.StepId.VERIFY_CONNECTION && testingConnection) {
                    getString(R.string.guide_testing_conn)
                } else {
                    actionLabel
                },
                primary = isActive,
            ) {
                onStepPrimaryAction(step.id, state)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(primaryBtn)

            if (step.id == WirelessDebugOnboardingState.StepId.ACCESSIBILITY_HELPER &&
                !state.accessibilityEnabled &&
                !state.accessibilitySkipped &&
                !state.paired
            ) {
                primaryBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
                row.addView(
                    UiComponents.tonalButton(
                        this,
                        palette,
                        getString(R.string.guide_skip_a11y),
                    ) {
                        accessibilitySkipped = true
                        feedbackText = getString(R.string.guide_feedback_a11y_skipped)
                        feedbackIsError = false
                        renderUi()
                    },
                )
            }

            if (step.id == WirelessDebugOnboardingState.StepId.WIRELESS_PAIRING) {
                primaryBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
                row.addView(
                    UiComponents.tonalButton(
                        this,
                        palette,
                        if (manualPairExpanded) getString(R.string.guide_manual_collapse) else getString(R.string.guide_manual_expand),
                    ) {
                        manualPairExpanded = !manualPairExpanded
                        renderUi()
                    },
                )
            }
            actionBox.addView(row)

            // 第 3 步折叠式分屏手动填码兜底
            if (step.id == WirelessDebugOnboardingState.StepId.WIRELESS_PAIRING && manualPairExpanded) {
                actionBox.addView(spacer(8))
                actionBox.addView(
                    TextView(this).apply {
                        text = getString(R.string.guide_manual_hint)
                        textSize = 11f
                        setTextColor(palette.textTertiary)
                        setPadding(0, 0, 0, dp(6))
                    },
                )
                val inputs = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val portEdit = UiComponents.styledEditText(this, palette, getString(R.string.guide_manual_port_hint), InputType.TYPE_CLASS_NUMBER).apply {
                    if (PairingNotificationGuide.lastDiscoveredPairingPort > 0) {
                        setText(PairingNotificationGuide.lastDiscoveredPairingPort.toString())
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    }
                }
                val pinEdit = UiComponents.styledEditText(this, palette, getString(R.string.guide_manual_pin_hint), InputType.TYPE_CLASS_NUMBER).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    }
                }
                inputs.addView(portEdit)
                inputs.addView(pinEdit)
                inputs.addView(
                    UiComponents.tonalButton(this, palette, getString(R.string.guide_manual_pair_btn), primary = true) {
                        val port = portEdit.text.toString().trim().toIntOrNull() ?: 0
                        val pin = pinEdit.text.toString().trim()
                        runManualPair(port, pin)
                    },
                )
                actionBox.addView(inputs)
            }
            card.addView(actionBox)
        }

        return card
    }

    private fun buildRomTipsCard(): View {
        val card = UiComponents.panelCard(this, palette, padDp = 14)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (tipsExpanded) View.VISIBLE else View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val chevron = ImageView(this).apply {
            setImageResource(if (tipsExpanded) R.drawable.ic_ui_chevron_down else R.drawable.ic_ui_chevron_right)
            setColorFilter(palette.textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                tipsExpanded = !tipsExpanded
                body.visibility = if (tipsExpanded) View.VISIBLE else View.GONE
                chevron.setImageResource(
                    if (tipsExpanded) R.drawable.ic_ui_chevron_down else R.drawable.ic_ui_chevron_right,
                )
            }
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.guide_tips_title)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(chevron)
        card.addView(header)

        val tips = listOf(
            getString(R.string.guide_tip_1),
            getString(R.string.guide_tip_2),
            getString(R.string.guide_tip_3),
            getString(R.string.guide_tip_4),
            getString(R.string.guide_tip_5),
            getString(R.string.guide_tip_6),
        )
        tips.forEach { tip ->
            body.addView(
                TextView(this).apply {
                    text = tip
                    textSize = 11.5f
                    setTextColor(palette.textSecondary)
                    setLineSpacing(dpf(2f), 1f)
                    setPadding(0, dp(4), 0, dp(2))
                },
            )
        }
        card.addView(body)
        return card
    }

    private fun onStepPrimaryAction(
        stepId: WirelessDebugOnboardingState.StepId,
        state: WirelessDebugOnboardingState,
    ) {
        when (stepId) {
            WirelessDebugOnboardingState.StepId.NOTIFICATION -> requestNotificationPermissionOrOpenSettings()
            WirelessDebugOnboardingState.StepId.DEVELOPER_OPTIONS -> openAboutPhoneSettings()
            WirelessDebugOnboardingState.StepId.ACCESSIBILITY_HELPER -> {
                accessibilitySkipped = false
                openAccessibilitySettings()
            }
            WirelessDebugOnboardingState.StepId.WIRELESS_PAIRING -> {
                autoCheckPending = true
                PairingNotificationGuide.startPairingSession(this)
                feedbackText = if (state.accessibilityEnabled) {
                    getString(R.string.guide_feedback_a11y_ready)
                } else {
                    getString(R.string.guide_feedback_notif_banner_sent)
                }
                feedbackIsError = false
                renderUi()
                openDeveloperOptions()
            }
            WirelessDebugOnboardingState.StepId.VERIFY_CONNECTION -> testConnection(silent = false)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun requestNotificationPermissionOrOpenSettings() {
        val nm = getSystemService(NotificationManager::class.java)
        val channelEnabled = nm?.areNotificationsEnabled() != false
        if (NotificationPermissionCompat.shouldRequest() &&
            checkSelfPermission(NotificationPermissionCompat.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            NotificationPermissionCompat.requestIfNeeded(this, REQ_POST_NOTIF)
            return
        }
        if (!channelEnabled) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            runCatching { startActivity(intent) }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        }
    }

    private fun openAboutPhoneSettings() {
        val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun runManualPair(port: Int, pin: String) {
        if (port !in 1..65535) {
            feedbackText = getString(R.string.guide_feedback_invalid_port)
            feedbackIsError = true
            renderUi()
            return
        }
        feedbackText = getString(R.string.guide_feedback_pairing_start, port)
        feedbackIsError = false
        renderUi()
        val appCtx = applicationContext
        Thread({
            val store = PairingNotificationGuide.keyStore(appCtx)
            val identity = store.getOrCreateIdentity()
            val res = WirelessAdbPairingClient.pair("127.0.0.1", port, pin, identity)
            main.post {
                when (res) {
                    is WirelessAdbPairingClient.PairResult.Success -> {
                        store.markPaired()
                        PairingNotificationGuide.cancelNotification(appCtx)
                        manualPairExpanded = false
                        feedbackText = getString(R.string.guide_feedback_pair_success)
                        feedbackIsError = false
                        renderUi()
                        testConnection(silent = false)
                    }
                    is WirelessAdbPairingClient.PairResult.Failure -> {
                        feedbackText = res.messageZh
                        feedbackIsError = true
                        renderUi()
                    }
                }
            }
        }, "guide-manual-pair").start()
    }

    private fun testConnection(silent: Boolean) {
        if (testingConnection) return
        val store = PairingNotificationGuide.keyStore(this)
        if (!store.isPaired()) {
            if (!silent) {
                feedbackText = getString(R.string.guide_feedback_need_pair_first)
                feedbackIsError = true
                renderUi()
            }
            return
        }
        testingConnection = true
        if (!silent) {
            feedbackText = getString(R.string.guide_feedback_probing)
            feedbackIsError = false
            renderUi()
        }
        val appCtx = applicationContext
        Thread({
            val ep = WirelessAdbDiscovery(appCtx).discoverOnce(WirelessAdbDiscovery.SERVICE_CONNECT, 3_500L)
            if (ep == null) {
                main.post {
                    testingConnection = false
                    connectionVerified = false
                    connectedPort = 0
                    feedbackText = getString(R.string.guide_feedback_no_service)
                    feedbackIsError = true
                    renderUi()
                }
                return@Thread
            }
            val identity = store.getOrCreateIdentity()
            val res = WirelessAdbShellClient.runShellCommand(ep.host, ep.port, identity, "id")
            main.post {
                testingConnection = false
                when (res) {
                    is WirelessAdbShellClient.ShellResult.Ok -> {
                        connectionVerified = true
                        connectedPort = ep.port
                        feedbackText = getString(R.string.guide_feedback_conn_ok, ep.port, res.output.take(44))
                        feedbackIsError = false
                    }
                    is WirelessAdbShellClient.ShellResult.Error -> {
                        connectionVerified = false
                        connectedPort = 0
                        feedbackText = res.messageZh
                        feedbackIsError = true
                    }
                }
                renderUi()
            }
        }, "guide-test-conn").start()
    }

    private fun applyAndFinish() {
        val caps = CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT)
        settingsStore.captureEngineMode = CaptureEngineMode.WIRELESS_DEBUG
        if (!caps.supportsShellSingleAppPolicy && caps.supportsShellGlobalSubmix) {
            settingsStore.captureScopeMode = CaptureScopeMode.GLOBAL_MEDIA
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ENABLED_WIRELESS, true))
        finish()
    }

    private fun isNotificationsReady(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.areNotificationsEnabled() == false) return false
        if (NotificationPermissionCompat.shouldRequest() &&
            checkSelfPermission(NotificationPermissionCompat.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun isAccessibilityHelperEnabled(): Boolean =
        WirelessPairingAccessibilityService.isServiceEnabled(this)

    private fun isDeveloperOptionsEnabled(): Boolean = runCatching {
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrDefault(false)

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun dp(v: Int) = UiComponents.dp(this, v)
    private fun dpf(v: Float) = UiComponents.dpf(this, v)
}
