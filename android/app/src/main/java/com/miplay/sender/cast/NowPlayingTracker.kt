package com.miplay.sender.cast

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.miplay.sender.NowPlayingNotificationListener

/**
 * 读取源 App MediaSession。无通知使用权时 getActiveSessions 通常为空 → 诚实占位。
 * 控制键通过 [MediaController.TransportControls] 转发；无会话时 no-op。
 */
class NowPlayingTracker(
    private val context: Context,
    private val onUpdate: (NowPlayingInfo) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private var controller: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            controller = null
            publish()
        }
    }
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refreshControllers(it) }

    fun start() {
        val cn = ComponentName(context, NowPlayingNotificationListener::class.java)
        runCatching {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            refreshControllers(msm?.getActiveSessions(cn))
        }.onFailure {
            Log.i(TAG, "MediaSession 不可用: ${it.message}")
            onUpdate(
                NowPlayingInfo.unavailable("无法读取 MediaSession（需通知使用权或系统限制）"),
            )
        }
        // 无监听器权限时 getActiveSessions 可能抛 SecurityException 或返回空
        if (controller == null) {
            onUpdate(
                NowPlayingInfo.unavailable("未取得正在播放（请在系统设置中开启通知使用权）"),
            )
        }
    }

    fun stop() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    fun playPause() {
        val c = controller ?: return
        val st = c.playbackState?.state
        if (st == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun skipNext() {
        controller?.transportControls?.skipToNext()
    }

    fun skipPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(posMs: Long) {
        controller?.transportControls?.seekTo(posMs)
    }

    private fun refreshControllers(list: List<MediaController>?) {
        val preferredPkg = com.miplay.sender.CastService.targetPackage
        val next = list
            ?.firstOrNull { preferredPkg.isNotBlank() && it.packageName == preferredPkg }
            ?: list?.firstOrNull {
                val st = it.playbackState?.state
                st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
            }
            ?: list?.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            onUpdate(
                NowPlayingInfo.unavailable("未取得正在播放（请在系统设置中开启通知使用权）"),
            )
            return
        }
        val md = c.metadata
        val st = c.playbackState
        val art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val pos = st?.position?.takeIf { it >= 0 }
        val playing = when (st?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> false
            else -> null
        }
        onUpdate(
            NowPlayingInfo(
                title = title,
                artist = artist,
                durationMs = dur,
                positionMs = pos,
                isPlaying = playing,
                artwork = art,
                sourcePackage = c.packageName,
                available = title != null || artist != null || art != null,
                placeholderReasonZh = if (title == null) "会话无标题字段" else "",
            ),
        )
    }

    companion object {
        private const val TAG = "NowPlaying"
    }
}
