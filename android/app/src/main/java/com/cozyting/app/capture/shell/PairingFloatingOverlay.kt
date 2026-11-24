package com.cozyting.app.capture.shell

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cozyting.app.R
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.PrefsCastSettingsStore
import com.cozyting.app.ui.UiComponents
import com.cozyting.app.ui.UiPalette
import kotlin.math.abs

/**
 * 无线调试 6 位配对码顶部悬浮数字键盘（Style B 明暗自适应 · `TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_NOT_FOCUSABLE`）。
 *
 * 设计要点：
 * 1. 通过 [WirelessPairingAccessibilityService] 的 `TYPE_ACCESSIBILITY_OVERLAY` 窗口层级挂载，
 *    无需 `SYSTEM_ALERT_WINDOW` 悬浮窗权限，且在 Android 12+ / HyperOS 系统设置「无线调试 · 6 位码弹窗」
 *    页面不会被 `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` 隐藏；
 * 2. 全程保持 `FLAG_NOT_FOCUSABLE`，绝不抢夺系统设置「使用配对码配对设备」弹窗的窗口焦点，避免系统因失焦销毁配对码；
 * 3. 支持无障碍服务自动识别屏幕上的 6 位配对码与端口并自动填入触发秒配；同时内置超紧凑 2 行浮动数字键盘
 *    （1–5 + ⌫ / 6–0 + 配对）供同屏手动点按兜底。
 */
object PairingFloatingOverlay {
    private val main = Handler(Looper.getMainLooper())

    private var rootContainer: FrameLayout? = null
    private var attachedWindowManager: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var detectedPort: Int = 0
    private var manualPortBuffer: String = ""
    private var pinBuffer: String = ""
    private var expanded: Boolean = false
    private var userManuallyCollapsed: Boolean = false
    private var editingPort: Boolean = false
    private var pairingInProgress: Boolean = false
    private var pairingSucceeded: Boolean = false
    private var lastAutoTriedSignature: String = ""
    private var statusTextZh: String = ""
    private var statusTone: StatusTone = StatusTone.NEUTRAL

    internal enum class StatusTone {
        NEUTRAL,
        ACCENT,
        SUCCESS,
        WARNING,
    }

    /** 检查无障碍悬浮层是否就绪（无需 `SYSTEM_ALERT_WINDOW`）。 */
    fun canShowAccessibilityOverlay(): Boolean =
        WirelessPairingAccessibilityService.activeInstance != null

    fun isShowing(): Boolean = rootContainer != null

    fun showOrUpdate(context: Context, newDetectedPort: Int): Boolean {
        val a11yService = WirelessPairingAccessibilityService.activeInstance ?: return false
        val appCtx = AppLocaleManager.localizedContext(context)
        main.post {
            val portJustDiscovered = newDetectedPort > 0 && newDetectedPort != detectedPort
            if (newDetectedPort > 0) {
                detectedPort = newDetectedPort
            }
            if (rootContainer == null) {
                pinBuffer = ""
                manualPortBuffer = ""
                editingPort = false
                pairingInProgress = false
                pairingSucceeded = false
                userManuallyCollapsed = false
                lastAutoTriedSignature = ""
                expanded = detectedPort > 0
                statusTextZh = ""
                statusTone = StatusTone.NEUTRAL
                attachWindow(a11yService, appCtx)
            } else if (portJustDiscovered) {
                if (!userManuallyCollapsed) {
                    expanded = true
                }
                editingPort = false
                if (!pairingInProgress && !pairingSucceeded) {
                    statusTextZh = appCtx.getString(R.string.overlay_status_port_discovered, detectedPort)
                    statusTone = StatusTone.ACCENT
                }
                rebuildContent(appCtx)
                if (!pairingInProgress && !pairingSucceeded && pinBuffer.length == 6) {
                    triggerPairing(appCtx)
                }
            } else {
                rebuildContent(appCtx)
            }
        }
        return true
    }

