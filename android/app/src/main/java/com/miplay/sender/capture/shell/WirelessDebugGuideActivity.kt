package com.miplay.sender.capture.shell

import android.app.Activity
import android.app.NotificationManager
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
import android.widget.ScrollView
import android.widget.TextView
import com.miplay.sender.R
import com.miplay.sender.capture.CaptureEngineCapabilities
import com.miplay.sender.capture.CaptureEngineMode
import com.miplay.sender.capture.CaptureScopeMode
import com.miplay.sender.compat.NotificationPermissionCompat
import com.miplay.sender.compat.WindowInsetsApplyCompat
import com.miplay.sender.ui.PrefsCastSettingsStore
import com.miplay.sender.ui.UiComponents
import com.miplay.sender.ui.UiPalette

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

    private var connectionVerified = false
    private var connectedPort = 0
    private var testingConnection = false
    private var accessibilitySkipped = false
    private var manualPairExpanded = false
    private var tipsExpanded = false
    private var feedbackText = ""
    private var feedbackIsError = false
    private var autoCheckPending = false

    // 供 Robolectric UI 预览单测注入状态
    private var previewStateOverride: WirelessDebugOnboardingState? = null
    private var previewDarkOverride: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        settingsStore = PrefsCastSettingsStore(this)
        autoCheckPending = intent?.getBooleanExtra(EXTRA_AUTO_VERIFY, false) == true
        renderUi()
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
                feedbackText = "已获得通知权限，请继续完成下一步配置"
                feedbackIsError = false
            } else {
                feedbackText = "通知权限未授予，可点击「立即允许通知」前往系统通知设置开启"
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
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_back, "返回") {
                finish()
            },
        )
        topBar.addView(
            TextView(this).apply {
                text = "无感采集配置向导"
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
            !state.supported -> "Android ${state.sdkInt} 不支持"
            state.connectionVerified -> "全部 $totalSteps 步已完成"
            else -> "配置进度 ${state.completedCount} / $totalSteps"
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
                    text = CaptureEngineCapabilities.forSdkInt(state.sdkInt).tierSummaryZh
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
                UiComponents.tonalButton(this, palette, "返回使用标准投屏模式", primary = true) {
                    settingsStore.captureEngineMode = CaptureEngineMode.STANDARD
                    finish()
                },
            )
            val scroll = ScrollView(this).apply {
                setBackgroundColor(palette.bgCanvas)
                addView(root)
            }
            WindowInsetsApplyCompat.applySystemBarsPadding(scroll)
            setContentView(scroll)
            return
        }

        // —— 4 步交互清单 ——
        root.addView(UiComponents.sectionTitle(this, palette, "配置步骤"))
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
                "完成并启用无感模式"
            } else {
                "已配对 · 直接启用无感模式"
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

        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.bgCanvas)
            addView(root)
        }
        WindowInsetsApplyCompat.applySystemBarsPadding(scroll)
        setContentView(scroll)
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
                    "正在检测连接…"
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
                        "跳过，用通知栏",
                    ) {
                        accessibilitySkipped = true
                        feedbackText = "已跳过无障碍：配对时将通过通知栏横幅输入 6 位码（若未看到输入框，请长按本条通知展开）"
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
                        if (manualPairExpanded) "收起手动" else "手动填码",
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
                        text = "提示：可将本 App 挂为「小窗」或与系统设置「分屏」，直接对照弹窗填入端口与 6 位配对码，无需操作通知栏："
                        textSize = 11f
                        setTextColor(palette.textTertiary)
                        setPadding(0, 0, 0, dp(6))
                    },
                )
                val inputs = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val portEdit = UiComponents.styledEditText(this, palette, "配对端口", InputType.TYPE_CLASS_NUMBER).apply {
                    if (PairingNotificationGuide.lastDiscoveredPairingPort > 0) {
                        setText(PairingNotificationGuide.lastDiscoveredPairingPort.toString())
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    }
                }
                val pinEdit = UiComponents.styledEditText(this, palette, "6位配对码", InputType.TYPE_CLASS_NUMBER).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    }
                }
                inputs.addView(portEdit)
                inputs.addView(pinEdit)
                inputs.addView(
                    UiComponents.tonalButton(this, palette, "配对", primary = true) {
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
                text = "常见问题与重启恢复说明"
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(chevron)
        card.addView(header)

        val tips = listOf(
            "• 为什么推荐无障碍而非悬浮窗：Android 12+ / HyperOS 会在系统「无线调试」与配对码页面自动屏蔽第三方普通悬浮窗；开启「妙投 · 无线调试自动配对助手」无障碍服务后，无需悬浮窗权限即可在 PIN 码页同屏显示顶部数字键盘，并自动读取屏幕上的 6 位配对码完成秒配。",
            "• 通知栏长按兜底：若未开启无障碍，点开系统「使用配对码配对设备」后，请下拉通知栏找到妙投通知并点「输入 6 位配对码」；若系统折叠了输入按钮，请直接【长按本条通知】（或单指下滑该通知）展开输入框。",
            "• 手机重启后：Android 会自动关闭「无线调试」，只需重新点开「无线调试」开关即可恢复，无需重新输入 6 位配对码。",
            "• 音箱听歌 + 手机刷视频互不打断：Android 13+ 独立单 App 无感投送会自动开启双区音频焦点隔离（仅投送期间生效，停止即自动还原），刷短视频不会暂停音箱音乐。",
            "• 必须连接 Wi-Fi：无线调试与小米音箱局域网投送均需在同一 Wi-Fi 下使用。",
            "• 状态栏小绿点：无感模式会彻底消除投屏弹窗与「正在投屏」横幅；部分 Android 12+ 机型右上角仍可能显示极小的系统录音隐私绿点，属系统隐私机制。",
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
                    "无障碍自动配对与顶部悬浮键盘已就绪：点开「无线调试 → 使用配对码配对设备」后将自动识别 6 位码并秒配（也保留通知栏长按输入兜底）"
                } else {
                    "已发送配对通知栏横幅：点开「无线调试 → 使用配对码配对设备」后下拉通知栏输入 6 位码（若未看到输入按钮，请长按本条通知展开）"
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
            feedbackText = "请输入合法配对端口（1–65535）"
            feedbackIsError = true
            renderUi()
            return
        }
        feedbackText = "正在向 127.0.0.1:$port 发起 TLS + SPAKE2 配对…"
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
                        feedbackText = "配对成功，正在自动验证连接…"
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
                feedbackText = "尚未完成第 4 步配对，请先在浮动框输入 6 位配对码"
                feedbackIsError = true
                renderUi()
            }
            return
        }
        testingConnection = true
        if (!silent) {
            feedbackText = "正在探测本机无线调试端口并校验 uid=2000 权限…"
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
                    feedbackText = "未检测到本机无线调试服务：请确认 Wi-Fi 已连接且「开发者选项 → 无线调试」开关已打开"
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
                        feedbackText = "连通验证通过（127.0.0.1:${ep.port} · ${res.output.take(44)}）"
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
