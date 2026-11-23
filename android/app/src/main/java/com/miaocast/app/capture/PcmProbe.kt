package com.miaocast.app.capture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 对捕获到的 PCM（s16le 立体声交错）做可量化探针：RMS/峰值 + Goertzel 目标频点能量。
 * 用于受控音源 A/B 过滤验收；不依赖扬声器是否可听。
 */
object PcmProbe {
    const val SAMPLE_RATE = 48_000
    /** 受控音源 A 目标频率 */
    const val FREQ_A_HZ = 1000.0
    /** 受控音源 B 目标频率 */
    const val FREQ_B_HZ = 2000.0

    data class Snapshot(
        val frames: Int,
        val rms: Double,
        val peak: Int,
        val energyA: Double,
        val energyB: Double,
        val dominant: Dominant,
        val verdictZh: String,
    )

    enum class Dominant { A, B, SILENCE, MIXED_OR_OTHER }

    fun analyzeInterleavedS16LeStereo(
        pcm: ByteArray,
        length: Int = pcm.size,
        sampleRate: Int = SAMPLE_RATE,
    ): Snapshot {
        val usable = length - (length % 4)
        var sumSq = 0.0
        var peak = 0
        var frames = 0
        // 取左声道做频谱估计
        val mono = ShortArray(usable / 4)
        var mi = 0
        var i = 0
        while (i + 3 < usable) {
            val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val sample = s.toShort().toInt()
            val a = kotlin.math.abs(sample)
            if (a > peak) peak = a
            sumSq += sample.toDouble() * sample.toDouble()
            mono[mi++] = sample.toShort()
            frames++
            i += 4 // 跳过右声道
        }
        val rms = if (frames == 0) 0.0 else sqrt(sumSq / frames)
        val energyA = goertzelPower(mono, frames, sampleRate, FREQ_A_HZ)
        val energyB = goertzelPower(mono, frames, sampleRate, FREQ_B_HZ)
        val silenceThreshold = 80.0 // 数字域近似静音（远低于满幅正弦）
        val ratio = if (energyB < 1e-9) Double.POSITIVE_INFINITY else energyA / energyB
        val dominant = when {
            rms < silenceThreshold && peak < 200 -> Dominant.SILENCE
            energyA > energyB * 4.0 && energyA > 1e6 -> Dominant.A
            energyB > energyA * 4.0 && energyB > 1e6 -> Dominant.B
            else -> Dominant.MIXED_OR_OTHER
        }
        val verdictZh = when (dominant) {
            Dominant.SILENCE ->
                "捕获近似静音（RMS=${"%.1f".format(rms)}）。若媒体音量为0导致全静音，不能据此证明 UID 过滤；需在数字域有能量时比较 A/B。"
            Dominant.A ->
                "主能量接近 ${FREQ_A_HZ.toInt()}Hz（A）。energyA/energyB=${"%.2f".format(ratio)}"
            Dominant.B ->
                "主能量接近 ${FREQ_B_HZ.toInt()}Hz（B）。energyB/energyA=${"%.2f".format(1.0 / ratio)}"
            Dominant.MIXED_OR_OTHER ->
                "未形成明确 A/B 主导（可能串音、未播放或禁止捕获）。RMS=${"%.1f".format(rms)}"
        }
        return Snapshot(frames, rms, peak, energyA, energyB, dominant, verdictZh)
    }

    /** Goertzel 功率（未归一化），对固定长度块可比。 */
    fun goertzelPower(samples: ShortArray, n: Int, sampleRate: Int, freqHz: Double): Double {
        if (n <= 0) return 0.0
        val k = (0.5 + n * freqHz / sampleRate).toInt()
        val w = 2.0 * PI * k / n
        val cosine = cos(w)
        val coeff = 2.0 * cosine
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            s0 = samples[i].toDouble() + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val real = s1 - s2 * cosine
        val imag = s2 * sin(w)
        return real * real + imag * imag
    }

    fun synthesizeToneS16LeStereo(
        freqHz: Double,
        frames: Int,
        sampleRate: Int = SAMPLE_RATE,
        amplitude: Double = 0.4,
    ): ByteArray {
        val out = ByteArray(frames * 4)
        var o = 0
        for (n in 0 until frames) {
            val v = (sin(2.0 * PI * freqHz * n / sampleRate) * amplitude * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val lo = v and 0xff
            val hi = (v shr 8) and 0xff
            out[o++] = lo.toByte(); out[o++] = hi.toByte()
            out[o++] = lo.toByte(); out[o++] = hi.toByte()
        }
        return out
    }
}
