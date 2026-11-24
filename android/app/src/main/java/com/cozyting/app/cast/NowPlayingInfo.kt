package com.cozyting.app.cast

import android.content.Context
import android.graphics.Bitmap
import com.cozyting.app.R

/**
 * 正在播放信息。字段为 null/空 = 未从 MediaSession 取得，UI 必须诚实占位，禁止伪造曲名封面。
 */
data class NowPlayingInfo(
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
    val positionMs: Long?,
    val isPlaying: Boolean?,
    val artwork: Bitmap?,
    val sourcePackage: String?,
    val available: Boolean,
    val placeholderReasonZh: String,
) {
    companion object {
        fun unavailable(reason: String) = NowPlayingInfo(
            title = null,
            artist = null,
            durationMs = null,
            positionMs = null,
            isPlaying = null,
            artwork = null,
            sourcePackage = null,
            available = false,
            placeholderReasonZh = reason,
        )

        fun displayTitle(info: NowPlayingInfo): String =
            info.title?.takeIf { it.isNotBlank() } ?: "未知标题"

        fun displayTitle(context: Context, info: NowPlayingInfo): String =
            info.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.main_unknown_title)

        fun displayArtist(info: NowPlayingInfo): String =
            info.artist?.takeIf { it.isNotBlank() }
                ?: if (info.available) "未知艺人" else info.placeholderReasonZh

        fun displayArtist(context: Context, info: NowPlayingInfo): String =
            info.artist?.takeIf { it.isNotBlank() }
                ?: if (info.available) context.getString(R.string.main_unknown_artist) else info.placeholderReasonZh

        fun formatTime(ms: Long?): String {
            if (ms == null || ms < 0) return "—:—"
            val totalSec = (ms / 1000).toInt()
            val m = totalSec / 60
            val s = totalSec % 60
            return "%02d:%02d".format(m, s)
        }
    }
}
