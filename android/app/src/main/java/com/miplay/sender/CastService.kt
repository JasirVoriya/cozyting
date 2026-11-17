package com.miplay.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：持有 Rust 投送会话（core 负责多台同步、暂停恢复的运行期 TIME_OFFSET、中途加入、15 s 心跳超时断线重连）。
 * - 静音测试源：Rust 内置 AAC 静音帧；
 * - 系统音频源：MediaProjection → AudioPlaybackCapture(48k/s16/立体声) → MediaCodec AAC → NativeBridge.pushAac。
 *
 * Intent 动作（App 和 adb 都可用）：START / STOP / PAUSE / RESUME / VOLUME(ip 可空=全组, volume) / ADD(ip) / REMOVE(ip)。
 */
class CastService : Service() {
    companion object {
        const val TAG = "MiPlaySender"
        const val ACTION_START = "com.miplay.sender.START"
        const val ACTION_STOP = "com.miplay.sender.STOP"
        const val ACTION_VOLUME = "com.miplay.sender.VOLUME"
        const val ACTION_PAUSE = "com.miplay.sender.PAUSE"
        const val ACTION_RESUME = "com.miplay.sender.RESUME"
        const val ACTION_ADD = "com.miplay.sender.ADD"
        const val ACTION_REMOVE = "com.miplay.sender.REMOVE"
        const val EXTRA_IPS = "ips"
        const val EXTRA_IP = "ip"
        const val EXTRA_SOURCE = "source" // "silent" | "system"
        const val EXTRA_DELAY = "delay_ms" // <=0 自动（按 Wi-Fi 频段）
        const val EXTRA_PREMUTE = "premute"
        const val EXTRA_REF = "ref_function" // 空 = 按台数自动
        const val EXTRA_AUTH = "auth" // 0=自动（明文失败回退 Safety）1=明文 2=Safety
        const val EXTRA_PROJ_CODE = "proj_code"
        const val EXTRA_PROJ_DATA = "proj_data"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_SECS = "secs"
        private const val CHANNEL = "cast"
        private const val NOTIF_ID = 1

        @Volatile var handle = 0L
        @Volatile var lastStats: String = ""
        @Volatile var lastEvent: String = "空闲"
        @Volatile var paused = false
        @Volatile var source = "silent"
        val running = AtomicBoolean(false)

        /** 当前 Wi-Fi 频率 → 播放延迟（与官方 isCurrentWifi5GHz ? 800 : 1000 一致）。 */
        fun autoDelayMs(ctx: Context): Int {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val f = runCatching { wm.connectionInfo.frequency }.getOrDefault(0)
            return NativeBridge.delayForFrequency(f)
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var encoder: AacEncoder? = null
    private var captureThread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var pushed = 0L
    @Volatile private var dropped = 0L
    private var projectionType = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun event(s: String) {
        // UI/通知只显示简短摘要；完整 JSON 只进 logcat
        lastEvent = s.substringBefore('{').trim().trimEnd('：', ':').take(80).ifEmpty { s.take(80) }
        Log.i(TAG, s)
    }

    private fun bg(name: String, f: () -> Unit) = Thread(f, name).start()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val h = handle
        when (intent?.action) {
            ACTION_STOP -> { stopCast(); stopSelf(); return START_NOT_STICKY }
            ACTION_VOLUME -> {
                val v = intent.getIntExtra(EXTRA_VOLUME, 0).coerceIn(0, 100)
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
                bg("volume") { event("setVolume(${ip.ifEmpty { "全组" }}, $v): " + NativeBridge.setVolume(h, ip, v)) }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { bg("pause") { paused = true; event("暂停: " + NativeBridge.pause(h)); updateNotification() }; return START_NOT_STICKY }
            ACTION_RESUME -> { bg("resume") { event("恢复: " + NativeBridge.resume(h)); paused = false; updateNotification() }; return START_NOT_STICKY }
            ACTION_ADD -> { val ip = intent.getStringExtra(EXTRA_IP).orEmpty(); bg("add") { event("加入 $ip: " + NativeBridge.addReceiver(h, ip)) }; return START_NOT_STICKY }
            ACTION_REMOVE -> { val ip = intent.getStringExtra(EXTRA_IP).orEmpty(); bg("remove") { NativeBridge.removeReceiver(h, ip); event("移除 $ip") }; return START_NOT_STICKY }
        }
        source = intent?.getStringExtra(EXTRA_SOURCE) ?: "silent"
        val ips = intent?.getStringExtra(EXTRA_IPS).orEmpty()
        projectionType = source == "system"
        startForegroundCompat()
        if (running.getAndSet(true)) { event("已在投送中，忽略重复开始"); return START_NOT_STICKY }
        paused = false
        acquireLocks()
        if (source == "system") {
            val code = intent!!.getIntExtra(EXTRA_PROJ_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_PROJ_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJ_DATA)
            if (code != Activity.RESULT_OK || data == null) { event("缺少 MediaProjection 授权"); running.set(false); stopSelf(); return START_NOT_STICKY }
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { event("MediaProjection 已停止"); stopCast(); stopSelf() }
            }, main)
        }
        val reqDelay = intent?.getIntExtra(EXTRA_DELAY, 0) ?: 0
        val delay = if (reqDelay > 0) reqDelay else autoDelayMs(this)
        val premute = intent?.getIntExtra(EXTRA_PREMUTE, 0) ?: 0
        val ref = intent?.getStringExtra(EXTRA_REF).orEmpty()
        val secs = intent?.getIntExtra(EXTRA_SECS, 0) ?: 0
        bg("cast-start") {
            event("连接音箱 $ips（source=$source, delay=${delay}ms${if (reqDelay <= 0) "(自动)" else ""}, 音量=$premute, ref=${ref.ifEmpty { "自动" }}）")
            handle = NativeBridge.create(source == "silent")
            if (source == "system") startCapture() // 先开始采集，Rust 预读 14 个 AU 时有数据
            // premute：推流前设音量；volumeAfterPlay：PLAY 后（含中途加入/重连）再设同一值
            NativeBridge.setAuthMode(intent?.getIntExtra(EXTRA_AUTH, 0) ?: 0)
            val st = NativeBridge.connect(handle, ips, delay, premute, premute, ref, Build.MODEL ?: "Android")
            event("connect 返回：$st")
            if (st.contains("\"error\"") && !st.contains("\"receivers\"")) { stopCast(); stopSelf(); return@bg }
            statsLoop()
            if (secs > 0) main.postDelayed({ event("定时 ${secs}s 到，停止"); stopCast(); stopSelf() }, secs * 1000L)
        }
        return START_NOT_STICKY
    }

