package com.miplay.testtone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * 前台播放固定频率立体声正弦（USAGE_MEDIA），供 AudioPlaybackCapture 验收。
 * AudioTrack 数字增益固定，不随 UI「听感」判断；禁止捕获变体由 Manifest allowAudioPlaybackCapture=false 控制。
 */
class ToneService : Service() {
    companion object {
        const val TAG = "MiPlayTestTone"
        const val ACTION_START = "com.miplay.testtone.START"
        const val ACTION_STOP = "com.miplay.testtone.STOP"
        private const val CHANNEL = "tone"
        private const val NOTIF = 42
        private const val SR = 48_000
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var playing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTone(); stopSelf(); return START_NOT_STICKY
            }
            else -> startTone()
        }
        return START_STICKY
    }

    private fun startTone() {
        if (playing) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "受控测试音", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ToneActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("播放 ${BuildConfig.TONE_HZ}Hz · UID=${Process.myUid()} · 捕获允许=${BuildConfig.ALLOW_CAPTURE}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SR)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufFrames = maxOf(min / 4, 2048)
        val at = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(bufFrames * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = at
        playing = true
        at.play()
        val hz = BuildConfig.TONE_HZ
        Log.i(TAG, "start tone hz=$hz uid=${Process.myUid()} allowCapture=${BuildConfig.ALLOW_CAPTURE}")
        thread = Thread({
            val chunkFrames = 1024
            val pcm = ShortArray(chunkFrames * 2)
            var nSample = 0L
            val amp = (0.45 * Short.MAX_VALUE).toInt()
            while (playing) {
                for (i in 0 until chunkFrames) {
                    val v = (sin(2.0 * PI * hz * nSample / SR) * amp).toInt().toShort()
                    pcm[i * 2] = v
                    pcm[i * 2 + 1] = v
                    nSample++
                }
                var off = 0
                while (off < pcm.size && playing) {
                    val w = at.write(pcm, off, pcm.size - off)
                    if (w <= 0) break
                    off += w
                }
            }
        }, "tone-gen").also { it.start() }
    }

    private fun stopTone() {
        playing = false
        thread?.join(500)
        thread = null
        runCatching { track?.stop() }
        track?.release()
        track = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "stopped")
    }

    override fun onDestroy() {
        stopTone()
        super.onDestroy()
    }
}
