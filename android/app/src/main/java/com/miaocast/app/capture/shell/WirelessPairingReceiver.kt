package com.miaocast.app.capture.shell

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.miaocast.app.R
import com.miaocast.app.ui.AppLocaleManager

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
                        val locCtx = AppLocaleManager.localizedContext(appCtx)
                        if (port !in 1..65535) {
                            PairingNotificationGuide.postResultNotification(
                                locCtx,
                                success = false,
                                messageZh = locCtx.getString(R.string.pairing_receiver_no_port),
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
                                    locCtx,
                                    success = true,
                                    messageZh = locCtx.getString(R.string.pairing_receiver_saved),
                                )
                            }
                            is WirelessAdbPairingClient.PairResult.Failure -> {
                                PairingNotificationGuide.postResultNotification(
                                    locCtx,
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
