package com.cozyting.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 仅用于取得 [android.media.session.MediaSessionManager.getActiveSessions] 所需的通知使用权。
 * 不解析通知内容、不上传。用户需在系统设置中手动开启。
 */
class NowPlayingNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
        sessionChangeCallbacks.forEach { cb ->
            runCatching { cb() }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    companion object {
        private const val TAG = "NowPlayingNL"
        private val sessionChangeCallbacks = CopyOnWriteArraySet<() -> Unit>()

        fun registerSessionChangeCallback(cb: () -> Unit) {
            sessionChangeCallbacks.add(cb)
        }

        fun unregisterSessionChangeCallback(cb: () -> Unit) {
            sessionChangeCallbacks.remove(cb)
        }
    }
}
