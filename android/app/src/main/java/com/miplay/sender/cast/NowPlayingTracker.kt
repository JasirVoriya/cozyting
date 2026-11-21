package com.miplay.sender.cast

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.miplay.sender.NowPlayingNotificationListener

/**
 * 读取全部活跃源 App 的 MediaSession。无通知使用权时 getActiveSessions 通常为空 → 诚实占位。
 * 为所有活跃会话注册独立回调并追踪每次「开始播放」上升沿，避免旧软件未关闭时新软件起播被漏检。
 * 控制键通过当前选中 [MediaController.TransportControls] 转发；无会话时 no-op。
 */
class NowPlayingTracker(
    private val context: Context,
    private val onUpdate: (NowPlayingInfo) -> Unit,
    private val onSessionsChanged: (List<ActiveMediaSessionSelector.SessionSnapshot>) -> Unit = {},
) {
    private class ObservedController(
        val token: MediaSession.Token,
        val tokenKey: String,
        val controller: MediaController,
        val callback: MediaController.Callback,
        var wasPlaying: Boolean,
        var lastBecamePlayingSeq: Long,
        var listOrderIndex: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val observed = linkedMapOf<MediaSession.Token, ObservedController>()
    private var controller: MediaController? = null
    private var initialized = false
    private var seqCounter = 0L

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
            onSessionsChanged(emptyList())
        }
        // 无监听器权限时 getActiveSessions 可能抛 SecurityException 或返回空
        if (controller == null && observed.isEmpty()) {
            onUpdate(
                NowPlayingInfo.unavailable("未取得正在播放（请在系统设置中开启通知使用权）"),
            )
        }
    }

    fun stop() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        observed.values.forEach { entry ->
            runCatching { entry.controller.unregisterCallback(entry.callback) }
        }
        observed.clear()
        controller = null
        initialized = false
    }

    /** 回到前台、配置更新或后台保底轮询时主动拉取一次活跃会话，避免错过状态切换。 */
    fun refresh() {
        val cn = ComponentName(context, NowPlayingNotificationListener::class.java)
        runCatching {
            refreshControllers(msm?.getActiveSessions(cn))
        }
    }

    fun currentSessionsSnapshot(): List<ActiveMediaSessionSelector.SessionSnapshot> = buildSnapshots()

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

    private fun isPlayingState(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    private fun refreshControllers(list: List<MediaController>?) {
        val incoming = list.orEmpty()
        val incomingTokens = incoming.mapTo(linkedSetOf()) { it.sessionToken }

        // 清理已消失的会话回调
        val iter = observed.entries.iterator()
        while (iter.hasNext()) {
            val (token, entry) = iter.next()
            if (token !in incomingTokens) {
                runCatching { entry.controller.unregisterCallback(entry.callback) }
                iter.remove()
            }
        }

        val isInitialSnapshot = !initialized
        if (isInitialSnapshot) {
            seqCounter = incoming.size.toLong()
        }

        incoming.forEachIndexed { index, mc ->
            val token = mc.sessionToken
            val playingNow = isPlayingState(mc.playbackState?.state)
            val existing = observed[token]
            if (existing == null) {
                val initSeq = when {
                    !playingNow -> 0L
                    isInitialSnapshot -> (incoming.size - index).toLong()
                    else -> ++seqCounter
                }
                val tokenKey = "${mc.packageName}@${System.identityHashCode(token)}"
                lateinit var entry: ObservedController
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        if (controller?.sessionToken == token) {
                            publish()
                        }
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        val nowPlaying = isPlayingState(state?.state)
                        if (!entry.wasPlaying && nowPlaying) {
                            entry.lastBecamePlayingSeq = ++seqCounter
                        }
                        entry.wasPlaying = nowPlaying
                        reselectAndEmit()
                    }

                    override fun onSessionDestroyed() {
                        observed.remove(token)?.let { removed ->
                            runCatching { removed.controller.unregisterCallback(removed.callback) }
                        }
                        reselectAndEmit()
                    }
                }
                entry = ObservedController(
                    token = token,
                    tokenKey = tokenKey,
                    controller = mc,
                    callback = cb,
                    wasPlaying = playingNow,
                    lastBecamePlayingSeq = initSeq,
                    listOrderIndex = index,
                )
                observed[token] = entry
                runCatching { mc.registerCallback(cb, main) }
            } else {
                existing.listOrderIndex = index
                if (!existing.wasPlaying && playingNow) {
                    existing.lastBecamePlayingSeq = ++seqCounter
                }
                existing.wasPlaying = playingNow
            }
        }
        initialized = true
        reselectAndEmit()
    }

    private fun buildSnapshots(): List<ActiveMediaSessionSelector.SessionSnapshot> =
        observed.values
            .sortedBy { it.listOrderIndex }
            .map { entry ->
                ActiveMediaSessionSelector.SessionSnapshot(
                    tokenKey = entry.tokenKey,
                    packageName = entry.controller.packageName.orEmpty(),
                    isPlaying = entry.wasPlaying,
                    lastBecamePlayingSeq = entry.lastBecamePlayingSeq,
                    listOrderIndex = entry.listOrderIndex,
                )
            }

    private fun reselectAndEmit() {
        val snapshots = buildSnapshots()
        val preferredPkg = com.miplay.sender.CastService.targetPackage
        val isCasting = com.miplay.sender.CastService.running.get()
        val chosen = ActiveMediaSessionSelector.selectDisplaySession(
            sessions = snapshots,
            preferredPackage = preferredPkg,
            isCasting = isCasting,
            selfPackage = context.packageName,
        )
        controller = chosen?.let { sel ->
            observed.values.firstOrNull { it.tokenKey == sel.tokenKey }?.controller
        }
        publish()
        onSessionsChanged(snapshots)
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
