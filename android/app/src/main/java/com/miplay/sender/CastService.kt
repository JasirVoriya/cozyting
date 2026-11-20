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
import android.media.AudioFormat
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
import com.miplay.sender.cast.AndroidStreamMusicVolume
import com.miplay.sender.compat.AcceptanceAudioDefaults
import com.miplay.sender.compat.CaptureSelectionLayers
import com.miplay.sender.cast.LocalOutputMute
import com.miplay.sender.capture.AndroidPackageIdentityLookup
import com.miplay.sender.capture.CaptureFailReason
import com.miplay.sender.capture.CaptureTargetResolver
import com.miplay.sender.capture.CaptureTargetResult
import com.miplay.sender.capture.PcmProbe
import com.miplay.sender.capture.PlaybackCaptureFactory
import com.miplay.sender.capture.PrefsTargetAppStore
import com.miplay.sender.capture.ProjectionSessionPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：持有 Rust 投送会话（多台同步、暂停恢复、中途加入、心跳断线重连）。
 * - 静音测试源：Rust 内置 AAC 静音帧；
 * - 选定 App：MediaProjection → AudioPlaybackCapture(UID+usage) → AAC → NativeBridge.pushAac。
 *
 * Android 14+：用户授权 → mediaProjection FGS → getMediaProjection；不采集麦克风。
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
        const val EXTRA_SOURCE = "source" // "silent" | "app"
        const val EXTRA_DELAY = "delay_ms"
        const val EXTRA_PREMUTE = "premute"
        const val EXTRA_REF = "ref_function"
        const val EXTRA_AUTH = "auth"
        const val EXTRA_PROJ_CODE = "proj_code"
        const val EXTRA_PROJ_DATA = "proj_data"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_SECS = "secs"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_TARGET_CERT = "target_cert"
        const val EXTRA_GRANT_SESSION = "grant_session"
        const val EXTRA_LOCAL_MUTE_POLICY = "local_mute_policy"
        private const val CHANNEL = "cast"
        private const val NOTIF_ID = 1
        private const val MAX_RECENT_EVENTS = 80
        private const val MAX_OPS_LOG_BYTES = 64 * 1024L
        private val eventsLock = Any()
        private val recentEvents = ArrayDeque<String>()

        @Volatile var handle = 0L
        @Volatile var lastStats: String = ""
        @Volatile var lastEvent: String = "空闲"
        @Volatile var paused = false
        @Volatile var source = "silent"
        @Volatile var targetPackage: String = ""
        @Volatile var lastFailMessage: String = ""
        /** 最近一次捕获 PCM 探针摘要（验收变体用于 A/B 量化；静音时不可证明过滤） */
        @Volatile var lastPcmProbe: String = ""
        /** 本机 STREAM_MUSIC 静音状态文案（估计/回退说明，非听感保证）。 */
        @Volatile var localMuteStatusZh: String = "本机出声（未尝试静音）"
        @Volatile var localMuteStatus: LocalOutputMute.Status = LocalOutputMute.Status.IDLE
        val running = AtomicBoolean(false)

        fun autoDelayMs(ctx: Context): Int {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val f = runCatching { wm.connectionInfo.frequency }.getOrDefault(0)
            return NativeBridge.delayForFrequency(f)
        }

        fun parseIps(raw: String): List<String> =
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun mergeConnectingReceivers(statsJson: String, connectingIps: Set<String>): String {
            val clean = connectingIps.map { it.trim() }.filter { it.isNotEmpty() }
            if (clean.isEmpty()) return statsJson
            val root = runCatching {
                if (statsJson.isBlank()) JSONObject() else JSONObject(statsJson)
            }.getOrElse { JSONObject() }
            val rs = root.optJSONArray("receivers") ?: JSONArray().also { root.put("receivers", it) }
            val seen = HashSet<String>()
            for (i in 0 until rs.length()) {
                rs.optJSONObject(i)?.optString("ip")?.takeIf { it.isNotBlank() }?.let(seen::add)
            }
            for (ip in clean) {
                if (seen.add(ip)) {
                    rs.put(
                        JSONObject()
                            .put("ip", ip)
                            .put("phase", "connecting")
                            .put("latency_e2e_est_ms", JSONObject.NULL)
                            .put("error", JSONObject.NULL),
                    )
                }
            }
            return root.toString()
        }

        fun removeReceiverFromStatsJson(statsJson: String, ip: String): String {
            val target = ip.trim()
            if (statsJson.isBlank() || target.isEmpty()) return statsJson
            val root = runCatching { JSONObject(statsJson) }.getOrNull() ?: return statsJson
            val rs = root.optJSONArray("receivers") ?: return statsJson
            val filtered = JSONArray()
            for (i in 0 until rs.length()) {
                val item = rs.optJSONObject(i) ?: continue
                if (item.optString("ip") != target) {
                    filtered.put(item)
                }
            }
            root.put("receivers", filtered)
            return root.toString()
        }

        fun primeConnectingReceivers(ips: Collection<String>) {
            val clean = ips.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (clean.isEmpty()) return
            lastStats = mergeConnectingReceivers(lastStats, clean)
        }

        fun recordOperationEvent(line: String, opsFile: File? = null): String {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val entry = "$ts $line"
            synchronized(eventsLock) {
                while (recentEvents.size >= MAX_RECENT_EVENTS) {
                    recentEvents.removeFirst()
                }
                recentEvents.addLast(entry)
                if (opsFile != null) {
                    runCatching {
                        if (opsFile.exists() && opsFile.length() >= MAX_OPS_LOG_BYTES) {
                            val rotated = File(opsFile.parentFile, "${opsFile.name}.1")
                            opsFile.copyTo(rotated, overwrite = true)
                            opsFile.writeText("")
                        }
                        opsFile.appendText("$entry\n")
                    }
                }
            }
            return entry
        }

        fun recentEventsSnapshot(): List<String> = synchronized(eventsLock) { recentEvents.toList() }

        fun clearRecentEventsForTest() = synchronized(eventsLock) { recentEvents.clear() }
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
    private var activeGrantSession: String? = null
    private var captureUid: Int = -1
    private val sessionPolicy = ProjectionSessionPolicy()
    private var noAudioHintPosted = false
    private val routeLock = Any()
    private val desiredReceivers = LinkedHashSet<String>()
    private val activeOrSubmittedReceivers = LinkedHashSet<String>()
    private val pendingReconcileIps = LinkedHashSet<String>()
    private val pendingSpeakerVolumes = LinkedHashMap<String, Int>()
    private var pendingGroupVolume: Int? = null
    private var connectFinishedForHandle: Long = 0L
    private var inFlightAddIp: String? = null
    private var reconcileRunning = false
    /**
     * 禁止在属性初始化器里 `AndroidStreamMusicVolume(this)`：Service 构造时 base context
     * 尚未 attach，getApplicationContext() 会 NPE（真机 0.9.1 已复现）。
     * 仅在 [onCreate] 之后赋值。
     */
    private lateinit var localMute: LocalOutputMute

    override fun onCreate() {
        super.onCreate()
        localMute = LocalOutputMute(
            AndroidStreamMusicVolume.fromAttachedContext(applicationContext),
            restoreVolumeIndex = AcceptanceAudioDefaults.restoreLocalVolumeIndex(BuildConfig.IS_ACCEPTANCE),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publishMute(snap: LocalOutputMute.Snapshot) {
        localMuteStatus = snap.status
        localMuteStatusZh = snap.messageZh
        Log.i(
            TAG,
            "LOCAL_MUTE status=${snap.status} isStreamMute=${snap.streamMuted} " +
                "probeLocked=${snap.probeLocked} policy=${snap.policy} api=${snap.api} msg=${snap.messageZh}",
        )
    }

    private fun event(s: String) {
        lastEvent = s.substringBefore('{').trim().trimEnd('：', ':').take(80).ifEmpty { s.take(80) }
        val opsFile = runCatching { applicationContext?.filesDir?.let { File(it, "cast_ops.log") } }.getOrNull()
        val stamped = recordOperationEvent(s, opsFile)
        Log.i(TAG, "OP_EVENT $stamped")
    }

    private fun bg(name: String, f: () -> Unit) = Thread(f, name).start()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val h = handle
        when (intent?.action) {
            ACTION_STOP -> { stopCast(); stopSelf(); return START_NOT_STICKY }
            ACTION_VOLUME -> {
                val v = intent.getIntExtra(EXTRA_VOLUME, 0).coerceIn(0, 100)
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty().trim()
                val readyHandle: Long
                val deferVolume: Boolean
                synchronized(routeLock) {
                    readyHandle = connectFinishedForHandle
                    deferVolume = readyHandle == 0L || (ip.isNotEmpty() && pendingReconcileIps.contains(ip))
                    if (deferVolume) {
                        if (ip.isEmpty()) pendingGroupVolume = v else pendingSpeakerVolumes[ip] = v
                    }
                }
                if (deferVolume) {
                    event("排队音量(${ip.ifEmpty { "全组" }}, $v)")
                } else if (readyHandle != 0L) {
                    bg("volume") {
                        event("setVolume(${ip.ifEmpty { "全组" }}, $v): " + NativeBridge.setVolume(readyHandle, ip, v))
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { bg("pause") { paused = true; event("暂停: " + NativeBridge.pause(h)); updateNotification() }; return START_NOT_STICKY }
            ACTION_RESUME -> { bg("resume") { event("恢复: " + NativeBridge.resume(h)); paused = false; updateNotification() }; return START_NOT_STICKY }
            ACTION_ADD -> {
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty().trim()
                if (ip.isNotEmpty() && running.get()) {
                    val readyHandle: Long
                    synchronized(routeLock) {
                        desiredReceivers.add(ip)
                        pendingReconcileIps.add(ip)
                        lastStats = mergeConnectingReceivers(lastStats, pendingReconcileIps.toSet())
                        readyHandle = connectFinishedForHandle
                    }
                    if (readyHandle == 0L) {
                        event("排队加入 $ip（等待首台音箱连接完成）")
                    } else {
                        event("准备加入 $ip")
                        triggerReconcile(readyHandle)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_REMOVE -> {
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty().trim()
                if (ip.isNotEmpty()) {
                    val hNow = handle
                    val cancelInFlight: Boolean
                    val readyHandle: Long
                    synchronized(routeLock) {
                        desiredReceivers.remove(ip)
                        pendingReconcileIps.remove(ip)
                        pendingSpeakerVolumes.remove(ip)
                        lastStats = removeReceiverFromStatsJson(lastStats, ip)
                        cancelInFlight = (inFlightAddIp == ip)
                        readyHandle = connectFinishedForHandle
                    }
                    if (cancelInFlight && hNow != 0L) {
                        bg("cancel-add") {
                            val stillRemoved = synchronized(routeLock) { !desiredReceivers.contains(ip) }
                            if (stillRemoved) NativeBridge.removeReceiver(hNow, ip)
                        }
                    }
                    if (readyHandle != 0L) {
                        triggerReconcile(readyHandle)
                    }
                    event("移除 $ip")
                }
                return START_NOT_STICKY
            }
        }

        source = intent?.getStringExtra(EXTRA_SOURCE) ?: "silent"
        val ips = intent?.getStringExtra(EXTRA_IPS).orEmpty()
        val initialIps = parseIps(ips)
        projectionType = source == "app"

        // Android 14+：先进入对应类型的 FGS，再 getMediaProjection
        startForegroundCompat()

        if (running.getAndSet(true)) {
            if (initialIps.isNotEmpty()) {
                val readyHandle: Long
                synchronized(routeLock) {
                    initialIps.forEach { ip ->
                        if (desiredReceivers.add(ip)) {
                            pendingReconcileIps.add(ip)
                        }
                    }
                    lastStats = mergeConnectingReceivers(lastStats, pendingReconcileIps.toSet())
                    readyHandle = connectFinishedForHandle
                }
                event("已在投送中，合并目标音箱: ${initialIps.joinToString(",")}")
                if (readyHandle != 0L) {
                    triggerReconcile(readyHandle)
                }
            } else {
                event("已在投送中，忽略重复开始")
            }
            return START_NOT_STICKY
        }
        paused = false
        lastFailMessage = ""
        noAudioHintPosted = false
        synchronized(routeLock) {
            desiredReceivers.clear()
            desiredReceivers.addAll(initialIps)
            activeOrSubmittedReceivers.clear()
            activeOrSubmittedReceivers.addAll(initialIps)
            pendingReconcileIps.clear()
            pendingReconcileIps.addAll(initialIps)
            pendingSpeakerVolumes.clear()
            pendingGroupVolume = null
            connectFinishedForHandle = 0L
            inFlightAddIp = null
            reconcileRunning = false
            lastStats = mergeConnectingReceivers("", pendingReconcileIps.toSet())
        }
        acquireLocks()

        if (source == "app") {
            val pkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
            val cert = intent?.getStringExtra(EXTRA_TARGET_CERT).orEmpty()
            val resolved = resolveTargetOrFail(pkg, cert)
            if (resolved == null) {
                running.set(false)
                releaseLocks()
                stopSelf()
                return START_NOT_STICKY
            }
            targetPackage = resolved.packageName
            captureUid = resolved.uid
            event(CaptureSelectionLayers.statusLine(targetPackage, captureUid))

            val code = intent!!.getIntExtra(EXTRA_PROJ_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_PROJ_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJ_DATA)
            }
            val grantSession = intent.getStringExtra(EXTRA_GRANT_SESSION) ?: UUID.randomUUID().toString()
            val decision = sessionPolicy.beginAfterUserGrant(grantSession, code)
            if (decision !is ProjectionSessionPolicy.Decision.ProceedToFgsThenGetProjection ||
                code != Activity.RESULT_OK || data == null
            ) {
                event("缺少或无效的 MediaProjection 授权（每次会话需重新授权）")
                lastFailMessage = "录屏/音频捕获授权无效或被拒绝"
                running.set(false)
                releaseLocks()
                stopSelf()
                return START_NOT_STICKY
            }
            activeGrantSession = grantSession
            // FGS 已启动 → 现在才 getMediaProjection
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            if (projection == null) {
                event("getMediaProjection 返回 null")
                lastFailMessage = "系统未能创建 MediaProjection"
                running.set(false)
                releaseLocks()
                stopSelf()
                return START_NOT_STICKY
            }
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    event("MediaProjection 已停止/撤销，正在清理")
                    lastFailMessage = "捕获授权已撤销或被系统终止"
                    stopCast()
                    stopSelf()
                }
            }, main)
        }

        val reqDelay = intent?.getIntExtra(EXTRA_DELAY, 0) ?: 0
        val delay = if (reqDelay > 0) reqDelay else autoDelayMs(this)
        val premute = intent?.getIntExtra(EXTRA_PREMUTE, 0) ?: 0
        val ref = intent?.getStringExtra(EXTRA_REF).orEmpty()
        val secs = intent?.getIntExtra(EXTRA_SECS, 0) ?: 0
        val sessionHandle = NativeBridge.create(source == "silent")
        handle = sessionHandle
        bg("cast-start") {
            val srcLabel = if (source == "app") "app=$targetPackage uid=$captureUid" else "silent"
            event("连接音箱 $ips（source=$srcLabel, delay=${delay}ms${if (reqDelay <= 0) "(自动)" else ""}, 音量=$premute）")
            if (source == "app") {
                if (!startCapture(captureUid)) {
                    if (handle == sessionHandle) {
                        stopCast()
                        stopSelf()
                    }
                    return@bg
                }
            }
            val authReq = intent?.getIntExtra(EXTRA_AUTH, 0) ?: 0
            event("auth_mode_requested=$authReq (${AuthModeResolve.label(authReq)})")
            NativeBridge.setAuthMode(authReq)
            val st = NativeBridge.connect(sessionHandle, ips, delay, premute, premute, ref, Build.MODEL ?: "Android")
            if (!running.get() || handle != sessionHandle) {
                return@bg
            }
            event("connect 返回：$st")
            if (st.contains("\"error\"") && !st.contains("\"receivers\"")) {
                stopCast(); stopSelf(); return@bg
            }
            synchronized(routeLock) {
                initialIps.forEach { pendingReconcileIps.remove(it) }
                connectFinishedForHandle = sessionHandle
                lastStats = mergeConnectingReceivers(st, pendingReconcileIps.toSet())
            }
            if (source == "app") {
                val policyArg = intent?.getStringExtra(EXTRA_LOCAL_MUTE_POLICY).orEmpty()
                val policy = if (policyArg == "keep_local") {
                    LocalOutputMute.Policy.KEEP_LOCAL_OUTPUT
                } else {
                    LocalOutputMute.Policy.GLOBAL_STREAM_MUTE
                }
                publishMute(localMute.beginMuteForCast(policy))
                event(localMuteStatusZh)
            } else {
                publishMute(localMute.skip("silent source"))
            }
            statsLoop()
            triggerReconcile(sessionHandle)
            if (secs > 0) main.postDelayed({ event("定时 ${secs}s 到，停止"); stopCast(); stopSelf() }, secs * 1000L)
        }
        return START_NOT_STICKY
    }

    private fun triggerReconcile(h: Long) {
        synchronized(routeLock) {
            if (reconcileRunning || connectFinishedForHandle != h || !running.get() || handle != h) return
            reconcileRunning = true
        }
        bg("reconcile-receivers") {
            try {
                while (running.get() && handle == h) {
                    var toRemove: String? = null
                    var toAdd: String? = null
                    var groupVol: Int? = null
                    var speakerVol: Pair<String, Int>? = null
                    synchronized(routeLock) {
                        if (!running.get() || handle != h || connectFinishedForHandle != h) {
                            reconcileRunning = false
                            return@bg
                        }
                        toRemove = activeOrSubmittedReceivers.firstOrNull { !desiredReceivers.contains(it) }
                        if (toRemove != null) {
                            activeOrSubmittedReceivers.remove(toRemove)
                            pendingReconcileIps.remove(toRemove)
                        } else {
                            toAdd = desiredReceivers.firstOrNull { !activeOrSubmittedReceivers.contains(it) }
                            if (toAdd != null) {
                                activeOrSubmittedReceivers.add(toAdd!!)
                                pendingReconcileIps.add(toAdd!!)
                                inFlightAddIp = toAdd
                                lastStats = mergeConnectingReceivers(lastStats, pendingReconcileIps.toSet())
                            } else if (pendingGroupVolume != null) {
                                groupVol = pendingGroupVolume
                                pendingGroupVolume = null
                            } else {
                                val readyVolKey = pendingSpeakerVolumes.keys.firstOrNull { !pendingReconcileIps.contains(it) }
                                if (readyVolKey != null) {
                                    speakerVol = readyVolKey to pendingSpeakerVolumes.remove(readyVolKey)!!
                                } else {
                                    reconcileRunning = false
                                    return@bg
                                }
                            }
                        }
                    }
                    when {
                        toRemove != null -> {
                            val ip = toRemove!!
                            NativeBridge.removeReceiver(h, ip)
                            synchronized(routeLock) {
                                lastStats = removeReceiverFromStatsJson(lastStats, ip)
                            }
                            event("已从会话移除 $ip")
                        }
                        toAdd != null -> {
                            val ip = toAdd!!
                            event("开始加入音箱 $ip")
                            val res = NativeBridge.addReceiver(h, ip)
                            var removedDuringAdd = false
                            var retryImmediately = false
                            synchronized(routeLock) {
                                if (inFlightAddIp == ip) {
                                    inFlightAddIp = null
                                }
                                if (!desiredReceivers.contains(ip)) {
                                    activeOrSubmittedReceivers.remove(ip)
                                    pendingReconcileIps.remove(ip)
                                    removedDuringAdd = true
                                } else if (res.contains("receiver removed during connect")) {
                                    activeOrSubmittedReceivers.remove(ip)
                                    pendingReconcileIps.add(ip)
                                    retryImmediately = true
                                } else {
                                    pendingReconcileIps.remove(ip)
                                }
                            }
                            if (removedDuringAdd) {
                                NativeBridge.removeReceiver(h, ip)
                                synchronized(routeLock) {
                                    lastStats = removeReceiverFromStatsJson(lastStats, ip)
                                }
                                event("加入期间已取消勾选，移除 $ip")
                            } else if (retryImmediately) {
                                event("重新勾选 $ip，立即重试连接")
                            } else if (res.contains("\"error\"")) {
                                event("加入 $ip 暂未就绪，后台自动重连中: $res")
                            } else {
                                event("加入 $ip: $res")
                            }
                            if (running.get() && handle == h) {
                                val raw = NativeBridge.stats(h)
                                val connectingNow = synchronized(routeLock) { pendingReconcileIps.toSet() }
                                if (running.get() && handle == h) {
                                    lastStats = mergeConnectingReceivers(raw, connectingNow)
                                }
                            }
                        }
                        groupVol != null -> {
                            event("setVolume(全组, $groupVol): " + NativeBridge.setVolume(h, "", groupVol!!))
                        }
                        speakerVol != null -> {
                            val (ip, v) = speakerVol!!
                            event("setVolume($ip, $v): " + NativeBridge.setVolume(h, ip, v))
                        }
                    }
                }
            } finally {
                synchronized(routeLock) {
                    inFlightAddIp = null
                    reconcileRunning = false
                }
            }
        }
    }

    private fun resolveTargetOrFail(pkgExtra: String, certExtra: String): CaptureTargetResult.Ok? {
        val store = PrefsTargetAppStore(this)
        val saved = store.loadSelection()
        val pkg = pkgExtra.ifBlank { saved?.packageName }
        val expectCert = certExtra.ifBlank { saved?.signingCertSha256 }
        val resolver = CaptureTargetResolver(AndroidPackageIdentityLookup(this))
        return when (val r = resolver.resolveForCapture(pkg, expectCert)) {
            is CaptureTargetResult.Ok -> {
                // 开始时写回最新签名摘要（仍不存 UID）
                val label = saved?.label?.ifBlank { r.packageName } ?: r.packageName
                store.saveSelection(
                    com.miplay.sender.capture.TargetAppSelection(
                        packageName = r.packageName,
                        label = label,
                        signingCertSha256 = r.signingCertSha256,
                    ),
                )
                r
            }
            is CaptureTargetResult.Fail -> {
                lastFailMessage = r.messageZh
                event("目标解析失败[${r.reason}]: ${r.messageZh}")
                // 身份变化/卸载时清掉过期选择
                if (r.reason == CaptureFailReason.NOT_FOUND ||
                    r.reason == CaptureFailReason.IDENTITY_CHANGED
                ) {
                    store.clearSelection()
                }
                null
            }
        }
    }

    private var statsTick = 0

    private fun statsLoop() {
        main.post(object : Runnable {
            override fun run() {
                if (!running.get() || handle == 0L) return
                val h = handle
                bg("stats") {
                    if (!running.get() || handle != h) return@bg
                    val rawStats = NativeBridge.stats(h)
                    if (!running.get() || handle != h) return@bg
                    val connectingNow = synchronized(routeLock) { pendingReconcileIps.toSet() }
                    lastStats = mergeConnectingReceivers(rawStats, connectingNow)
                    Log.i(TAG, "STATS pushed=$pushed dropped=$dropped " + lastStats)
                    runCatching {
                        val lat = JSONObject(lastStats).optJSONObject("latency")
                        if (lat != null) {
                            Log.i(
                                TAG,
                                "LATENCY kind=${lat.optString("kind")} e2e=${lat.opt("e2e_est_ms")} " +
                                    "ahead=${lat.opt("schedule_ahead_ms")} delay_ms=${lat.optLong("playback_delay_ms")} " +
                                    "flushed_aus=${lat.optLong("feed_flushed_aus_at_start")} " +
                                    "def=${lat.optString("definition").take(120)}",
                            )
                        }
                    }
                    if (source == "app" && pushed == 0L && !noAudioHintPosted && statsTick >= 5) {
                        noAudioHintPosted = true
                        event("未收到可捕获音频，可能未播放/禁止捕获/不支持")
                    }
                    if (++statsTick % 5 == 0) main.post { updateNotification() }
                }
                // 1s 刷新，供界面持续更新延迟诊断
                main.postDelayed(this, 1000)
            }
        })
    }

    private fun summary(): String = runCatching {
        val o = JSONObject(lastStats)
        val rs = o.getJSONArray("receivers")
        val parts = (0 until rs.length()).map { i ->
            val r = rs.getJSONObject(i)
            val st = when {
                !r.isNull("error") -> "断开"
                r.optInt("state", -1) == 2 -> "播放"
                r.optInt("state", -1) == 3 -> "暂停"
                else -> r.optString("phase").take(8)
            }
            val rc = r.optInt("reconnects", 0)
            r.getString("ip").substringAfterLast('.') + ":" + st + (if (rc > 0) "(重连$rc)" else "")
        }
        val src = if (source == "app") targetPackage.substringAfterLast('.') else "静音"
        val e2e = LatencyDisplay.formatMs(LatencyDisplay.optLongOrNull(o.optJSONObject("latency"), "e2e_est_ms"))
        val cfgDelay = o.optLong("playback_delay_us") / 1000
        "${rs.length()} 台 · $src · 配置${cfgDelay}ms · E2E估$e2e · " + parts.joinToString(" ")
    }.getOrDefault(lastEvent)

    private fun buildNotification(): Notification {
        val pi = { action: String, code: Int ->
            PendingIntent.getService(
                this, code,
                Intent(this, CastService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val open = PendingIntent.getActivity(
            this, 9, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            paused && source == "app" -> "已暂停投送（选定 App）"
            paused -> "已暂停投送（静音测试）"
            source == "app" -> "正在投送选定 App 音频"
            else -> "正在投送到小米音箱（静音测试）"
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(summary())
            .setSmallIcon(if (paused) android.R.drawable.ic_media_pause else R.drawable.ic_stat_miplay)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (paused) "恢复" else "暂停",
                    pi(if (paused) ACTION_RESUME else ACTION_PAUSE, 2),
                ).build(),
            )
            .addAction(Notification.Action.Builder(null, "停止", pi(ACTION_STOP, 1)).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (!running.get()) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    /** 严格 UID 过滤；失败返回 false，调用方必须停止，不得回退全系统。 */
    private fun startCapture(uid: Int): Boolean {
        val proj = projection
        if (proj == null) {
            lastFailMessage = "MediaProjection 不可用"
            event(lastFailMessage)
            return false
        }
        if (uid < 0) {
            lastFailMessage = "UID 无效，拒绝开始捕获"
            event(lastFailMessage)
            return false
        }
        val config = try {
            PlaybackCaptureFactory.buildAppCapture(proj, uid)
        } catch (e: Exception) {
            lastFailMessage = "无法构建 UID 过滤捕获：${e.message}"
            event(lastFailMessage)
            return false
        }
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val chunk = 1024 * 4
        val minBuf = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = try {
            AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(config)
                // 收紧捕获环缓：原 16×chunk≈341ms 容量；4×chunk≈85ms，降低捕获侧积压（可证伪：若 read 欠载再放大）。
                .setBufferSizeInBytes(maxOf(chunk * 4, minBuf)).build()
        } catch (e: Exception) {
            lastFailMessage = "AudioRecord 创建失败（可能被拒绝捕获）：${e.message}"
            event(lastFailMessage)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            lastFailMessage = "AudioRecord 未初始化，拒绝捕获或设备不支持"
            event(lastFailMessage)
            rec.release()
            return false
        }
        record = rec
        val enc = AacEncoder { au ->
            val h = handle
            if (h != 0L) {
                if (NativeBridge.pushAac(h, au) == 0) pushed++ else dropped++
            }
        }
        encoder = enc
        rec.startRecording()
        event("AudioPlaybackCapture 已开始（uid=$uid, state=${rec.recordingState}）")
        captureThread = Thread({
            val buf = ByteArray(chunk)
            var peak = 0
            var blocks = 0L
            while (running.get()) {
                var off = 0
                while (off < chunk && running.get()) {
                    val n = rec.read(buf, off, chunk - off)
                    if (n <= 0) {
                        event("AudioRecord.read 返回 $n")
                        return@Thread
                    }
                    off += n
                }
                if (paused) continue
                var i = 0
                while (i < chunk) {
                    val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                    val a = kotlin.math.abs(s.toShort().toInt())
                    if (a > peak) peak = a
                    i += 2
                }
                blocks++
                // 约每秒：验收频谱探针 + 本机静音捕获能量对照
                if (blocks % 12 == 0L) {
                    val snap = PcmProbe.analyzeInterleavedS16LeStereo(buf, chunk)
                    if (BuildConfig.IS_ACCEPTANCE) {
                        lastPcmProbe =
                            "RMS=${"%.0f".format(snap.rms)} peak=${snap.peak} " +
                                "A=${"%.2e".format(snap.energyA)} B=${"%.2e".format(snap.energyB)} " +
                                "⇒ ${snap.dominant} | ${snap.verdictZh}"
                        Log.i(TAG, "PCM_PROBE $lastPcmProbe")
                    }
                    val prevLocked = localMute.snapshot().probeLocked
                    val muteSnap = localMute.onCaptureEnergy(snap.rms)
                    if (muteSnap.status != localMuteStatus || muteSnap.probeLocked != prevLocked) {
                        publishMute(muteSnap)
                        event(muteSnap.messageZh)
                    }
                }
                if (blocks % 470 == 0L) {
                    Log.i(TAG, "capture blocks=$blocks peak=$peak aacProduced=${enc.produced}")
                    peak = 0
                }
                enc.encode(buf, chunk)
            }
        }, "capture").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private fun stopCast() {
        if (!running.getAndSet(false)) return
        event("停止投送并清理资源")
        publishMute(localMute.restore())
        captureThread?.interrupt()
        captureThread = null
        runCatching { record?.stop() }
        record?.release(); record = null
        encoder?.release(); encoder = null
        synchronized(routeLock) {
            desiredReceivers.clear()
            activeOrSubmittedReceivers.clear()
            pendingReconcileIps.clear()
            pendingSpeakerVolumes.clear()
            pendingGroupVolume = null
            connectFinishedForHandle = 0L
            inFlightAddIp = null
            reconcileRunning = false
        }
        val h = handle
        handle = 0L
        lastStats = ""
        if (h != 0L) {
            Thread {
                val finalStats = NativeBridge.stats(h)
                Log.i(TAG, "FINAL $finalStats")
                NativeBridge.stop(h)
            }.start()
        }
        runCatching { projection?.stop() }
        projection = null
        activeGrantSession?.let { sessionPolicy.hasReusableGrant(it) }
        activeGrantSession = null
        captureUid = -1
        releaseLocks()
        // 通知可被用户关掉后仍可通过 App 停止；这里主动取消 ongoing
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    override fun onDestroy() {
        stopCast()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "miplay")
            .apply { setReferenceCounted(false); acquire() }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miplay:cast")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        Log.i(
            TAG,
            "locks acquired; wakeHeld=${wakeLock?.isHeld} wifiHeld=${wifiLock?.isHeld} battery-exempt=$exempt" +
                if (exempt) "" else "（部分品牌后台仍可能冻结，省电豁免≠保活保证）",
        )
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wifiLock = null
        wakeLock = null
        Log.i(TAG, "locks released")
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "妙播投送", NotificationManager.IMPORTANCE_LOW))
        val type = if (projectionType) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        startForeground(NOTIF_ID, buildNotification(), type)
    }
}
