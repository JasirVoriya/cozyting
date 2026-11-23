package com.miplay.sender

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
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
import android.widget.ScrollView
import android.widget.TextView
import com.miplay.sender.capture.CaptureEngineCapabilities
import com.miplay.sender.capture.CaptureEngineMode
import com.miplay.sender.capture.CaptureScopeMode
import com.miplay.sender.capture.LocalFilterAcceptanceGate
import com.miplay.sender.capture.PrefsTargetAppStore
import com.miplay.sender.capture.shell.PairingNotificationGuide
import com.miplay.sender.capture.shell.WirelessAdbDiscovery
import com.miplay.sender.capture.shell.WirelessAdbShellClient
import com.miplay.sender.capture.shell.WirelessDebugGuideActivity
import com.miplay.sender.cast.CastProtocol
import com.miplay.sender.cast.CastRouteSelection
import com.miplay.sender.cast.PrefsDiscoveredDeviceStore
import com.miplay.sender.compat.CaptureSelectionLayers
import com.miplay.sender.compat.WindowInsetsApplyCompat
import com.miplay.sender.ui.PrefsCastSettingsStore
import com.miplay.sender.ui.ThemeMode
import com.miplay.sender.ui.UiComponents
import com.miplay.sender.ui.UiPalette
import com.miplay.sender.update.AppUpdateManager
import com.miplay.sender.update.UpdateChannel
import com.miplay.sender.update.UpdateCheckState
import com.miplay.sender.update.UpdateConfig
import com.miplay.sender.update.UpdateDecisionStatus
import com.miplay.sender.update.UpdateDialogController
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
    private var debugExpanded = false
    private var wirelessFeedbackText: String = ""
    private var wirelessFeedbackIsError: Boolean = false
    private var pendingLocalProbe: Intent? = null
    private var localProbeStatus: TextView? = null
    private var localProbeBtn: TextView? = null
    private var devicesChanged = false
    private var triggerDiscover = false
    private var pendingOpenDialogAfterManualCheck = false
    private var updateDialog: Dialog? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
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
        runCatching { updateDialog?.dismiss() }
        updateDialog = null
        main.removeCallbacks(probeTicker)
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
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_back, "返回") {
                finish()
            },
        )
        topBar.addView(
            TextView(this).apply {
                text = "设置"
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(topBar)

        // —— 卡片 1：外观与发现 ——
        root.addView(UiComponents.sectionTitle(this, palette, "外观与发现"))
        val appearanceCard = UiComponents.panelCard(this, palette, padDp = 14)
        appearanceCard.addView(
            TextView(this).apply {
                text = "主题模式"
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
                labels = modes.map { it.labelZh },
                initialIndex = modeIdx,
            ) { idx ->
                settingsStore.themeMode = modes[idx]
                renderUi()
            },
        )
        appearanceCard.addView(spacer(10))
        appearanceCard.addView(
            UiComponents.switchRow(
                context = this,
                palette = palette,
                title = "显示全部发现设备",
                subtitle = "包含未声明 MiPlay 能力的局域网节点（兜底排查用）",
                initialChecked = settingsStore.showAllDiscovered,
            ) { checked ->
                settingsStore.showAllDiscovered = checked
                devicesChanged = true
            },
        )
        appearanceCard.addView(spacer(10))
        appearanceCard.addView(
            UiComponents.tonalButton(this, palette, "立即重新发现局域网设备") {
                triggerDiscover = true
                finish()
            },
        )
        root.addView(appearanceCard)

        // —— 卡片 2：投送与系统权限 ——
        root.addView(UiComponents.sectionTitle(this, palette, "投送与系统权限"))
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
                title = "投送时手机静音",
                subtitle = if (trueSingleAppMuteActive) {
                    "Android 13+ 无感单 App 环回静音：仅静音当前投送的音乐 App，手机其他 App 照常发声"
                } else {
                    "默认静音全局 STREAM_MUSIC；关闭后保留手机本机出声"
                },
                initialChecked = !settingsStore.keepLocalOutput,
            ) { checked ->
                settingsStore.keepLocalOutput = !checked
            },
        )
        castCard.addView(
            TextView(this).apply {
                val boundaryNote = if (trueSingleAppMuteActive) {
                    "说明：当前启用无感单 App 环回模式，不再修改全局 STREAM_MUSIC；实际效果以真机 ROM 路由支持为准。"
                } else {
                    "说明：Android 标准普通权限仅支持按 App UID 捕获，无公开 API 单独静音第三方 App 本机输出。"
                }
                text = "当前状态：${CastService.localMuteStatusZh}\n$boundaryNote"
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(dp(4), dp(6), dp(4), dp(10))
            },
        )

        val notifGranted = isNotificationListenerEnabled()
        castCard.addView(
            UiComponents.switchRow(
                context = this,
                palette = palette,
                title = "自动识别正在播放的音源",
                subtitle = "自动跟随「常用音源」中开启自动识别且正在播放的 App（需通知使用权，投送中切歌自动热切换）",
                initialChecked = settingsStore.autoDetectAudioSource,
            ) { checked ->
                settingsStore.autoDetectAudioSource = checked
            },
        )
        castCard.addView(spacer(8))

        castCard.addView(
            permissionRow(
                title = "通知使用权（正在播放与自动识别）",
                subtitle = "用于读取正在播放曲目、播控及自动识别常用音源，不上传通知内容",
                granted = notifGranted,
                grantedLabel = "已开启",
                missingLabel = "未开启",
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        )
        castCard.addView(spacer(8))

        val batteryExempt = isBatteryOptimizationIgnored()
        castCard.addView(
            permissionRow(
                title = "电池优化豁免",
                subtitle = "减少后台投送被系统休眠中断的概率（不构成跨品牌保活保证）",
                granted = batteryExempt,
                grantedLabel = "已豁免",
                missingLabel = "未豁免",
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
        root.addView(castCard)

        // —— 卡片 3：连接与调试（默认折叠） ——
        root.addView(UiComponents.sectionTitle(this, palette, "高级与调试"))
        val debugCard = UiComponents.panelCard(this, palette, padDp = 14)
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
                text = "连接参数、手动 IP 与调试选项"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        debugMeta.addView(
            TextView(this).apply {
                text = "默认自动配置即可；排查网络或特殊音箱时再展开"
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
        root.addView(UiComponents.sectionTitle(this, palette, "关于与更新"))
        val updateCard = UiComponents.panelCard(this, palette, padDp = 14)
        buildAboutAndUpdateCard(updateCard)
        root.addView(updateCard)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.bgCanvas)
            addView(root)
        }
        WindowInsetsApplyCompat.applySystemBarsPadding(scroll)
        setContentView(scroll)
    }

    private fun buildDebugBody(body: LinearLayout) {
        // 手动添加音箱 IP
        body.addView(fieldLabel("手动添加设备 IP（逗号分隔）"))
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
        val manualIps = UiComponents.styledEditText(this, palette, "例如 192.168.1.100").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        manualRow.addView(manualIps)
        manualRow.addView(
            UiComponents.tonalButton(this, palette, "加入", primary = true) {
                val ips = manualIps.text.toString().split(',')
                    .map { it.trim() }
                    .filter { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }
                if (ips.isEmpty()) {
                    manualFeedback.text = "请输入合法 IPv4 地址（如 192.168.1.100）"
                    manualFeedback.setTextColor(palette.statusWarning)
                    manualFeedback.visibility = View.VISIBLE
                    return@tonalButton
                }
                val existing = deviceStore.loadDevices()
                val extra = ips.map { ip ->
                    CastRouteSelection.Device(
                        id = ip,
                        name = "手动 $ip",
                        kind = CastRouteSelection.Kind.SPEAKER,
                        castable = true,
                        protocol = CastProtocol.MIPLAY,
                    )
                }
                val merged = (existing + extra).distinctBy { it.id }
                deviceStore.saveDevices(merged)
                devicesChanged = true
                manualIps.setText("")
                manualFeedback.text = "已加入 ${ips.joinToString(", ")}，返回主界面即可勾选投送"
                manualFeedback.setTextColor(palette.statusSuccess)
                manualFeedback.visibility = View.VISIBLE
            },
        )
        body.addView(manualRow)
        body.addView(manualFeedback)

        // 音源模式
        body.addView(fieldLabel("捕获音源模式"))
        body.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = listOf("选定 App（UID 过滤）", "测试静音"),
                initialIndex = if (settingsStore.sourceMode == "silent") 1 else 0,
            ) { idx ->
                settingsStore.sourceMode = if (idx == 1) "silent" else "app"
            },
        )
        body.addView(spacer(10))

        // 播放缓冲延迟（输入框 + 生效按钮：投送中直接热重锚 TIME_OFFSET 生效，待机时保存并下次连接生效）
        val autoMs = CastService.autoDelayMs(this)
        body.addView(fieldLabel("播放缓冲延迟 ms（留空或 0 为自动：$autoMs ms；范围 50–5000）"))
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
            hintText = "自动 ($autoMs)",
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
                delayFeedback.text = "请输入 50–5000 之间的毫秒数（留空或填 0 恢复自动 $autoMs ms）"
                delayFeedback.setTextColor(palette.statusWarning)
                delayFeedback.visibility = View.VISIBLE
            } else {
                settingsStore.delayMs = parsed
                val effectiveMs = if (parsed > 0) parsed else autoMs
                val autoSuffix = if (parsed == 0) "（自动）" else ""
                val lowHint = if (effectiveMs < 250) "；低于 250ms 在弱网或多箱场景可能卡顿" else ""
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
                    delayFeedback.text = "已生效：当前投送已热更新为 ${effectiveMs}ms$autoSuffix（无需重连）$lowHint"
                    delayFeedback.setTextColor(if (effectiveMs < 250) palette.statusWarning else palette.statusSuccess)
                } else {
                    delayFeedback.text = "已保存：${effectiveMs}ms$autoSuffix，下次连接音箱时生效$lowHint"
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
            UiComponents.tonalButton(this, palette, "生效", primary = true) {
                applyDelayAction()
            },
        )
        body.addView(delayRow)
        body.addView(delayFeedback)
        body.addView(spacer(10))

        // 房间参考模式
        body.addView(fieldLabel("组网参考模式（ref_function）"))
        val refValues = listOf("", "single_room", "multi_room", "stereo")
        val refLabels = listOf("自动", "单箱", "多箱", "立体声")
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
        body.addView(fieldLabel("8899 认证链路"))
        body.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = listOf("自动", "明文 Legacy", "Safety 加密"),
                initialIndex = settingsStore.authMode,
            ) { idx ->
                settingsStore.authMode = idx
            },
        )
        body.addView(
            TextView(this).apply {
                text = CaptureSelectionLayers.settingsHintZh()
                textSize = 11f
                setTextColor(palette.textTertiary)
                setPadding(dp(2), dp(10), dp(2), 0)
            },
        )

        if (BuildConfig.IS_ACCEPTANCE) {
            body.addView(
                TextView(this).apply {
                    text = "验收默认音箱音量=0（静音测试）；停止投送后不恢复本机 STREAM_MUSIC 索引。"
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
                    "本地过滤验收：未运行"
                }
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(dp(2), 0, dp(2), dp(6))
            }
            body.addView(localProbeStatus)
            localProbeBtn = UiComponents.tonalButton(
                context = this,
                palette = palette,
                label = if (LocalFilterProbeService.running.get()) "停止本地过滤验收" else "启动本地音频过滤验收",
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
            localProbeStatus?.text = "请先停止音箱投送"
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
        localProbeStatus?.text = "本地过滤验收：等待投影授权…"
    }

    private fun stopLocalProbe() {
        startService(Intent(this, LocalFilterProbeService::class.java).setAction(LocalFilterProbeService.ACTION_STOP))
        localProbeBtn?.text = "启动本地音频过滤验收"
        localProbeStatus?.text = "已停止本地探针"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LOCAL_PROBE) return
        val p = pendingLocalProbe ?: return
        pendingLocalProbe = null
        if (resultCode != RESULT_OK || data == null) {
            localProbeStatus?.text = "本地过滤验收：授权被拒绝"
            return
        }
        startForegroundService(
            p.putExtra(LocalFilterProbeService.EXTRA_PROJ_CODE, resultCode)
                .putExtra(LocalFilterProbeService.EXTRA_PROJ_DATA, data),
        )
        localProbeBtn?.text = "停止本地过滤验收"
        localProbeStatus?.text = "本地过滤验收已启动"
    }

    private fun buildCaptureEngineSection(card: LinearLayout) {
        val caps = CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT)
        card.addView(
            TextView(this).apply {
                text = "采集引擎"
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
                labels = engines.map { it.labelZh },
                initialIndex = engineIdx,
            ) { idx ->
                settingsStore.captureEngineMode = engines[idx]
                wirelessFeedbackText = ""
                renderUi()
            },
        )
        card.addView(
            TextView(this).apply {
                text = caps.tierSummaryZh
                textSize = 11.5f
                setTextColor(palette.textSecondary)
                setPadding(dp(4), dp(6), dp(4), dp(4))
            },
        )

        if (settingsStore.captureEngineMode != CaptureEngineMode.WIRELESS_DEBUG) return

        if (!caps.supportsWirelessDebugEngine) {
            card.addView(
                TextView(this).apply {
                    text = "当前系统不支持内置无线调试免投屏采集，请切换回「标准投屏模式」。"
                    textSize = 11.5f
                    setTextColor(palette.statusWarning)
                    setPadding(dp(4), dp(2), dp(4), dp(4))
                },
            )
            return
        }

        val keyStore = PairingNotificationGuide.keyStore(this)
        val paired = keyStore.isPaired()
        card.addView(spacer(6))
        card.addView(
            permissionRow(
                title = "内置无线调试配对状态",
                subtitle = if (paired) {
                    "已保存本机 TLS 配对证书；投送前确保系统「无线调试」开启即可自动直连（点击查看向导）"
                } else {
                    "仅需首次按 5 步向导开启无障碍自动识别配对（或通知栏长按输入），无需悬浮窗权限或连接电脑"
                },
                granted = paired,
                grantedLabel = "已配对",
                missingLabel = "未配对",
            ) {
                openWirelessDebugGuide(autoVerify = paired)
            },
        )

        card.addView(spacer(8))
        card.addView(fieldLabel("无感模式捕获范围"))
        val scopes = listOf(CaptureScopeMode.SINGLE_APP, CaptureScopeMode.GLOBAL_MEDIA)
        val scopeIdx = scopes.indexOf(settingsStore.captureScopeMode).coerceAtLeast(0)
        card.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = scopes.map { it.labelZh },
                initialIndex = scopeIdx,
            ) { idx ->
                settingsStore.captureScopeMode = scopes[idx]
                renderUi()
            },
        )
        if (settingsStore.captureScopeMode == CaptureScopeMode.SINGLE_APP && !caps.supportsShellSingleAppPolicy) {
            card.addView(
                TextView(this).apply {
                    text = "提示：Android 11–12 不支持 shell 单 App 隔离（Fail-Closed 会拒绝启动），请选择「全局媒体（显式）」或切回「标准投屏模式」。"
                    textSize = 11.5f
                    setTextColor(palette.statusWarning)
                    setPadding(dp(4), dp(6), dp(4), dp(2))
                },
            )
        }

        card.addView(spacer(8))
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(
            UiComponents.tonalButton(
                this,
                palette,
                if (paired) "查看向导 / 重新配对" else "打开 5 步配对引导",
                primary = !paired,
            ) {
                openWirelessDebugGuide(autoVerify = paired)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            },
        )
        actionRow.addView(
            UiComponents.tonalButton(this, palette, "测试连接", primary = paired) {
                testWirelessAdbConnection()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        card.addView(actionRow)

        if (paired) {
            card.addView(spacer(8))
            card.addView(
                UiComponents.tonalButton(this, palette, "清除本机配对证书") {
                    keyStore.clearPairing()
                    PairingNotificationGuide.cancelNotification(this)
                    wirelessFeedbackText = "已清除本机无线调试配对证书"
                    wirelessFeedbackIsError = false
                    renderUi()
                },
            )
        }

        if (wirelessFeedbackText.isNotBlank()) {
            card.addView(
                TextView(this).apply {
                    text = wirelessFeedbackText
                    textSize = 11.5f
                    setTextColor(if (wirelessFeedbackIsError) palette.statusWarning else palette.statusSuccess)
                    setPadding(dp(4), dp(8), dp(4), 0)
                },
            )
        }
    }

    private fun openWirelessDebugGuide(autoVerify: Boolean) {
        startActivity(
            Intent(this, WirelessDebugGuideActivity::class.java)
                .putExtra(WirelessDebugGuideActivity.EXTRA_AUTO_VERIFY, autoVerify),
        )
    }

    private fun testWirelessAdbConnection() {
        val store = PairingNotificationGuide.keyStore(this)
        if (!store.isPaired()) {
            wirelessFeedbackText = "尚未配对，请先点击「去配对」完成 6 位码握手"
            wirelessFeedbackIsError = true
            renderUi()
            return
        }
        wirelessFeedbackText = "正在检测本机无线调试连接…"
        wirelessFeedbackIsError = false
        renderUi()
        val appCtx = applicationContext
        Thread({
            val ep = WirelessAdbDiscovery(appCtx).discoverOnce(WirelessAdbDiscovery.SERVICE_CONNECT, 3_000L)
            if (ep == null) {
                main.post {
                    wirelessFeedbackText = "未发现本机无线调试端口，请确认「开发者选项 → 无线调试」已开启"
                    wirelessFeedbackIsError = true
                    renderUi()
                }
                return@Thread
            }
            val identity = store.getOrCreateIdentity()
            val res = WirelessAdbShellClient.runShellCommand(ep.host, ep.port, identity, "id")
            main.post {
                when (res) {
                    is WirelessAdbShellClient.ShellResult.Ok -> {
                        wirelessFeedbackText = "无线调试连接正常（127.0.0.1:${ep.port} · ${res.output.take(48)}）"
                        wirelessFeedbackIsError = false
                    }
                    is WirelessAdbShellClient.ShellResult.Error -> {
                        wirelessFeedbackText = res.messageZh
                        wirelessFeedbackIsError = true
                    }
                }
                renderUi()
            }
        }, "test-wireless-adb").start()
    }

    private fun buildAboutAndUpdateCard(card: LinearLayout) {
        val state = AppUpdateManager.state
        val displayVer = AppUpdateManager.currentDisplayVersion()
        val isPre = UpdateChannel.isPrereleaseVersion(displayVer)
        val readyDecision = (state as? UpdateCheckState.Ready)?.decision
        val hasNew = readyDecision?.hasNewerVersion == true

        // 顶部版本信息行 + 状态胶囊
        val versionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        val verMeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val verTitle = buildString {
            append(getString(R.string.app_brand_name))
            append(" v").append(displayVer)
            if (BuildConfig.IS_ACCEPTANCE) {
                append(" · 验收版")
            } else if (isPre) {
                append(" · 预览版")
            }
        }
        verMeta.addView(
            TextView(this).apply {
                text = verTitle
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        val subLine = when (state) {
            is UpdateCheckState.Checking -> "正在检查公开更新清单…"
            is UpdateCheckState.Failed -> state.message
            is UpdateCheckState.Ready -> when (state.decision.status) {
                UpdateDecisionStatus.UPDATE_AVAILABLE -> {
                    val behind = if (state.decision.versionsBehind > 1) {
                        "（含 ${state.decision.versionsBehind} 个版本变更）"
                    } else {
                        ""
                    }
                    "发现新版本 ${state.decision.latestTag}$behind"
                }
                UpdateDecisionStatus.IGNORED -> "发现新版本 ${state.decision.latestTag}（已跳过提醒）"
                UpdateDecisionStatus.UNSUPPORTED_OS -> "新版本 ${state.decision.latestTag} 需要更高系统版本"
                UpdateDecisionStatus.UP_TO_DATE -> "当前已是最新版本 · 上次检查 ${formatCheckTime(state.checkedAtMs)}"
                UpdateDecisionStatus.ERROR -> state.decision.error.ifBlank { "更新解析异常" }
            }
            UpdateCheckState.Idle -> {
                val last = settingsStore.lastUpdateCheckMs
                if (last > 0L) "上次检查：${formatCheckTime(last)}" else "尚未检查更新 · 只读静态清单、零遥测"
            }
        }
        verMeta.addView(
            TextView(this).apply {
                text = subLine
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
        versionRow.addView(verMeta)

        val (pillText, pillFg, pillBg) = when {
            state is UpdateCheckState.Checking -> Triple("检测中…", palette.accentBright, palette.accentSoftBg)
            state is UpdateCheckState.Failed -> Triple(
                "检查失败",
                palette.statusWarning,
                Color.argb(30, Color.red(palette.statusWarning), Color.green(palette.statusWarning), Color.blue(palette.statusWarning)),
            )
            hasNew -> Triple("有新版本", palette.accentBright, palette.accentSoftBg)
            state is UpdateCheckState.Ready && state.decision.status == UpdateDecisionStatus.UP_TO_DATE -> Triple(
                "已是最新",
                palette.statusSuccess,
                Color.argb(28, Color.red(palette.statusSuccess), Color.green(palette.statusSuccess), Color.blue(palette.statusSuccess)),
            )
            else -> Triple("v$displayVer", palette.textSecondary, palette.controlSurface)
        }
        versionRow.addView(
            TextView(this).apply {
                text = pillText
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(pillFg)
                background = UiComponents.roundedDrawable(pillBg, UiComponents.dpf(this@SettingsActivity, 999f))
                setPadding(dp(9), dp(4), dp(9), dp(4))
            },
        )
        card.addView(versionRow)

        // 主操作按钮：检查更新 / 查看新版本
        val isChecking = state is UpdateCheckState.Checking
        val actionLabel = when {
            isChecking -> "正在检查更新…"
            hasNew -> "查看新版本 ${readyDecision!!.latestTag}"
            state is UpdateCheckState.Failed -> "重试检查更新"
            else -> "立即检查更新"
        }
        val actionBtn = UiComponents.tonalButton(
            context = this,
            palette = palette,
            label = actionLabel,
            primary = hasNew,
        ) {
            if (isChecking) return@tonalButton
            if (hasNew) {
                showUpdateDialog(readyDecision!!)
            } else {
                pendingOpenDialogAfterManualCheck = true
                AppUpdateManager.checkManually(this)
            }
        }.apply {
            isEnabled = !isChecking
            alpha = if (isChecking) 0.6f else 1f
        }
        card.addView(actionBtn)
        card.addView(spacer(10))

        // 启动时自动检查开关（默认开启，可一键关闭以 100% 零外网请求）
        card.addView(
            UiComponents.switchRow(
                context = this,
                palette = palette,
                title = "启动时自动检查更新",
                subtitle = "每 24 小时仅向公开更新清单发起一次只读 HTTPS 请求，不上传任何设备或局域网数据",
                initialChecked = settingsStore.autoCheckUpdates,
            ) { checked ->
                settingsStore.autoCheckUpdates = checked
            },
        )
        card.addView(spacer(10))

        // 更新通道分段选择：[稳定版 | 预览版 (RC)]
        card.addView(fieldLabel("更新通道"))
        val channels = listOf(UpdateChannel.STABLE, UpdateChannel.PRERELEASE)
        val currentCh = settingsStore.resolvedUpdateChannel(BuildConfig.VERSION_NAME)
        val chIdx = channels.indexOf(currentCh).coerceAtLeast(0)
        card.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = channels.map { it.labelZh },
                initialIndex = chIdx,
            ) { idx ->
                settingsStore.updateChannelRaw = channels[idx].id
                AppUpdateManager.reevaluateFromCache(this, manual = false)
                renderUi()
            },
        )
        card.addView(spacer(10))

        // 公开 Release 与完整更新日志外链行（无权限绿点，简洁右箭头）
        card.addView(
            LinearLayout(this).apply {
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
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.PUBLIC_RELEASES_WEB_URL)))
                    }
                }
                val meta = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8)
                    }
                }
                meta.addView(
                    TextView(this@SettingsActivity).apply {
                        text = "公开版本发布页与完整更新日志"
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(palette.textPrimary)
                    },
                )
                meta.addView(
                    TextView(this@SettingsActivity).apply {
                        text = "github.com/${UpdateConfig.PUBLIC_RELEASE_REPO}/releases"
                        textSize = 11.5f
                        setTextColor(palette.textSecondary)
                        setPadding(0, dp(2), 0, 0)
                    },
                )
                addView(meta)
                addView(
                    TextView(this@SettingsActivity).apply {
                        text = "打开"
                        textSize = 11.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(palette.textSecondary)
                        setPadding(0, 0, dp(4), 0)
                    },
                )
                addView(
                    ImageView(this@SettingsActivity).apply {
                        setImageResource(R.drawable.ic_ui_chevron_right)
                        setColorFilter(palette.textTertiary)
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                    },
                )
            },
        )
    }

    private fun showUpdateDialog(decision: com.miplay.sender.update.UpdateDecision) {
        runCatching { updateDialog?.dismiss() }
        updateDialog = UpdateDialogController.show(this, palette, decision) {
            updateDialog = null
            if (!isFinishing && !isDestroyed) renderUi()
        }
    }

    private fun formatCheckTime(tsMs: Long): String {
        if (tsMs <= 0L) return "刚刚"
        val diff = (System.currentTimeMillis() - tsMs).coerceAtLeast(0L)
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(tsMs))
        }
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
