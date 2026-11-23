package com.miaocast.app.cast

import android.content.Context
import android.media.AudioManager

/**
 * 生产环境 [LocalOutputMute.StreamMusicVolume] 适配。
 *
 * 禁止对尚未 attachBaseContext 的 Service 调用 [Context.getApplicationContext]：
 * 请在 Service.onCreate 之后用 [fromAttachedContext]，或直接注入 [AudioManager]。
 */
class AndroidStreamMusicVolume(
    private val am: AudioManager,
) : LocalOutputMute.StreamMusicVolume {

    override fun getIndex(): Int = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    override fun getMaxIndex(): Int = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    override fun isMuted(): Boolean = am.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun mute() {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    }

    override fun unmute() {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }

    override fun setIndex(index: Int) {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    companion object {
        /**
         * @param attachedContext 已附加的 Context（推荐 Service.applicationContext / Application）
         */
        fun fromAttachedContext(attachedContext: Context): AndroidStreamMusicVolume {
            val am = attachedContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return AndroidStreamMusicVolume(am)
        }
    }
}