    private var statsTick = 0

    private fun statsLoop() {
        main.post(object : Runnable {
            override fun run() {
                if (!running.get() || handle == 0L) return
                val h = handle
                bg("stats") {
                    lastStats = NativeBridge.stats(h)
                    Log.i(TAG, "STATS pushed=$pushed dropped=$dropped " + lastStats)
                    // 通知每 10 s 刷一次即可（原 2 s 一次会让 SystemUI/通知同步服务频繁处理）
                    if (++statsTick % 5 == 0) main.post { updateNotification() }
                }
                main.postDelayed(this, 2000)
            }
        })
    }

    /** 通知栏：台数、各台状态、重连次数；按钮 暂停/恢复 + 停止。 */
    private fun summary(): String = runCatching {
        val o = JSONObject(lastStats)
        val rs = o.getJSONArray("receivers")
        val parts = (0 until rs.length()).map { i ->
            val r = rs.getJSONObject(i)
            val st = when { !r.isNull("error") -> "断开"; r.optInt("state", -1) == 2 -> "播放"; r.optInt("state", -1) == 3 -> "暂停"; else -> r.optString("phase").take(8) }
            val rc = r.optInt("reconnects", 0)
            r.getString("ip").substringAfterLast('.') + ":" + st + (if (rc > 0) "(重连$rc)" else "")
        }
        "${rs.length()} 台 · 延迟 ${o.optLong("playback_delay_us") / 1000}ms · " + parts.joinToString(" ")
    }.getOrDefault(lastEvent)

    private fun buildNotification(): Notification {
        val pi = { action: String, code: Int -> PendingIntent.getService(this, code, Intent(this, CastService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE) }
        val open = PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (paused) "已暂停投送（${if (source == "system") "系统音频" else "静音测试"}）" else "正在投送到小米音箱（${if (source == "system") "系统音频" else "静音测试"}）")
            .setContentText(summary())
            .setSmallIcon(if (paused) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (paused) "恢复" else "暂停", pi(if (paused) ACTION_RESUME else ACTION_PAUSE, 2)).build())
            .addAction(Notification.Action.Builder(null, "停止", pi(ACTION_STOP, 1)).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true).build()
    }

    private fun updateNotification() {
        if (!running.get()) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun startCapture() {
        val proj = projection ?: return
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val chunk = 1024 * 4
        val minBuf = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(maxOf(chunk * 16, minBuf)).build()
        record = rec
        val enc = AacEncoder { au ->
            val h = handle
            if (h != 0L) { if (NativeBridge.pushAac(h, au) == 0) pushed++ else dropped++ }
        }
        encoder = enc
        rec.startRecording()
        event("AudioPlaybackCapture 已开始（state=${rec.recordingState}）")
        captureThread = Thread({
            val buf = ByteArray(chunk)
            var peak = 0
            var blocks = 0L
            while (running.get()) {
                var off = 0
                while (off < chunk && running.get()) {
                    val n = rec.read(buf, off, chunk - off)
                    if (n <= 0) { event("AudioRecord.read 返回 $n"); return@Thread }
                    off += n
                }
                // 暂停期间丢弃采集到的 PCM（官方 Resume 前也丢掉暂停前的 PCM）
                if (paused) continue
                var i = 0
                while (i < chunk) { val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8); val a = kotlin.math.abs(s.toShort().toInt()); if (a > peak) peak = a; i += 2 }
                blocks++
                if (blocks % 470 == 0L) { Log.i(TAG, "capture blocks=$blocks peak=$peak aacProduced=${enc.produced}"); peak = 0 }
                enc.encode(buf, chunk)
            }
        }, "capture").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun stopCast() {
        if (!running.getAndSet(false)) return
        event("停止投送")
        captureThread?.interrupt()
        runCatching { record?.stop() }; record?.release(); record = null
        encoder?.release(); encoder = null
        val h = handle
        handle = 0L
        if (h != 0L) Thread { lastStats = NativeBridge.stats(h); Log.i(TAG, "FINAL $lastStats"); NativeBridge.stop(h) }.start()
        projection?.stop(); projection = null
        releaseLocks()
    }

    override fun onDestroy() {
        stopCast()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "miplay").apply { setReferenceCounted(false); acquire() }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miplay:cast").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        Log.i(TAG, "locks acquired; battery-optimization exempt=$exempt" + if (exempt) "" else " (HyperOS Doze 可能冻结本进程)")
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "妙播投送", NotificationManager.IMPORTANCE_LOW))
        val type = if (projectionType) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        startForeground(NOTIF_ID, buildNotification(), type)
    }
}
