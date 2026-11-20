package com.miplay.sender.compat

/**
 * 生产默认音箱音量保持 30；验收变体默认 0 便于静音测试。
 * 验收结束不把本机 STREAM_MUSIC 拉回 mute 前索引（避免破坏静音对照）。
 */
object AcceptanceAudioDefaults {
    fun speakerPremutePercent(isAcceptance: Boolean): Int = if (isAcceptance) 0 else 30

    fun restoreLocalVolumeIndex(isAcceptance: Boolean): Boolean = !isAcceptance
}
