package com.miaocast.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/** AAC-LC 48 kHz 立体声 256 kbps；每输入 1024 帧 PCM 产出一个原始 AU（不含 ADTS）。 */
class AacEncoder(private val onAu: (ByteArray) -> Unit) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 4 * 2)
        }
        configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private var frames = 0L
    var produced = 0L
        private set

    fun encode(pcm: ByteArray, len: Int) {
        val inIdx = codec.dequeueInputBuffer(20_000)
        if (inIdx >= 0) {
            codec.getInputBuffer(inIdx)!!.apply { clear(); put(pcm, 0, len) }
            codec.queueInputBuffer(inIdx, 0, len, frames * 1_000_000 / 48_000, 0)
            frames += len / 4
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out < 0) break
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val b = ByteArray(info.size)
                codec.getOutputBuffer(out)!!.apply { position(info.offset); get(b) }
                produced++
                onAu(b)
            }
            codec.releaseOutputBuffer(out, false)
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }
}