    /**
     * 当无障碍服务从系统配对码弹窗自动提取到 6 位 PIN 码与端口时调用：
     * 自动展开顶部悬浮卡片、填入 6 位数字槽并立即触发配对。
     */
    fun onAutoDetectedPinAndPort(context: Context, pinCode: String, pairingPort: Int) {
        if (pinCode.length != 6 || !pinCode.all { it.isDigit() } || pairingPort !in 1024..65535) return
        val appCtx = AppLocaleManager.localizedContext(context)
        main.post {
            if (pairingInProgress || pairingSucceeded) return@post
            val sig = "$pairingPort:$pinCode"
            if (sig == lastAutoTriedSignature) return@post
            lastAutoTriedSignature = sig

            detectedPort = pairingPort
            pinBuffer = pinCode
            editingPort = false
            expanded = true
            userManuallyCollapsed = false
            statusTextZh = appCtx.getString(R.string.overlay_status_auto_detected, pinCode, pairingPort)
            statusTone = StatusTone.ACCENT

            val a11yService = WirelessPairingAccessibilityService.activeInstance
            if (rootContainer == null && a11yService != null) {
                attachWindow(a11yService, appCtx)
            } else {
                rebuildContent(appCtx)
            }
            triggerPairing(appCtx)
        }
    }

    fun dismiss(context: Context) {
        val appCtx = context.applicationContext
        main.post {
            val view = rootContainer
            val wm = attachedWindowManager ?: appCtx.getSystemService(WindowManager::class.java)
            rootContainer = null
            attachedWindowManager = null
            windowParams = null
            detectedPort = 0
            pinBuffer = ""
            manualPortBuffer = ""
            expanded = false
            userManuallyCollapsed = false
            editingPort = false
            pairingInProgress = false
            pairingSucceeded = false
            lastAutoTriedSignature = ""
            statusTextZh = ""
            if (view != null) {
                runCatching { wm?.removeViewImmediate(view) }
            }
        }
    }

