package com.miplay.sender.cast

import android.content.Context
import android.media.AudioManager

/** 生产环境 [LocalOutputMute.StreamMusicVolume] 适配。 */
class AndroidStreamMusicVolume(context: Context) : LocalOutputMute.StreamMusicVolume {
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
}
