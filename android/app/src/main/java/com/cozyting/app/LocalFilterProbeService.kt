package com.cozyting.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.cozyting.app.capture.AndroidPackageIdentityLookup
import com.cozyting.app.capture.CaptureFailReason
import com.cozyting.app.capture.CaptureTargetResolver
import com.cozyting.app.capture.CaptureTargetResult
import com.cozyting.app.capture.LocalFilterAcceptanceGate
import com.cozyting.app.capture.PcmProbe
import com.cozyting.app.capture.PlaybackCaptureFactory
import com.cozyting.app.capture.PrefsTargetAppStore
import com.cozyting.app.capture.ProjectionSessionPolicy
import com.cozyting.app.capture.TargetAppSelection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅 acceptance 变体：本地音频过滤验收。
 * 路径：系统 MediaProjection 授权 → mediaProjection FGS → UID 过滤 AudioPlaybackCapture → PCM 探针。
 * 不创建 MiPlay 会话，不发现/控制/推流音箱，不本地监听，不录画面/麦克风，不落盘音频。
 */
class LocalFilterProbeService : Service() {
    companion object {
        const val TAG = "MiPlayLocalProbe"
        const val ACTION_START = "com.cozyting.app.acceptance.LOCAL_PROBE_START"
        const val ACTION_STOP = "com.cozyting.app.acceptance.LOCAL_PROBE_STOP"
        const val EXTRA_PROJ_CODE = "proj_code"
        const val EXTRA_PROJ_DATA = "proj_data"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_TARGET_CERT = "target_cert"
        const val EXTRA_GRANT_SESSION = "grant_session"
        private const val CHANNEL = "local_probe"
        private const val NOTIF_ID = 7

        @Volatile var running = AtomicBoolean(false)
        @Volatile var lastEvent: String = "空闲"
        @Volatile var lastProbe: String = ""
        @Volatile var lastFail: String = ""
        @Volatile var targetPackage: String = ""

        /** 测试/诊断：本服务是否调用过 NativeBridge（必须始终为 false） */
        @Volatile var miplayConnectInvoked: Boolean = false
        @Volatile var miplayCreateInvoked: Boolean = false
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private var activeGrant: String? = null
    private val policy = ProjectionSessionPolicy()
    private var captureUid = -1

    override fun onBind(intent: Intent?): IBinder? = null

    private fun event(s: String) {
        lastEvent = s.take(120)
        Log.i(TAG, s)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProbe()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 断言执行计划：本地探针不得连 MiPlay
        val plan = LocalFilterAcceptanceGate.executionPlan(LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE)
        require(!plan.callMiPlayConnect && !plan.callMiPlayCreateSession && plan.runPcmProbeOnly)

        startForeground(
            NOTIF_ID,
            buildNotification("正在准备本地过滤探针…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        if (running.getAndSet(true)) {
            event("探针已在运行")
            return START_NOT_STICKY
        }
        lastFail = ""
        lastProbe = ""
        miplayConnectInvoked = false
        miplayCreateInvoked = false

        val pkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        val cert = intent?.getStringExtra(EXTRA_TARGET_CERT).orEmpty()
        val gate = LocalFilterAcceptanceGate.validateStart(
            mode = LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE,
            targetPackageSelected = pkg.isNotBlank() || PrefsTargetAppStore(this).loadSelection() != null,
            speakerCount = 0,
        )
        if (gate is LocalFilterAcceptanceGate.StartDecision.Block) {
            lastFail = gate.messageZh
            event(gate.messageZh)
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val resolved = resolveTarget(pkg, cert)
        if (resolved == null) {
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        targetPackage = resolved.packageName
        captureUid = resolved.uid
        LocalFilterAcceptanceGate.forbidUidWideningOrSystemFallback(
            selectedUids = listOf(captureUid),
            attemptedUids = listOf(captureUid),
        )

        val code = intent!!.getIntExtra(EXTRA_PROJ_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PROJ_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJ_DATA)
        }
        val grantSession = intent.getStringExtra(EXTRA_GRANT_SESSION) ?: UUID.randomUUID().toString()
        val decision = policy.beginAfterUserGrant(grantSession, code)
        if (decision !is ProjectionSessionPolicy.Decision.ProceedToFgsThenGetProjection ||
            code != Activity.RESULT_OK || data == null
        ) {
            lastFail = "MediaProjection 授权无效或被拒绝（每次须重新授权，不可复用）"
            event(lastFail)
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        activeGrant = grantSession
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (projection == null) {
            lastFail = "getMediaProjection 失败"
            event(lastFail)
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                lastFail = "投影授权已撤销，已清理探针"
                event(lastFail)
                stopProbe()
                stopSelf()
            }
        }, main)

        if (!startCapture(captureUid)) {
            stopProbe()
            stopSelf()
            return START_NOT_STICKY
        }
        // 明确：此处绝不调用 NativeBridge.create / connect / discover
        event("本地 PCM 探针运行中（uid=$captureUid, pkg=$targetPackage）。未连接音箱。")
        updateNotification()
        return START_NOT_STICKY
    }

    private fun resolveTarget(pkgExtra: String, certExtra: String): CaptureTargetResult.Ok? {
        val store = PrefsTargetAppStore(this)
        val saved = store.loadSelection()
        val pkg = pkgExtra.ifBlank { saved?.packageName }
        val expect = certExtra.ifBlank { saved?.signingCertSha256 }
        return when (val r = CaptureTargetResolver(AndroidPackageIdentityLookup(this)).resolveForCapture(pkg, expect)) {
            is CaptureTargetResult.Ok -> {
                store.saveSelection(
                    TargetAppSelection(
                        packageName = r.packageName,
                        label = saved?.label ?: r.packageName,
                        signingCertSha256 = r.signingCertSha256,
                    ),
                )
                r
            }
            is CaptureTargetResult.Fail -> {
                lastFail = r.messageZh
                event("目标失败[${r.reason}]: ${r.messageZh}")
                if (r.reason == CaptureFailReason.NOT_FOUND || r.reason == CaptureFailReason.IDENTITY_CHANGED) {
                    store.clearSelection()
                }
                null
            }
        }
    }

    private fun startCapture(uid: Int): Boolean {
        val proj = projection ?: return false.also { lastFail = "无 MediaProjection" }
        val config = try {
            PlaybackCaptureFactory.buildAppCapture(proj, uid)
        } catch (e: Exception) {
            lastFail = "UID 过滤构建失败: ${e.message}"
            return false
        }
        LocalFilterAcceptanceGate.forbidUidWideningOrSystemFallback(listOf(uid), listOf(uid))
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val chunk = 1024 * 4
        val minBuf = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = try {
            AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(maxOf(chunk * 16, minBuf)).build()
        } catch (e: Exception) {
            lastFail = "AudioRecord 失败: ${e.message}"
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            lastFail = "AudioRecord 未初始化"
            rec.release()
            return false
        }
        record = rec
        rec.startRecording()
        captureThread = Thread({
            val buf = ByteArray(chunk)
            var blocks = 0L
            while (running.get()) {
                var off = 0
                while (off < chunk && running.get()) {
                    val n = rec.read(buf, off, chunk - off)
                    if (n <= 0) {
                        event("read=$n，停止探针")
                        return@Thread
                    }
                    off += n
                }
                blocks++
                if (blocks % 12 == 0L) {
                    val snap = PcmProbe.analyzeInterleavedS16LeStereo(buf, chunk)
                    val silenceNote = if (snap.dominant == PcmProbe.Dominant.SILENCE) {
                        " | " + LocalFilterAcceptanceGate.silenceCannotProveFilterZh()
                    } else {
                        ""
                    }
                    lastProbe =
                        "RMS=${"%.0f".format(snap.rms)} peak=${snap.peak} " +
                            "A=${"%.2e".format(snap.energyA)} B=${"%.2e".format(snap.energyB)} " +
                            "⇒ ${snap.dominant} | ${snap.verdictZh}$silenceNote"
                    Log.i(TAG, "PCM_PROBE $lastProbe")
                    main.post { updateNotification() }
                }
            }
        }, "local-pcm-probe").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private fun stopProbe() {
        if (!running.getAndSet(false)) {
            // 仍尝试释放，防止半初始化
        }
        event("停止本地探针并清理")
        captureThread?.interrupt()
        captureThread = null
        runCatching { record?.stop() }
        record?.release(); record = null
        runCatching { projection?.stop() }
        projection = null
        activeGrant?.let { policy.hasReusableGrant(it) }
        activeGrant = null
        captureUid = -1
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    override fun onDestroy() {
        stopProbe()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "本地过滤验收", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocalFilterProbeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("本地音频过滤验收（不连接音箱）")
            .setContentText(text.take(80))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        if (!running.get()) return
        val body = (lastProbe.ifBlank { lastEvent }).ifBlank { "捕获中…" }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(body))
    }
}
