package com.miaocast.app.capture

import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection

/**
 * 唯一允许的 App 音频捕获构建入口：强制 UID + usage。
 * 无 UID 时抛错，防止退化成全系统捕获。
 */
object PlaybackCaptureFactory {
    fun buildAppCapture(projection: MediaProjection, uid: Int): AudioPlaybackCaptureConfiguration {
        val spec = CaptureConfigSpec.appScoped(uid = uid)
        val b = AudioPlaybackCaptureConfiguration.Builder(projection)
        for (u in spec.matchingUids) {
            b.addMatchingUid(u)
        }
        for (usage in spec.matchingUsages) {
            b.addMatchingUsage(usage)
        }
        return b.build()
    }

    /** 将 CaptureConfigSpec 的 usage 常量映射到框架常量（防御漂移）。 */
    fun frameworkUsages(): IntArray = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN,
    )
}
