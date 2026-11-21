package com.miplay.sender.capture.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import com.miplay.sender.R
import com.miplay.sender.compat.ShellCaptureCompat
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 无线调试通知栏 `RemoteInput` 6 位配对码引导器。
 *
 * 用户在「开发者选项 → 无线调试 → 使用配对码配对设备」看到 6 位数字时，
 * 无需切回本 App（切回会导致系统关闭配对弹窗并销毁临时端口），直接下拉通知栏输入 6 位码即可完成配对。
 */
object PairingNotificationGuide {
    const val CHANNEL_ID = "miplay_wireless_adb_pairing"
    const val NOTIF_ID = 202
    const val ACTION_SUBMIT_PAIRING_CODE = "com.miplay.sender.action.SUBMIT_WIRELESS_ADB_PAIRING_CODE"
    const val ACTION_CANCEL_PAIRING = "com.miplay.sender.action.CANCEL_WIRELESS_ADB_PAIRING"
    const val KEY_PAIRING_CODE = "wireless_adb_pairing_code"

    private val activeDiscovery = AtomicReference<WirelessAdbDiscovery?>(null)
    @Volatile var lastDiscoveredPairingPort: Int = 0
        private set

    fun keyStore(context: Context): WirelessAdbKeyStore =
        WirelessAdbKeyStore(File(context.applicationContext.filesDir, "wireless_adb"))

    fun startPairingSession(context: Context) {
        val appCtx = context.applicationContext
        stopPairingWatcher()
        lastDiscoveredPairingPort = 0

        val discovery = WirelessAdbDiscovery(appCtx)
        activeDiscovery.set(discovery)
        discovery.startWatching(WirelessAdbDiscovery.SERVICE_PAIRING) { ep ->
            lastDiscoveredPairingPort = ep.port
            postWaitingForCodeNotification(appCtx, ep.port)
        }
        postWaitingForCodeNotification(appCtx, detectedPort = 0)
    }

    fun stopPairingWatcher() {
        activeDiscovery.getAndSet(null)?.stopWatching()
    }

    fun cancelNotification(context: Context) {
        stopPairingWatcher()
        lastDiscoveredPairingPort = 0
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIF_ID)
    }

    fun postWaitingForCodeNotification(context: Context, detectedPort: Int) {
        val nm = ensureChannel(context) ?: return
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
        ).addRemoteInput(remoteInput).build()

        val cancelIntent = Intent(context, WirelessPairingReceiver::class.java)
            .setAction(ACTION_CANCEL_PAIRING)
        val cancelPi = PendingIntent.getBroadcast(
            context,
            2022,
            cancelIntent,
            ShellCaptureCompat.immutablePendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )

        val title = if (detectedPort > 0) {
            "已检测到无线调试配对服务（端口 $detectedPort）"
        } else {
            "等待「使用配对码配对设备」窗口…"
        }
        val text = if (detectedPort > 0) {
            "请直接点击下方「输入 6 位配对码」填入系统显示的数字完成配对"
        } else {
            "请在系统设置进入「无线调试 → 使用配对码配对设备」，检测到端口后直接在此填入 6 位码"
        }

        val notif = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_miplay)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(replyAction)
            .addAction(Notification.Action.Builder(null, "取消", cancelPi).build())
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    fun postResultNotification(context: Context, success: Boolean, messageZh: String) {
        val nm = ensureChannel(context) ?: return
        val notif = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_miplay)
            .setContentTitle(if (success) "无线调试配对成功" else "无线调试配对失败")
            .setContentText(messageZh)
            .setStyle(Notification.BigTextStyle().bigText(messageZh))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(context: Context): NotificationManager? {
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return null
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "无线调试配对引导", NotificationManager.IMPORTANCE_HIGH),
        )
        return nm
    }
}
