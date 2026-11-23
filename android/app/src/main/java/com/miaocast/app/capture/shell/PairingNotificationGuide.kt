package com.miaocast.app.capture.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import com.miaocast.app.R
import com.miaocast.app.compat.ShellCaptureCompat
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 无线调试 6 位配对码会话控制器：
 * - 主路径（已开启「无障碍自动配对」）：通过 [WirelessPairingAccessibilityService] 在系统设置上方使用
 *   `TYPE_ACCESSIBILITY_OVERLAY` 挂载 [PairingFloatingOverlay]（无需悬浮窗权限、不被 Android 12+ Settings 隐藏），
 *   并在弹出配对码弹窗时自动提取 6 位 PIN 码与端口完成秒配；
 * - 保底兜底（始终同步开启）：无论是否开启无障碍，均推送带 `RemoteInput` 的通知栏横幅，并在文案中醒目提示
 *   「若未看到输入按钮，请长按本条通知展开输入框」。
 */
object PairingNotificationGuide {
    /** 检测到配对端口后使用的高优先级横幅通道（弹出 Heads-Up 直接露出「输入 6 位配对码」按钮）。 */
    const val CHANNEL_ID = "miaocast_wireless_adb_pairing_v2"
    /** 进入开发者选项前的静默等待通道，避免提前弹窗导致后续真正检测到配对码时被系统抑制横幅。 */
    const val CHANNEL_ID_WAITING = "miaocast_wireless_adb_waiting"
    const val NOTIF_ID = 202
    const val ACTION_SUBMIT_PAIRING_CODE = "com.miaocast.app.action.SUBMIT_WIRELESS_ADB_PAIRING_CODE"
    const val ACTION_CANCEL_PAIRING = "com.miaocast.app.action.CANCEL_WIRELESS_ADB_PAIRING"
    const val KEY_PAIRING_CODE = "wireless_adb_pairing_code"

    private val activeDiscovery = AtomicReference<WirelessAdbDiscovery?>(null)
    @Volatile var isPairingSessionActive: Boolean = false
        private set
    @Volatile var lastDiscoveredPairingPort: Int = 0
        private set
    @Volatile private var lastHeadsUpPort: Int = 0

    fun keyStore(context: Context): WirelessAdbKeyStore =
        WirelessAdbKeyStore(File(context.applicationContext.filesDir, "wireless_adb"))

    fun startPairingSession(context: Context) {
        val appCtx = context.applicationContext
        stopPairingWatcher()
        isPairingSessionActive = true
        lastDiscoveredPairingPort = 0
        lastHeadsUpPort = 0

        val discovery = WirelessAdbDiscovery(appCtx)
        activeDiscovery.set(discovery)
        discovery.startWatching(WirelessAdbDiscovery.SERVICE_PAIRING) { ep ->
            lastDiscoveredPairingPort = ep.port
            if (PairingFloatingOverlay.canShowAccessibilityOverlay()) {
                PairingFloatingOverlay.showOrUpdate(appCtx, ep.port)
                WirelessPairingAccessibilityService.requestImmediateScan()
            }
            // 无论是否开启无障碍，始终保留通知栏 RemoteInput 横幅作为保底兜底
            postWaitingForCodeNotification(appCtx, ep.port)
        }
        if (PairingFloatingOverlay.canShowAccessibilityOverlay()) {
            PairingFloatingOverlay.showOrUpdate(appCtx, newDetectedPort = 0)
            WirelessPairingAccessibilityService.requestImmediateScan()
        }
        postWaitingForCodeNotification(appCtx, detectedPort = 0)
    }

    /** 无障碍服务自动识别到系统配对弹窗中的 6 位 PIN 码与端口时回调。 */
    fun onAutoExtractedPairingInfo(context: Context, pinCode: String, pairingPort: Int) {
        if (!isPairingSessionActive) return
        if (pairingPort in 1024..65535) {
            lastDiscoveredPairingPort = pairingPort
        }
        PairingFloatingOverlay.onAutoDetectedPinAndPort(context, pinCode, pairingPort)
    }

    fun stopPairingWatcher() {
        isPairingSessionActive = false
        activeDiscovery.getAndSet(null)?.stopWatching()
    }

    fun cancelNotification(context: Context) {
        stopPairingWatcher()
        lastDiscoveredPairingPort = 0
        lastHeadsUpPort = 0
        PairingFloatingOverlay.dismiss(context)
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIF_ID)
    }

    fun postWaitingForCodeNotification(context: Context, detectedPort: Int) {
        val nm = ensureChannels(context) ?: return
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel("输入 6 位无线调试配对码")
            .build()

        val replyIntent = Intent(context, WirelessPairingReceiver::class.java)
            .setAction(ACTION_SUBMIT_PAIRING_CODE)
        val replyPi = PendingIntent.getBroadcast(
            context,
            2021,
            replyIntent,
            ShellCaptureCompat.mutablePendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )
        val replyAction = Notification.Action.Builder(
            null,
            "输入 6 位配对码",
            replyPi,
        )
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .addRemoteInput(remoteInput)
            .build()

        val cancelIntent = Intent(context, WirelessPairingReceiver::class.java)
            .setAction(ACTION_CANCEL_PAIRING)
        val cancelPi = PendingIntent.getBroadcast(
            context,
            2022,
            cancelIntent,
            ShellCaptureCompat.immutablePendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )

        val isReady = detectedPort > 0
        val shouldTriggerHeadsUp = isReady && detectedPort != lastHeadsUpPort
        if (shouldTriggerHeadsUp) {
            lastHeadsUpPort = detectedPort
            // 先撤销静默等待态通知，再以高优通道全新投递，确保系统在配对码弹窗出现瞬间弹出顶部横幅
            nm.cancel(NOTIF_ID)
        }

        val title = if (isReady) {
            "无线调试配对就绪（端口 $detectedPort）"
        } else {
            "等待打开「使用配对码配对设备」弹窗…"
        }
        val text = if (isReady) {
            "请点下方「输入 6 位配对码」填入数字。若未看到输入按钮，请【长按本条通知】（或单指下滑本通知）展开输入框，勿切回 App"
        } else {
            "进入「无线调试 → 使用配对码配对设备」。若未开启自动识别，下拉通知栏并【长按本条通知】即可展开配对码输入框"
        }

        val channelId = if (isReady) CHANNEL_ID else CHANNEL_ID_WAITING
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_miplay)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOngoing(true)
            .setOnlyAlertOnce(!shouldTriggerHeadsUp)
            .addAction(replyAction)
            .addAction(Notification.Action.Builder(null, "取消", cancelPi).build())
        nm.notify(NOTIF_ID, builder.build())
    }

    fun postResultNotification(context: Context, success: Boolean, messageZh: String) {
        val nm = ensureChannels(context) ?: return
        lastHeadsUpPort = 0
        val notif = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_miplay)
            .setContentTitle(if (success) "无线调试配对成功" else "无线调试配对失败")
            .setContentText(messageZh)
            .setStyle(Notification.BigTextStyle().bigText(messageZh))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun ensureChannels(context: Context): NotificationManager? {
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return null
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_WAITING, "无线调试等待提示（静默）", NotificationManager.IMPORTANCE_LOW).apply {
                description = "进入系统开发者选项前的静默状态提示"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "无线调试配对码输入横幅", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "检测到无线调试配对窗口时在顶部弹出 6 位配对码输入按钮"
                enableVibration(false)
            },
        )
        return nm
    }
}