    private fun attachWindow(a11yContext: Context, appCtx: Context) {
        val wm = a11yContext.getSystemService(WindowManager::class.java) ?: return
        val dm = appCtx.resources.displayMetrics
        val cardWidth = (dm.widthPixels * 0.94f).toInt().coerceAtMost(UiComponents.dp(appCtx, 384))
        val lp = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = UiComponents.dp(appCtx, 40)
        }
        val container = FrameLayout(a11yContext)
        rootContainer = container
        attachedWindowManager = wm
        windowParams = lp
        rebuildContent(appCtx)
        runCatching {
            wm.addView(container, lp)
        }.onFailure {
            rootContainer = null
            attachedWindowManager = null
            windowParams = null
        }
    }

    private fun rebuildContent(appCtx: Context) {
        val container = rootContainer ?: return
        val isDark = PrefsCastSettingsStore(appCtx).isDark(appCtx)
        val palette = UiPalette.forMode(isDark)
        container.removeAllViews()
        val effectivePort = resolveEffectivePort()
        val defaultStatus = when {
            statusTextZh.isNotBlank() -> statusTextZh
            editingPort -> appCtx.getString(R.string.overlay_status_editing_port)
            effectivePort > 0 -> appCtx.getString(R.string.overlay_status_port_ready, effectivePort)
            else -> appCtx.getString(R.string.overlay_status_wait_popup)
        }
        val defaultTone = when {
            statusTextZh.isNotBlank() -> statusTone
            editingPort -> StatusTone.WARNING
            effectivePort > 0 -> StatusTone.ACCENT
            else -> StatusTone.NEUTRAL
        }

        val card = buildOverlayCard(
            context = appCtx,
            palette = palette,
            expanded = expanded,
            effectivePort = effectivePort,
            editingPort = editingPort,
            manualPortText = manualPortBuffer,
            pinText = pinBuffer,
            statusMessage = defaultStatus,
            tone = defaultTone,
            pairingInProgress = pairingInProgress,
            onToggleExpand = {
                expanded = !expanded
                userManuallyCollapsed = !expanded
                rebuildContent(appCtx)
            },
            onTogglePortEdit = {
                if (!pairingInProgress && !pairingSucceeded) {
                    editingPort = !editingPort
                    statusTextZh = ""
                    rebuildContent(appCtx)
                }
            },
            onClose = {
                PairingNotificationGuide.cancelNotification(appCtx)
            },
            onDigitPressed = { digit ->
                onDigitTap(appCtx, digit)
            },
            onBackspacePressed = { clearAll ->
                onBackspaceTap(appCtx, clearAll)
            },
            onSubmitPressed = {
                onSubmitTap(appCtx)
            },
        )
        installDragTouchListener(appCtx, card)
        container.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun resolveEffectivePort(): Int {
        val manual = manualPortBuffer.toIntOrNull()
        if (manual != null && manual in 1..65535) return manual
        return detectedPort.takeIf { it in 1..65535 } ?: 0
    }

    private fun onDigitTap(appCtx: Context, digit: Char) {
        if (pairingInProgress || pairingSucceeded) return
        if (editingPort) {
            if (manualPortBuffer.length < 5) {
                manualPortBuffer += digit
                statusTextZh = ""
                rebuildContent(appCtx)
            }
            return
        }
        if (pinBuffer.length >= 6) {
            // 若上次配对失败后已满 6 位，再次点数字时自动清空重输
            pinBuffer = ""
        }
        pinBuffer += digit
        statusTextZh = ""
        rebuildContent(appCtx)
        if (pinBuffer.length == 6 && resolveEffectivePort() in 1..65535) {
            triggerPairing(appCtx)
        }
    }

    private fun onBackspaceTap(appCtx: Context, clearAll: Boolean) {
        if (pairingInProgress || pairingSucceeded) return
        statusTextZh = ""
        if (editingPort) {
            manualPortBuffer = if (clearAll || manualPortBuffer.isEmpty()) "" else manualPortBuffer.dropLast(1)
        } else {
            pinBuffer = if (clearAll || pinBuffer.length == 6) "" else pinBuffer.dropLast(1)
        }
        rebuildContent(appCtx)
    }

    private fun onSubmitTap(appCtx: Context) {
        if (pairingInProgress || pairingSucceeded) return
        if (editingPort) {
            val port = manualPortBuffer.toIntOrNull() ?: 0
            if (port !in 1..65535) {
                statusTextZh = appCtx.getString(R.string.guide_feedback_invalid_port)
                statusTone = StatusTone.WARNING
                rebuildContent(appCtx)
                return
            }
            editingPort = false
            statusTextZh = appCtx.getString(R.string.overlay_status_port_set, port)
            statusTone = StatusTone.ACCENT
            rebuildContent(appCtx)
            if (pinBuffer.length == 6) {
                triggerPairing(appCtx)
            }
            return
        }
        if (pinBuffer.length < 6) {
            statusTextZh = appCtx.getString(R.string.overlay_status_pin_incomplete, pinBuffer.length)
            statusTone = StatusTone.WARNING
            rebuildContent(appCtx)
            return
        }
        triggerPairing(appCtx)
    }

    private fun triggerPairing(appCtx: Context) {
        if (pairingInProgress || pairingSucceeded) return
        val code = pinBuffer
        if (code.length != 6) return
        pairingInProgress = true
        val initialPort = resolveEffectivePort()
        statusTextZh = if (initialPort > 0) {
            appCtx.getString(R.string.guide_feedback_pairing_start, initialPort)
        } else {
            appCtx.getString(R.string.overlay_status_probing_port)
        }
        statusTone = StatusTone.ACCENT
        rebuildContent(appCtx)

        Thread({
            var port = initialPort
            if (port !in 1..65535) {
                port = WirelessAdbDiscovery(appCtx)
                    .discoverOnce(WirelessAdbDiscovery.SERVICE_PAIRING, timeoutMs = 2_200L)
                    ?.port ?: 0
            }
            if (port !in 1..65535) {
                main.post {
                    pairingInProgress = false
                    editingPort = true
                    statusTextZh = appCtx.getString(R.string.overlay_status_no_port)
                    statusTone = StatusTone.WARNING
                    rebuildContent(appCtx)
                }
                return@Thread
            }
            detectedPort = port
            val store = PairingNotificationGuide.keyStore(appCtx)
            val identity = store.getOrCreateIdentity()
            val res = WirelessAdbPairingClient.pair("127.0.0.1", port, code, identity)
            main.post {
                pairingInProgress = false
                when (res) {
                    is WirelessAdbPairingClient.PairResult.Success -> {
                        pairingSucceeded = true
                        store.markPaired()
                        PairingNotificationGuide.stopPairingWatcher()
                        statusTextZh = appCtx.getString(R.string.overlay_status_success)
                        statusTone = StatusTone.SUCCESS
                        rebuildContent(appCtx)
                        main.postDelayed({
                            PairingNotificationGuide.cancelNotification(appCtx)
                            val guideIntent = Intent(appCtx, WirelessDebugGuideActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                                .putExtra(WirelessDebugGuideActivity.EXTRA_AUTO_VERIFY, true)
                            runCatching { appCtx.startActivity(guideIntent) }
                        }, 650L)
                    }
                    is WirelessAdbPairingClient.PairResult.Failure -> {
                        statusTextZh = appCtx.getString(R.string.overlay_status_fail_retry, res.messageZh)
                        statusTone = StatusTone.WARNING
                        rebuildContent(appCtx)
                    }
                }
            }
        }, "overlay-wireless-pair").start()
    }

    private fun installDragTouchListener(context: Context, dragTarget: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        dragTarget.setOnTouchListener { _, ev ->
            val lp = windowParams ?: return@setOnTouchListener false
            val container = rootContainer ?: return@setOnTouchListener false
            val wm = attachedWindowManager
                ?: context.applicationContext.getSystemService(WindowManager::class.java)
                ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        lp.x = startX + dx
                        lp.y = (startY + dy).coerceAtLeast(UiComponents.dp(context, 16))
                        runCatching { wm.updateViewLayout(container, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    wasDragging
                }
                else -> false
            }
        }
    }

    internal fun buildOverlayCardForTest(
        context: Context,
        isDark: Boolean,
        expanded: Boolean = true,
        detectedPort: Int = 39481,
        pinText: String = "482",
        statusMessage: String = context.getString(R.string.overlay_status_port_ready, detectedPort),
    ): View = buildOverlayCard(
        context = context,
        palette = UiPalette.forMode(isDark),
        expanded = expanded,
        effectivePort = detectedPort,
        editingPort = false,
        manualPortText = "",
        pinText = pinText,
        statusMessage = statusMessage,
        tone = StatusTone.ACCENT,
        pairingInProgress = false,
        onToggleExpand = {},
        onTogglePortEdit = {},
        onClose = {},
        onDigitPressed = {},
        onBackspacePressed = {},
        onSubmitPressed = {},
    )

    @Suppress("LongParameterList")
    private fun buildOverlayCard(
        context: Context,
        palette: UiPalette,
        expanded: Boolean,
        effectivePort: Int,
        editingPort: Boolean,
        manualPortText: String,
        pinText: String,
        statusMessage: String,
        tone: StatusTone,
        pairingInProgress: Boolean,
        onToggleExpand: () -> Unit,
        onTogglePortEdit: () -> Unit,
        onClose: () -> Unit,
        onDigitPressed: (Char) -> Unit,
        onBackspacePressed: (Boolean) -> Unit,
        onSubmitPressed: () -> Unit,
    ): LinearLayout {
        val dp: (Int) -> Int = { UiComponents.dp(context, it) }
        val dpf: (Float) -> Float = { UiComponents.dpf(context, it) }

        val cardBg = if (palette.isDark) Color.rgb(18, 23, 32) else Color.WHITE
        val borderColor = when {
            tone == StatusTone.SUCCESS -> palette.statusSuccess
            effectivePort > 0 || expanded -> palette.accent
            else -> palette.panelBorder
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = cardBg,
                radiusPx = dpf(20f),
                strokeColor = borderColor,
                strokeWidthPx = dp(if (expanded) 2 else 1).coerceAtLeast(1),
            )
            setPadding(dp(14), dp(11), dp(14), dp(12))
            elevation = dpf(14f)
        }

        // —— 1. 顶栏（可拖动 + 标题 + 端口胶囊 + 展开/收起 + 关闭） ——
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dotColor = when {
            tone == StatusTone.SUCCESS -> palette.statusSuccess
            effectivePort > 0 -> palette.statusSuccess
            else -> palette.accentBright
        }
        val dotSize = dp(7)
        header.addView(
            View(context).apply {
                background = UiComponents.roundedDrawable(dotColor, dotSize / 2f)
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { marginEnd = dp(7) }
            },
        )
        header.addView(
            TextView(context).apply {
                text = if (expanded) {
                    context.getString(R.string.overlay_title_expanded)
                } else {
                    context.getString(R.string.overlay_title_collapsed)
                }
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )

        if (expanded) {
            val portLabel = when {
                editingPort -> context.getString(R.string.overlay_port_done)
                effectivePort > 0 -> context.getString(R.string.overlay_port_locked, effectivePort)
                else -> context.getString(R.string.overlay_port_manual)
            }
            val portFg = when {
                editingPort -> Color.WHITE
                effectivePort > 0 -> palette.statusSuccess
                else -> palette.statusWarning
            }
            val portBg = when {
                editingPort -> palette.accent
                effectivePort > 0 -> Color.argb(
                    32,
                    Color.red(palette.statusSuccess),
                    Color.green(palette.statusSuccess),
                    Color.blue(palette.statusSuccess),
                )
                else -> Color.argb(
                    34,
                    Color.red(palette.statusWarning),
                    Color.green(palette.statusWarning),
                    Color.blue(palette.statusWarning),
                )
            }
            header.addView(
                TextView(context).apply {
                    text = portLabel
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(portFg)
                    background = UiComponents.rippleSurface(portBg, dpf(999f), palette.ripple)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onTogglePortEdit() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(6) }
                },
            )
        }

        header.addView(
            TextView(context).apply {
                text = if (expanded) {
                    context.getString(R.string.overlay_collapse)
                } else {
                    context.getString(R.string.overlay_expand)
                }
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (expanded) palette.textSecondary else palette.accentBright)
                background = UiComponents.rippleSurface(
                    fillColor = if (expanded) palette.controlSurface else palette.accentSoftBg,
                    radiusPx = dpf(999f),
                    rippleColor = palette.ripple,
                )
                setPadding(dp(9), dp(4), dp(9), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { onToggleExpand() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            },
        )

        val closeSize = dp(26)
        header.addView(
            FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(closeSize, closeSize)
                background = UiComponents.rippleSurface(palette.controlSurface, closeSize / 2f, palette.ripple)
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.overlay_close_desc)
                setOnClickListener { onClose() }
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_ui_close)
                        setColorFilter(palette.textSecondary)
                        layoutParams = FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER)
                    },
                )
            },
        )
        card.addView(header)

        if (!expanded) {
            card.addView(
                TextView(context).apply {
                    text = context.getString(R.string.overlay_collapsed_hint)
                    textSize = 11.5f
                    setTextColor(palette.textSecondary)
                    setPadding(0, dp(6), 0, 0)
                },
            )
            return card
        }

        // —— 2. 可选手动端口输入条 ——
        if (editingPort) {
            val portBox = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = UiComponents.roundedDrawable(
                    fillColor = palette.accentSoftBg,
                    radiusPx = dpf(10f),
                    strokeColor = palette.accentBright,
                    strokeWidthPx = dp(1).coerceAtLeast(1),
                )
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            }
            portBox.addView(
                TextView(context).apply {
                    text = context.getString(R.string.overlay_port_field_label)
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.textSecondary)
                },
            )
            portBox.addView(
                TextView(context).apply {
                    text = manualPortText.ifEmpty { context.getString(R.string.overlay_port_field_placeholder) }
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (manualPortText.isEmpty()) palette.textTertiary else palette.accentBright)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            card.addView(portBox)
        }

        // —— 3. 6 位配对码数字槽 ——
        val slotsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        for (i in 0 until 6) {
            val ch = pinText.getOrNull(i)
            val isCursor = !editingPort && i == pinText.length && !pairingInProgress
            val slotFill = when {
                isCursor -> palette.accentSoftBg
                ch != null -> palette.controlSurface
                else -> palette.rowIdleBg
            }
            val slotBorder = when {
                isCursor -> palette.accentBright
                ch != null -> palette.accent
                else -> palette.panelBorder
            }
            val strokeW = if (isCursor) dp(2).coerceAtLeast(2) else dp(1).coerceAtLeast(1)
            val slot = TextView(context).apply {
                text = ch?.toString().orEmpty()
                textSize = 16.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                gravity = Gravity.CENTER
                background = UiComponents.roundedDrawable(
                    fillColor = slotFill,
                    radiusPx = dpf(10f),
                    strokeColor = slotBorder,
                    strokeWidthPx = strokeW,
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (i > 0) marginStart = dp(5)
                }
                isClickable = true
                setOnClickListener {
                    if (editingPort) onTogglePortEdit()
                }
            }
            slotsRow.addView(slot)
        }
        card.addView(slotsRow)

        // —— 4. 状态提示行 ——
        val statusColor = when (tone) {
            StatusTone.SUCCESS -> palette.statusSuccess
            StatusTone.WARNING -> palette.statusWarning
            StatusTone.ACCENT -> palette.accentBright
            StatusTone.NEUTRAL -> palette.textSecondary
        }
        card.addView(
            TextView(context).apply {
                text = statusMessage
                textSize = 11f
                setTextColor(statusColor)
                setPadding(dp(2), dp(6), dp(2), dp(2))
            },
        )

        // —— 5. 超紧凑 2 行浮动数字键盘（1 2 3 4 5 ⌫ / 6 7 8 9 0 配对） ——
        fun buildKey(
            label: String,
            weight: Float = 1f,
            primary: Boolean = false,
            secondaryTone: Boolean = false,
            marginStartDp: Int = 5,
            onLongPress: (() -> Unit)? = null,
            onClick: () -> Unit,
        ): TextView {
            val fill = when {
                primary -> palette.accent
                secondaryTone -> palette.rowIdleBg
                else -> palette.controlSurface
            }
            val fg = when {
                primary -> Color.WHITE
                secondaryTone -> palette.textSecondary
                else -> palette.textPrimary
            }
            val border = if (primary) Color.TRANSPARENT else palette.panelBorder
            return TextView(context).apply {
                text = label
                textSize = if (label.length == 1 && label[0].isDigit()) 15.5f else 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(fg)
                gravity = Gravity.CENTER
                alpha = if (pairingInProgress) 0.55f else 1f
                background = UiComponents.rippleSurface(
                    fillColor = fill,
                    radiusPx = dpf(10f),
                    rippleColor = if (primary) Color.argb(56, 255, 255, 255) else palette.ripple,
                    strokeColor = border,
                    strokeWidthPx = if (primary) 0 else dp(1).coerceAtLeast(1),
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(36), weight).apply {
                    if (marginStartDp > 0) marginStart = dp(marginStartDp)
                }
                isClickable = !pairingInProgress
                isFocusable = !pairingInProgress
                setOnClickListener { onClick() }
                if (onLongPress != null) {
                    setOnLongClickListener {
                        onLongPress()
                        true
                    }
                }
            }
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        listOf('1', '2', '3', '4', '5').forEachIndexed { idx, d ->
            row1.addView(
                buildKey(
                    label = d.toString(),
                    marginStartDp = if (idx == 0) 0 else 5,
                ) {
                    onDigitPressed(d)
                },
            )
        }
        row1.addView(
            buildKey(
                label = "⌫",
                weight = 1.15f,
                secondaryTone = true,
                onLongPress = { onBackspacePressed(true) },
            ) {
                onBackspacePressed(false)
            },
        )
        card.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        }
        listOf('6', '7', '8', '9', '0').forEachIndexed { idx, d ->
            row2.addView(
                buildKey(
                    label = d.toString(),
                    marginStartDp = if (idx == 0) 0 else 5,
                ) {
                    onDigitPressed(d)
                },
            )
        }
        row2.addView(
            buildKey(
                label = if (editingPort) context.getString(R.string.overlay_key_done) else context.getString(R.string.overlay_key_pair),
                weight = 1.15f,
                primary = true,
            ) {
                onSubmitPressed()
            },
        )
        card.addView(row2)

        return card
    }
}
