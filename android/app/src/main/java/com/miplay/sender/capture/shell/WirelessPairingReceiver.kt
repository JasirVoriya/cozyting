package com.miplay.sender.capture.shell

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收通知栏 `RemoteInput` 提交的 6 位无线调试配对码并在后台线程完成配对。
 */
class WirelessPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            PairingNotificationGuide.ACTION_CANCEL_PAIRING -> {
                PairingNotificationGuide.cancelNotification(context)
            }
            PairingNotificationGuide.ACTION_SUBMIT_PAIRING_CODE -> {
                val input = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(PairingNotificationGuide.KEY_PAIRING_CODE)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val pending = goAsync()
                val appCtx = context.applicationContext
                Thread({
                    try {
                        var port = PairingNotificationGuide.lastDiscoveredPairingPort
                        if (port !in 1..65535) {
                            port = WirelessAdbDiscovery(appCtx)
                                .discoverOnce(WirelessAdbDiscovery.SERVICE_PAIRING, timeoutMs = 2_500L)
                                ?.port ?: 0
                        }
                        if (port !in 1..65535) {
                            PairingNotificationGuide.postResultNotification(
                                appCtx,
                                success = false,
                                messageZh = "未发现无线调试配对端口，请保持「使用配对码配对设备」弹窗开启后重试",
                            )
                            return@Thread
                        }
                        val store = PairingNotificationGuide.keyStore(appCtx)
                        val identity = store.getOrCreateIdentity()
                        when (val res = WirelessAdbPairingClient.pair("127.0.0.1", port, input, identity)) {
                            is WirelessAdbPairingClient.PairResult.Success -> {
                                store.markPaired()
                                PairingNotificationGuide.stopPairingWatcher()
                                PairingFloatingOverlay.dismiss(appCtx)
                                PairingNotificationGuide.postResultNotification(
                                    appCtx,
                                    success = true,
                                    messageZh = "已保存本机配对证书，返回妙投即可启用无感采集",
                                )
                            }
                            is WirelessAdbPairingClient.PairResult.Failure -> {
                                PairingNotificationGuide.postResultNotification(
                                    appCtx,
                                    success = false,
                                    messageZh = res.messageZh,
                                )
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }, "wireless-adb-pair").start()
            }
        }
    }
}
