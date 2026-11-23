package com.miaocast.app.capture.shell

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无线调试配对无障碍辅助服务：
 * 1. 提供 `TYPE_ACCESSIBILITY_OVERLAY` 专属窗口 Context，使 [PairingFloatingOverlay] 在 Android 12+ / HyperOS
 *    系统设置「无线调试 · 6 位配对码弹窗」页面上方正常悬浮显示（不被 `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` 隐藏），
 *    且无需额外申请 `SYSTEM_ALERT_WINDOW` 悬浮窗权限；
 * 2. 仅在配对会话激活期间（[PairingNotificationGuide.isPairingSessionActive] == true），
 *    通过 [WirelessPairingScreenParser] 跨语言、跨 ROM 自动提取当前设置弹窗中的 6 位配对码与端口，
 *    自动填入顶部悬浮键盘并完成 SPAKE2 + TLS 1.3 秒配；
 * 3. 配对完成后立即进入静默休眠，日常投送音乐时零节点遍历、零隐私读取。
 */
class WirelessPairingAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        internal var activeInstance: WirelessPairingAccessibilityService? = null
            private set

        /** 检查系统设置中是否已启用本无障碍服务（或服务实例已连接）。 */
        fun isServiceEnabled(context: Context): Boolean {
            if (activeInstance != null) return true
            return runCatching {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                if (enabled.isBlank()) return false
                val cn = ComponentName(context, WirelessPairingAccessibilityService::class.java)
                val full = cn.flattenToString()
                val short = cn.flattenToShortString()
                enabled.split(':').any { entry ->
                    entry.equals(full, ignoreCase = true) || entry.equals(short, ignoreCase = true)
                }
            }.getOrDefault(false)
        }

        /** 若服务已连接且当前处于配对会话，主动扫描一次当前屏幕窗口。 */
        fun requestImmediateScan() {
            activeInstance?.scanActiveWindowsIfNeeded()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        if (PairingNotificationGuide.isPairingSessionActive) {
            PairingFloatingOverlay.showOrUpdate(
                context = this,
                newDetectedPort = PairingNotificationGuide.lastDiscoveredPairingPort,
            )
            scanActiveWindowsIfNeeded()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!PairingNotificationGuide.isPairingSessionActive) return
        val pkg = event?.packageName?.toString().orEmpty()
        // 忽略本应用自身与常见输入法事件，仅在系统设置或弹窗变化时扫描
        if (pkg.isNotEmpty() && pkg == packageName) return
        scanActiveWindowsIfNeeded()
    }

    override fun onInterrupt() {
        // 无操作
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (activeInstance === this) {
            PairingFloatingOverlay.dismiss(this)
            activeInstance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            PairingFloatingOverlay.dismiss(this)
            activeInstance = null
        }
        super.onDestroy()
    }

    internal fun scanActiveWindowsIfNeeded() {
        if (!PairingNotificationGuide.isPairingSessionActive) return
        val snapshots = collectWindowSnapshots()
        if (snapshots.isEmpty()) return
        val extracted = WirelessPairingScreenParser.extract(
            nodes = snapshots,
            mdnsPairingPort = PairingNotificationGuide.lastDiscoveredPairingPort,
        ) ?: return
        if (extracted.pinCode.length == 6 && extracted.pairingPort in 1024..65535) {
            PairingNotificationGuide.onAutoExtractedPairingInfo(
                context = this,
                pinCode = extracted.pinCode,
                pairingPort = extracted.pairingPort,
            )
        }
    }

    private fun collectWindowSnapshots(): List<WirelessPairingScreenParser.NodeSnapshot> {
        val out = ArrayList<WirelessPairingScreenParser.NodeSnapshot>(64)
        val selfPkg = packageName
        runCatching {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (win in winList) {
                    val root = win.root ?: continue
                    val winPkg = root.packageName?.toString().orEmpty()
                    if (winPkg == selfPkg) continue
                    traverseNode(
                        node = root,
                        windowId = win.id,
                        windowLayer = win.layer,
                        out = out,
                    )
                }
            }
        }
        if (out.isEmpty()) {
            runCatching {
                val root = rootInActiveWindow ?: return@runCatching
                val winPkg = root.packageName?.toString().orEmpty()
                if (winPkg != selfPkg) {
                    traverseNode(
                        node = root,
                        windowId = root.windowId,
                        windowLayer = 0,
                        out = out,
                    )
                }
            }
        }
        return out
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        windowId: Int,
        windowLayer: Int,
        out: MutableList<WirelessPairingScreenParser.NodeSnapshot>,
        depth: Int = 0,
    ) {
        if (depth > 28 || out.size >= 240) return
        val viewId = runCatching { node.viewIdResourceName }.getOrNull()
        val text = runCatching { node.text?.toString() }.getOrNull()
        val desc = runCatching { node.contentDescription?.toString() }.getOrNull()
        if (!viewId.isNullOrBlank() || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
            out.add(
                WirelessPairingScreenParser.NodeSnapshot(
                    viewIdResourceName = viewId,
                    text = text,
                    contentDescription = desc,
                    windowId = windowId,
                    windowLayer = windowLayer,
                ),
            )
        }
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            traverseNode(child, windowId, windowLayer, out, depth + 1)
        }
    }
}
