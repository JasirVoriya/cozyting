package com.cozyting.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.cozyting.app.capture.AndroidPackageIdentityLookup
import com.cozyting.app.capture.AudioCaptureBackend
import com.cozyting.app.capture.AutoSourceSelectionPolicy
import com.cozyting.app.capture.CaptureEngineCapabilities
import com.cozyting.app.capture.CaptureEngineMode
import com.cozyting.app.capture.CaptureFailReason
import com.cozyting.app.capture.CaptureScopeMode
import com.cozyting.app.capture.CaptureTargetResolver
import com.cozyting.app.capture.CaptureTargetResult
import com.cozyting.app.capture.MediaProjectionBackend
import com.cozyting.app.capture.PcmProbe
import com.cozyting.app.capture.PrefsTargetAppStore
import com.cozyting.app.capture.ProjectionSessionPolicy
import com.cozyting.app.capture.TargetAppSelection
import com.cozyting.app.capture.shell.PairingNotificationGuide
import com.cozyting.app.capture.shell.ShellCaptureBackend
import com.cozyting.app.cast.ActiveMediaSessionSelector
import com.cozyting.app.cast.AndroidStreamMusicVolume
import com.cozyting.app.cast.CastNotificationFormatter
import com.cozyting.app.cast.LocalOutputMute
import com.cozyting.app.cast.NowPlayingTracker
import com.cozyting.app.cast.PrefsDiscoveredDeviceStore
import com.cozyting.app.compat.AcceptanceAudioDefaults
import com.cozyting.app.compat.CaptureSelectionLayers
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.PrefsCastSettingsStore
import com.cozyting.app.ui.VolumeSafetyGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 前台服务：持有 Rust 投送会话（多台同步、暂停恢复、中途加入、心跳断线重连）。
 * - 静音测试源：Rust 内置 AAC 静音帧；
 * - 选定 App：MediaProjection → AudioPlaybackCapture(UID+usage) → AAC → NativeBridge.pushAac。
 *
 * Android 14+：用户授权 → mediaProjection FGS → getMediaProjection；不采集麦克风。
 */
class CastService : Service() {
    companion object {
        const val TAG = "CozyTing"
        const val ACTION_START = "com.cozyting.app.START"
        const val ACTION_STOP = "com.cozyting.app.STOP"
        const val ACTION_VOLUME = "com.cozyting.app.VOLUME"
        const val ACTION_PAUSE = "com.cozyting.app.PAUSE"
        const val ACTION_RESUME = "com.cozyting.app.RESUME"
        const val ACTION_ADD = "com.cozyting.app.ADD"
        const val ACTION_REMOVE = "com.cozyting.app.REMOVE"
        const val ACTION_SWITCH_SOURCE = "com.cozyting.app.SWITCH_SOURCE"
        const val ACTION_SET_DELAY = "com.cozyting.app.SET_DELAY"
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
        const val EXTRA_VOLUME_CONFIRMED = "volume_confirmed"
        const val EXTRA_SECS = "secs"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_TARGET_LABEL = "target_label"
        const val EXTRA_TARGET_CERT = "target_cert"
        const val EXTRA_TARGET_AUTO_DETECTED = "target_auto_detected"
        const val EXTRA_MANUAL_OVERRIDE_BLOCKED_PKG = "manual_override_blocked_pkg"
        const val EXTRA_GRANT_SESSION = "grant_session"
        const val EXTRA_LOCAL_MUTE_POLICY = "local_mute_policy"
        const val EXTRA_CAPTURE_ENGINE = "capture_engine" // "standard" | "wireless_debug"
        const val EXTRA_CAPTURE_SCOPE = "capture_scope" // "single_app" | "global_media"
        private const val CHANNEL = "cast"
        private const val NOTIF_ID = 1
        private const val MAX_RECENT_EVENTS = 80
        private const val MAX_OPS_LOG_BYTES = 64 * 1024L
        private val eventsLock = Any()
        private val recentEvents = ArrayDeque<String>()
        private val activeReceiversLock = Any()
        private val activeReceiverIpsSet = linkedSetOf<String>()

        @Volatile var handle = 0L
        @Volatile var lastStats: String = ""
        @Volatile var lastEvent: String = "空闲"
        @Volatile var paused = false
        @Volatile var source = "silent"
        @Volatile var activeEngineMode: CaptureEngineMode = CaptureEngineMode.STANDARD
        @Volatile var activeScopeMode: CaptureScopeMode = CaptureScopeMode.SINGLE_APP
        @Volatile var targetPackage: String = ""
        @Volatile var targetLabel: String = ""
        @Volatile var targetAutoDetected: Boolean = false
        @Volatile var targetChangeListener: (() -> Unit)? = null
        @Volatile var lastFailMessage: String = ""
        private val hotSwitchFeedback = AtomicReference("")
        /** 最近一次捕获 PCM 探针摘要（验收变体用于 A/B 量化；静音时不可证明过滤） */
        @Volatile var lastPcmProbe: String = ""
        /** 本机 STREAM_MUSIC 静音状态文案（估计/回退说明，非听感保证）。 */
        @Volatile var localMuteStatusZh: String = "本机出声（未尝试静音）"
        @Volatile var localMuteStatus: LocalOutputMute.Status = LocalOutputMute.Status.IDLE
        @Volatile var localMuteStatusResId: Int = R.string.cast_mute_status_idle
        val running = AtomicBoolean(false)

        fun localizedLocalMuteStatus(context: Context): String {
            val res = localMuteStatusResId
            return if (res != 0) context.getString(res) else localMuteStatusZh
        }

        /** 一次性读取并清空最近一次投送中热切音源反馈（成功或失败回滚原音源）。 */
        fun consumeHotSwitchFeedback(): String? =
            hotSwitchFeedback.getAndSet("").takeIf { it.isNotBlank() }

        fun autoDelayMs(ctx: Context): Int {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val f = runCatching { wm.connectionInfo.frequency }.getOrDefault(0)
            return try {
                NativeBridge.delayForFrequency(f)
            } catch (_: LinkageError) {
                // 仅在桌面 JVM 预览单测（无 arm64 JNI .so）时回退，与 L1 delay_for_frequency 一致
                if (f >= 4900) 800 else 1000
            }
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
            addActiveReceiverIps(clean)
            lastStats = mergeConnectingReceivers(lastStats, clean)
        }

        fun primeRemovingReceiver(ip: String) {
            val target = ip.trim()
            if (target.isEmpty()) return
            removeActiveReceiverIp(target)
            lastStats = removeReceiverFromStatsJson(lastStats, target)
        }

        fun extractReceiverIpsFromStats(statsJson: String): Set<String> {
            if (statsJson.isBlank()) return emptySet()
            return runCatching {
                val root = JSONObject(statsJson)
                val rs = root.optJSONArray("receivers") ?: return@runCatching emptySet<String>()
                val out = linkedSetOf<String>()
                for (i in 0 until rs.length()) {
                    rs.optJSONObject(i)?.optString("ip")?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
                }
                out
            }.getOrDefault(emptySet())
        }

        /**
         * 返回当前活跃投送会话的接收端 IP 集合（`CastService` 单一事实来源）：
         * 仅在 `running == true` 时非空，优先取 L1 `lastStats.receivers`，空时回退内存活跃集合。
         */
        fun activeReceiverIps(): Set<String> {
            if (!running.get()) return emptySet()
            val fromStats = extractReceiverIpsFromStats(lastStats)
            if (fromStats.isNotEmpty()) return fromStats
            return synchronized(activeReceiversLock) { activeReceiverIpsSet.toSet() }
        }

        internal fun setActiveReceiverIps(ips: Collection<String>) {
            val clean = ips.map { it.trim() }.filter { it.isNotEmpty() }
            synchronized(activeReceiversLock) {
                activeReceiverIpsSet.clear()
                activeReceiverIpsSet.addAll(clean)
            }
        }

        internal fun addActiveReceiverIps(ips: Collection<String>) {
            val clean = ips.map { it.trim() }.filter { it.isNotEmpty() }
            if (clean.isEmpty()) return
            synchronized(activeReceiversLock) {
                activeReceiverIpsSet.addAll(clean)
            }
        }

        internal fun removeActiveReceiverIp(ip: String) {
            val target = ip.trim()
            if (target.isEmpty()) return
            synchronized(activeReceiversLock) {
                activeReceiverIpsSet.remove(target)
            }
        }

        internal fun clearActiveReceiverIps() {
            synchronized(activeReceiversLock) {
                activeReceiverIpsSet.clear()
            }
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
    private var captureBackend: AudioCaptureBackend? = null
    private var encoder: AacEncoder? = null
    private val captureLock = Any()
    private val pcmBlocks = AtomicInteger(0)
    private var pcmPeak = 0
    @Volatile private var activeMuteHandling: AudioCaptureBackend.LocalMuteHandling =
        AudioCaptureBackend.LocalMuteHandling.MANAGED_BY_GLOBAL_STREAM_MUTE
    @Volatile private var activeKeepLocalOutput: Boolean = false
    private val switchExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "capture-switch") }
    private var sessionTracker: NowPlayingTracker? = null
    private var blockedUntilNewPlaySeq: Map<String, Long> = emptyMap()
    private var initialBlockedPackage: String? = null
    private var initialSessionsSnapshotted = false
    @Volatile private var switchingToPackage: String? = null
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
    /**
     * 禁止在属性初始化器里 `AndroidStreamMusicVolume(this)`：Service 构造时 base context
     * 尚未 attach，getApplicationContext() 会 NPE（真机 0.9.1 已复现）。
     * 仅在 [onCreate] 之后赋值。
     */
    private lateinit var localMute: LocalOutputMute
    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var targetStore: PrefsTargetAppStore
    private lateinit var deviceStore: PrefsDiscoveredDeviceStore
    @Volatile private var activeInitialIps: List<String> = emptyList()
    @Volatile private var lastNotificationSignature: String = ""

    override fun onCreate() {
        super.onCreate()
        settingsStore = PrefsCastSettingsStore(applicationContext)
        targetStore = PrefsTargetAppStore(applicationContext)
        deviceStore = PrefsDiscoveredDeviceStore(applicationContext)
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
                val confirmed = intent.getBooleanExtra(EXTRA_VOLUME_CONFIRMED, false)
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty().trim()
                if (h != 0L && running.get()) {
                    VolumeSafetyGuard.syncToNative(settingsStore.volumeGuardPolicy())
                    val res = NativeBridge.queueSetVolumeChecked(h, ip, v, confirmed)
                    event("协调音量(${ip.ifEmpty { "全组" }}, $v, confirmed=$confirmed): $res")
                }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { bg("pause") { paused = true; event("暂停: " + NativeBridge.pause(h)); updateNotification() }; return START_NOT_STICKY }
            ACTION_RESUME -> { bg("resume") { event("恢复: " + NativeBridge.resume(h)); paused = false; updateNotification() }; return START_NOT_STICKY }
            ACTION_ADD -> {
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty().trim()
                if (ip.isNotEmpty() && running.get() && h != 0L) {
                    addActiveReceiverIps(listOf(ip))
                    activeInitialIps = activeReceiverIps().toList()
                    val res = NativeBridge.queueAddReceiver(h, ip)
                    lastStats = NativeBridge.stats(h)
                    event("协调加入 $ip: $res")
                    updateNotification(force = true)
                }
                return START_NOT_STICKY
            }
            ACTION_REMOVE -> {
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty().trim()
                if (ip.isNotEmpty() && h != 0L) {
                    removeActiveReceiverIp(ip)
                    activeInitialIps = activeReceiverIps().toList()
                    NativeBridge.queueRemoveReceiver(h, ip)
                    lastStats = NativeBridge.stats(h)
                    event("协调移除 $ip")
                    updateNotification(force = true)
                }
                return START_NOT_STICKY
            }
            ACTION_SWITCH_SOURCE -> {
                val pkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty().trim()
                val label = intent.getStringExtra(EXTRA_TARGET_LABEL).orEmpty().trim()
                val blocked = intent.getStringExtra(EXTRA_MANUAL_OVERRIDE_BLOCKED_PKG)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (running.get() && source == "app" && pkg.isNotEmpty()) {
                    if (blocked != null) {
                        val currentSnaps = sessionTracker?.currentSessionsSnapshot().orEmpty()
                        val blockedSeq = currentSnaps
                            .filter { it.packageName == blocked && it.isPlaying }
                            .maxOfOrNull { it.lastBecamePlayingSeq }
                            ?: Long.MAX_VALUE
                        blockedUntilNewPlaySeq = blockedUntilNewPlaySeq + (blocked to blockedSeq)
                    }
                    if (pkg != targetPackage && pkg != switchingToPackage) {
                        requestHotSwitchTarget(pkg, label.ifEmpty { resolveAppLabel(pkg) }, autoDetected = false)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_SET_DELAY -> {
                val reqDelay = intent.getIntExtra(EXTRA_DELAY, 0)
                val effectiveMs = if (reqDelay > 0) reqDelay else autoDelayMs(this)
                if (h != 0L && running.get()) {
                    bg("set-delay") {
                        val res = NativeBridge.setPlaybackDelay(h, effectiveMs)
                        if (handle == h && running.get()) {
                            lastStats = NativeBridge.stats(h)
                            event("热更新播放缓冲延迟(${effectiveMs}ms): $res")
                            main.post { updateNotification() }
                        }
                    }
                }
                return START_NOT_STICKY
            }
        }

        source = intent?.getStringExtra(EXTRA_SOURCE) ?: "silent"
        val requestedEngine = CaptureEngineMode.fromId(
            intent?.getStringExtra(EXTRA_CAPTURE_ENGINE) ?: settingsStore.captureEngineMode.id,
        )
        val requestedScope = CaptureScopeMode.fromId(
            intent?.getStringExtra(EXTRA_CAPTURE_SCOPE) ?: settingsStore.captureScopeMode.id,
        )
        val policyArgEarly = intent?.getStringExtra(EXTRA_LOCAL_MUTE_POLICY).orEmpty()
        activeKeepLocalOutput = policyArgEarly == "keep_local"
        activeEngineMode = if (source == "app") requestedEngine else CaptureEngineMode.STANDARD
        activeScopeMode = if (source == "app" && activeEngineMode == CaptureEngineMode.WIRELESS_DEBUG) {
            requestedScope
        } else {
            CaptureScopeMode.SINGLE_APP
        }
        val ips = intent?.getStringExtra(EXTRA_IPS).orEmpty()
        val initialIps = parseIps(ips)
        if (!running.get()) {
            activeInitialIps = initialIps
            setActiveReceiverIps(initialIps)
            if (source == "app" && activeScopeMode == CaptureScopeMode.SINGLE_APP) {
                val earlyPkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty().trim()
                val earlyLabel = intent?.getStringExtra(EXTRA_TARGET_LABEL).orEmpty().trim()
                if (earlyPkg.isNotEmpty()) targetPackage = earlyPkg
                if (earlyLabel.isNotEmpty()) targetLabel = earlyLabel
            }
        }
        // 无感模式无需 mediaProjection 前台类型，彻底避免「正在投屏」系统状态栏提示
        projectionType = source == "app" && activeEngineMode == CaptureEngineMode.STANDARD

        // Android 14+：先进入对应类型的 FGS，再 getMediaProjection（无感模式仅用 mediaPlayback）
        startForegroundCompat()

        if (running.getAndSet(true)) {
            if (initialIps.isNotEmpty() && h != 0L) {
                addActiveReceiverIps(initialIps)
                activeInitialIps = activeReceiverIps().toList()
                initialIps.forEach { ip -> NativeBridge.queueAddReceiver(h, ip) }
                lastStats = NativeBridge.stats(h)
                event("已在投送中，合并目标音箱: ${initialIps.joinToString(",")}")
                updateNotification(force = true)
            } else {
                event("已在投送中，忽略重复开始")
            }
            return START_NOT_STICKY
        }
        paused = false
        lastFailMessage = ""
        noAudioHintPosted = false
        lastStats = mergeConnectingReceivers("", initialIps.toSet())
        updateNotification(force = true)
        acquireLocks()

        if (source == "app") {
            if (activeEngineMode == CaptureEngineMode.WIRELESS_DEBUG) {
                val caps = CaptureEngineCapabilities.forSdkInt(Build.VERSION.SDK_INT)
                val validation = caps.validate(
                    requestedEngine = activeEngineMode,
                    requestedScope = activeScopeMode,
                    hasPairedCertificate = PairingNotificationGuide.keyStore(this).isPaired(),
                    keepLocalOutput = activeKeepLocalOutput,
                )
                if (validation is CaptureEngineCapabilities.ValidationResult.Rejected) {
                    lastFailMessage = validation.messageZh
                    event("无感采集前置校验拒绝：${validation.messageZh}")
                    abortEarlyStart()
                    return START_NOT_STICKY
                }
            }

            if (activeScopeMode == CaptureScopeMode.SINGLE_APP) {
                val pkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
                val cert = intent?.getStringExtra(EXTRA_TARGET_CERT).orEmpty()
                val resolved = resolveTargetOrFail(pkg, cert)
                if (resolved == null) {
                    abortEarlyStart()
                    return START_NOT_STICKY
                }
                targetPackage = resolved.packageName
                targetLabel = intent?.getStringExtra(EXTRA_TARGET_LABEL)?.takeIf { it.isNotBlank() }
                    ?: resolveAppLabel(resolved.packageName)
                targetAutoDetected = intent?.getBooleanExtra(EXTRA_TARGET_AUTO_DETECTED, false) ?: false
                initialBlockedPackage = intent?.getStringExtra(EXTRA_MANUAL_OVERRIDE_BLOCKED_PKG)?.trim()
                    ?.takeIf { it.isNotEmpty() && it != resolved.packageName }
                captureUid = resolved.uid
                event(CaptureSelectionLayers.statusLine(targetPackage, captureUid))
            } else {
                targetPackage = ""
                targetLabel = AppLocaleManager.localizedContext(this).getString(R.string.cast_notif_global_media)
                targetAutoDetected = false
                initialBlockedPackage = null
                captureUid = -1
                event("已显式启用无感全局媒体捕获模式")
            }

            if (activeEngineMode == CaptureEngineMode.STANDARD) {
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
                    lastFailMessage = AppLocaleManager.localizedContext(this).getString(R.string.cast_err_projection_invalid)
                    abortEarlyStart()
                    return START_NOT_STICKY
                }
                activeGrantSession = grantSession
                // FGS 已启动 → 现在才 getMediaProjection
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                if (projection == null) {
                    event("getMediaProjection 返回 null")
                    lastFailMessage = AppLocaleManager.localizedContext(this).getString(R.string.cast_err_projection_null)
                    abortEarlyStart()
                    return START_NOT_STICKY
                }
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        event("MediaProjection 已停止/撤销，正在清理")
                        lastFailMessage = AppLocaleManager.localizedContext(this@CastService).getString(R.string.cast_err_projection_revoked)
                        stopCast()
                        stopSelf()
                    }
                }, main)
            }
        }

        val reqDelay = intent?.getIntExtra(EXTRA_DELAY, 0) ?: 0
        val delay = if (reqDelay > 0) reqDelay else autoDelayMs(this)
        val premute = intent?.getIntExtra(EXTRA_PREMUTE, 0) ?: 0
        val volumeConfirmed = intent?.getBooleanExtra(EXTRA_VOLUME_CONFIRMED, false) ?: false
        val ref = intent?.getStringExtra(EXTRA_REF).orEmpty()
        val secs = intent?.getIntExtra(EXTRA_SECS, 0) ?: 0
        val sessionHandle = NativeBridge.create(source == "silent")
        handle = sessionHandle
        bg("cast-start") {
            val srcLabel = if (source == "app") "app=$targetPackage uid=$captureUid" else "silent"
            event("连接音箱 $ips（source=$srcLabel, delay=${delay}ms${if (reqDelay <= 0) "(自动)" else ""}, 音量=$premute）")
            if (source == "app") {
                val started = synchronized(captureLock) { startCaptureLocked(captureUid, reuseEncoder = false) }
                if (!started) {
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
            VolumeSafetyGuard.syncToNative(settingsStore.volumeGuardPolicy())
            val st = NativeBridge.connectWithVolumeConfirm(
                sessionHandle,
                ips,
                delay,
                premute,
                premute,
                ref,
                Build.MODEL ?: "Android",
                volumeConfirmed,
            )
            if (!running.get() || handle != sessionHandle) {
                return@bg
            }
            event("connect 返回：$st")
            if (st.contains("\"error\"") && !st.contains("\"receivers\"")) {
                stopCast(); stopSelf(); return@bg
            }
            lastStats = st
            if (source == "app") {
                applyLocalMuteForActiveBackend()
                event(localMuteStatusZh)
                if (activeScopeMode == CaptureScopeMode.SINGLE_APP) {
                    main.post { startAutoSourceMonitor() }
                }
            } else {
                publishMute(localMute.skip("silent source"))
            }
            statsLoop()
            if (secs > 0) main.postDelayed({ event("定时 ${secs}s 到，停止"); stopCast(); stopSelf() }, secs * 1000L)
        }
        return START_NOT_STICKY
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
                    com.cozyting.app.capture.TargetAppSelection(
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
                    lastStats = rawStats
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
                    val tick = ++statsTick
                    // 主路径为系统 MediaSession 事件回调；此处仅每 10 秒低频兜底一次且仅在快照变化时触发业务
                    if (source == "app" && tick % 10 == 0 && settingsStore.autoDetectAudioSource) {
                        main.post {
                            if (running.get() && source == "app") sessionTracker?.refresh()
                        }
                    }
                    // 文案或设备状态变化时立即刷新通知，无变化时不重复打扰系统通知栏
                    main.post { updateNotification(force = false) }
                }
                // 1s 刷新，供界面持续更新延迟诊断
                main.postDelayed(this, 1000)
            }
        })
    }

    private fun cachedDeviceNamesByIp(): Map<String, String> = runCatching {
        if (!::deviceStore.isInitialized) return@runCatching emptyMap<String, String>()
        deviceStore.loadDevices()
            .filter { it.isCastSink && it.id.isNotBlank() && it.name.isNotBlank() }
            .associate { it.id to it.name }
    }.getOrDefault(emptyMap())

    private fun formatNotificationCopy(): CastNotificationFormatter.NotificationCopy {
        val locCtx = com.cozyting.app.ui.AppLocaleManager.localizedContext(this)
        return CastNotificationFormatter.format(
            source = source,
            scopeMode = activeScopeMode,
            targetLabel = targetLabel,
            targetPackage = targetPackage,
            paused = paused,
            statsJson = lastStats,
            fallbackIps = activeInitialIps,
            deviceNamesByIp = cachedDeviceNamesByIp(),
            strings = CastNotificationFormatter.Strings.fromContext(locCtx),
        )
    }

    private fun buildNotification(copy: CastNotificationFormatter.NotificationCopy = formatNotificationCopy()): Notification {
        val locCtx = com.cozyting.app.ui.AppLocaleManager.localizedContext(this)
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
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle(copy.title)
            .setContentText(copy.collapsedText)
            .setSmallIcon(if (paused) android.R.drawable.ic_media_pause else R.drawable.ic_stat_miplay)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (paused) locCtx.getString(R.string.cast_action_resume) else locCtx.getString(R.string.cast_action_pause),
                    pi(if (paused) ACTION_RESUME else ACTION_PAUSE, 2),
                ).build(),
            )
            .addAction(Notification.Action.Builder(null, locCtx.getString(R.string.cast_action_stop), pi(ACTION_STOP, 1)).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (!copy.expandedText.isNullOrBlank()) {
            builder.setStyle(Notification.BigTextStyle().bigText(copy.expandedText))
        }
        return builder.build()
    }

    private fun updateNotification(force: Boolean = true) {
        if (!running.get()) return
        val copy = formatNotificationCopy()
        val signature = "${paused}|${copy.title}|${copy.collapsedText}|${copy.expandedText.orEmpty()}"
        if (!force && signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(copy))
    }

    private fun resolveAppLabel(pkg: String): String =
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.').ifBlank { pkg })

    private fun startAutoSourceMonitor() {
        val pendingInitialBlocked = initialBlockedPackage
        stopAutoSourceMonitor()
        if (!running.get() || source != "app") return
        initialBlockedPackage = pendingInitialBlocked
        initialSessionsSnapshotted = false
        blockedUntilNewPlaySeq = emptyMap()
        sessionTracker = NowPlayingTracker(
            context = this,
            onUpdate = {},
            onSessionsChanged = { sessions -> onActiveSessionsChanged(sessions) },
            publishMetadata = false,
        ).also { it.start() }
    }

    private fun stopAutoSourceMonitor() {
        sessionTracker?.stop()
        sessionTracker = null
        blockedUntilNewPlaySeq = emptyMap()
        initialBlockedPackage = null
        initialSessionsSnapshotted = false
    }

    private fun onActiveSessionsChanged(sessions: List<ActiveMediaSessionSelector.SessionSnapshot>) {
        if (!running.get() || source != "app") return
        if (!initialSessionsSnapshotted) {
            initialSessionsSnapshotted = true
            val initialBlocked = linkedMapOf<String, Long>()
            for (snap in sessions) {
                if (snap.isPlaying && snap.packageName.isNotBlank() && snap.packageName != targetPackage) {
                    val prev = initialBlocked[snap.packageName]
                    if (prev == null || snap.lastBecamePlayingSeq > prev) {
                        initialBlocked[snap.packageName] = snap.lastBecamePlayingSeq
                    }
                }
            }
            initialBlockedPackage?.let { pkg ->
                if (pkg !in initialBlocked) {
                    val maxSeq = sessions
                        .filter { it.packageName == pkg && it.isPlaying }
                        .maxOfOrNull { it.lastBecamePlayingSeq }
                    if (maxSeq != null) initialBlocked[pkg] = maxSeq
                }
            }
            blockedUntilNewPlaySeq = initialBlocked
        } else {
            blockedUntilNewPlaySeq = ActiveMediaSessionSelector.pruneBlockedPackages(
                blockedUntilNewPlaySeq = blockedUntilNewPlaySeq,
                sessions = sessions,
            )
        }

        if (!settingsStore.autoDetectAudioSource || settingsStore.sourceMode != "app") return
        val favs = targetStore.loadFavoriteSources()
        if (favs.isEmpty()) return

        val candidate = ActiveMediaSessionSelector.selectAutoDetectCandidate(
            sessions = sessions,
            favoriteSources = favs,
            isCasting = true,
            currentSelectedPackage = targetPackage,
            selfPackage = packageName,
            blockedUntilNewPlaySeq = blockedUntilNewPlaySeq,
        ) ?: return

        val decision = AutoSourceSelectionPolicy.decide(
            AutoSourceSelectionPolicy.Input(
                globalAutoDetectEnabled = settingsStore.autoDetectAudioSource,
                isCasting = true,
                sourceMode = settingsStore.sourceMode,
                currentSelectedPackage = targetPackage,
                selfPackage = packageName,
                nowPlayingPackage = candidate.packageName,
                nowPlayingIsPlaying = candidate.isPlaying,
                favoriteSources = favs,
            ),
        )
        val nextPkg = (decision as? AutoSourceSelectionPolicy.Decision.AutoSelect)?.packageName ?: return
        if (nextPkg == targetPackage || nextPkg == switchingToPackage) return
        requestHotSwitchTarget(nextPkg, resolveAppLabel(nextPkg), autoDetected = true)
    }

    private fun requestHotSwitchTarget(newPackage: String, newLabel: String, autoDetected: Boolean) {
        switchingToPackage = newPackage
        switchExecutor.execute {
            try {
                hotSwitchCaptureTarget(newPackage, newLabel, autoDetected)
            } finally {
                if (switchingToPackage == newPackage) switchingToPackage = null
            }
        }
    }

    private fun applyLocalMuteForActiveBackend() {
        when (activeMuteHandling) {
            AudioCaptureBackend.LocalMuteHandling.ISOLATED_APP_LOOPBACK -> {
                localMute.skip("isolated single-app loopback mute")
                localMuteStatus = LocalOutputMute.Status.ACTIVE_VERIFIED
                localMuteStatusResId = R.string.cast_mute_status_single_app
                localMuteStatusZh = "单 App 本机静音已生效（无感环回，不影响其他 App 手机出声）"
                Log.i(TAG, "LOCAL_MUTE mode=ISOLATED_APP_LOOPBACK msg=$localMuteStatusZh")
            }
            AudioCaptureBackend.LocalMuteHandling.SYSTEM_ROUTE_LOOPBACK -> {
                localMute.skip("system route loopback")
                localMuteStatus = LocalOutputMute.Status.ACTIVE_VERIFIED
                localMuteStatusResId = R.string.cast_mute_status_global
                localMuteStatusZh = "全局媒体本机静音已生效（无感环回路由）"
                Log.i(TAG, "LOCAL_MUTE mode=SYSTEM_ROUTE_LOOPBACK msg=$localMuteStatusZh")
            }
            AudioCaptureBackend.LocalMuteHandling.KEEP_LOCAL_OUTPUT -> {
                localMuteStatusResId = 0
                publishMute(localMute.beginMuteForCast(LocalOutputMute.Policy.KEEP_LOCAL_OUTPUT))
            }
            AudioCaptureBackend.LocalMuteHandling.MANAGED_BY_GLOBAL_STREAM_MUTE -> {
                localMuteStatusResId = 0
                publishMute(localMute.beginMuteForCast(LocalOutputMute.Policy.GLOBAL_STREAM_MUTE))
            }
        }
    }

    /**
     * 在保持当前采集后端与音箱投送会话不断线的前提下，按严格单 UID 热切换捕获目标。
     * 任何解析或重建失败一律 fail-closed（尝试恢复原 UID，无法恢复则停止会话），绝不退化全系统捕获。
     */
    private fun hotSwitchCaptureTarget(newPackage: String, newLabel: String, autoDetected: Boolean) {
        synchronized(captureLock) {
            val backend = captureBackend ?: return
            if (!running.get() || source != "app" || activeScopeMode != CaptureScopeMode.SINGLE_APP) return
            if (newPackage.isBlank() || newPackage == targetPackage) return

            val locCtx = com.cozyting.app.ui.AppLocaleManager.localizedContext(this)
            val modeLabel = if (autoDetected) "自动" else "手动"
            val resolver = CaptureTargetResolver(AndroidPackageIdentityLookup(this))
            val resolved = when (val r = resolver.resolveForCapture(newPackage)) {
                is CaptureTargetResult.Ok -> r
                is CaptureTargetResult.Fail -> {
                    event("${modeLabel}切换音源被拒绝[${r.reason}]: ${r.messageZh}")
                    if (!autoDetected) {
                        hotSwitchFeedback.set(locCtx.getString(R.string.cast_hotswitch_rejected, r.messageZh))
                        main.post { targetChangeListener?.invoke() }
                    }
                    return
                }
            }
            val prevPkg = targetPackage
            val prevUid = captureUid
            val prevLabel = targetLabel.ifBlank { prevPkg.substringAfterLast('.') }
            event("正在${modeLabel}热切换音源：$prevLabel -> $newLabel（uid $prevUid -> ${resolved.uid}）")

            when (val switched = backend.hotSwitchUid(resolved.uid, activeKeepLocalOutput)) {
                is AudioCaptureBackend.StartResult.Started -> {
                    activeMuteHandling = switched.muteHandling
                    targetPackage = resolved.packageName
                    targetLabel = newLabel
                    targetAutoDetected = autoDetected
                    captureUid = resolved.uid
                    noAudioHintPosted = false
                    lastFailMessage = ""
                    targetStore.saveSelection(
                        TargetAppSelection(
                            packageName = resolved.packageName,
                            label = newLabel,
                            signingCertSha256 = resolved.signingCertSha256,
                        ),
                    )
                    if (!autoDetected) {
                        hotSwitchFeedback.set(locCtx.getString(R.string.cast_hotswitch_ok, newLabel))
                    }
                    event(
                        if (autoDetected) {
                            "已自动切换音源：$newLabel（uid=${resolved.uid}）"
                        } else {
                            "已切换音源：$newLabel（uid=${resolved.uid}）"
                        },
                    )
                    main.post {
                        updateNotification()
                        targetChangeListener?.invoke()
                    }
                }
                is AudioCaptureBackend.StartResult.Failed -> {
                    if (switched.messageZh.contains("已恢复原音源")) {
                        lastFailMessage = ""
                        event("无法热切换至 $newLabel，已保持当前音源 $prevLabel：${switched.messageZh}")
                        if (!autoDetected) {
                            hotSwitchFeedback.set(locCtx.getString(R.string.cast_hotswitch_reverted, newLabel, prevLabel))
                        }
                        main.post {
                            updateNotification()
                            targetChangeListener?.invoke()
                        }
                    } else {
                        lastFailMessage = locCtx.getString(R.string.cast_hotswitch_stopped, newLabel, switched.messageZh)
                        event(lastFailMessage)
                        main.post {
                            stopCast()
                            stopSelf()
                            targetChangeListener?.invoke()
                        }
                    }
                }
            }
        }
    }

    /** 仅停止当前采集后端（保留编码器、MediaProjection 与音箱连接）。 */
    private fun stopCaptureOnlyLocked() {
        val oldBackend = captureBackend
        captureBackend = null
        runCatching { oldBackend?.stop() }
    }

    /** 启动当前采集后端（MediaProjectionBackend 或 ShellCaptureBackend）；失败一律 fail-closed。 */
    private fun startCaptureLocked(uid: Int, reuseEncoder: Boolean): Boolean {
        val backend: AudioCaptureBackend = when (activeEngineMode) {
            CaptureEngineMode.STANDARD -> {
                val proj = projection
                if (proj == null) {
                    lastFailMessage = AppLocaleManager.localizedContext(this).getString(R.string.cast_err_projection_unavailable)
                    event(lastFailMessage)
                    return false
                }
                MediaProjectionBackend(
                    projection = proj,
                    isSessionRunning = { running.get() },
                    onEvent = { event(it) },
                )
            }
            CaptureEngineMode.WIRELESS_DEBUG -> {
                ShellCaptureBackend(
                    context = applicationContext,
                    isSessionRunning = { running.get() },
                    onEvent = { event(it) },
                )
            }
        }

        val enc = if (reuseEncoder && encoder != null) {
            encoder!!
        } else {
            AacEncoder { au ->
                val h = handle
                if (h != 0L) {
                    if (NativeBridge.pushAac(h, au) == 0) pushed++ else dropped++
                }
            }.also { encoder = it }
        }

        pcmBlocks.set(0)
        pcmPeak = 0
        val req = AudioCaptureBackend.CaptureRequest(
            scopeMode = activeScopeMode,
            targetUid = uid,
            keepLocalOutput = activeKeepLocalOutput,
        )
        val result = backend.start(
            request = req,
            onPcmFrame = { buf, len -> onCapturedPcmFrame(enc, buf, len) },
            onFatalError = { reason ->
                lastFailMessage = reason
                event(reason)
                main.post {
                    stopCast()
                    stopSelf()
                    targetChangeListener?.invoke()
                }
            },
        )
        return when (result) {
            is AudioCaptureBackend.StartResult.Started -> {
                captureBackend = backend
                activeMuteHandling = result.muteHandling
                true
            }
            is AudioCaptureBackend.StartResult.Failed -> {
                lastFailMessage = result.messageZh
                event(result.messageZh)
                runCatching { backend.stop() }
                false
            }
        }
    }

    private fun onCapturedPcmFrame(enc: AacEncoder, buf: ByteArray, len: Int) {
        if (paused || !running.get()) return
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            val a = kotlin.math.abs(s.toShort().toInt())
            if (a > pcmPeak) pcmPeak = a
            i += 2
        }
        val blocks = pcmBlocks.incrementAndGet().toLong()
        if (blocks % 12 == 0L) {
            val snap = PcmProbe.analyzeInterleavedS16LeStereo(buf, len)
            if (BuildConfig.IS_ACCEPTANCE) {
                lastPcmProbe =
                    "RMS=${"%.0f".format(snap.rms)} peak=${snap.peak} " +
                        "A=${"%.2e".format(snap.energyA)} B=${"%.2e".format(snap.energyB)} " +
                        "⇒ ${snap.dominant} | ${snap.verdictZh}"
                Log.i(TAG, "PCM_PROBE $lastPcmProbe")
            }
            if (activeMuteHandling == AudioCaptureBackend.LocalMuteHandling.MANAGED_BY_GLOBAL_STREAM_MUTE) {
                val prevLocked = localMute.snapshot().probeLocked
                val muteSnap = localMute.onCaptureEnergy(snap.rms)
                if (muteSnap.status != localMuteStatus || muteSnap.probeLocked != prevLocked) {
                    publishMute(muteSnap)
                    event(muteSnap.messageZh)
                }
            }
        }
        if (blocks % 470 == 0L) {
            Log.i(TAG, "capture blocks=$blocks peak=$pcmPeak aacProduced=${enc.produced}")
            pcmPeak = 0
        }
        enc.encode(buf, len)
    }

    private fun abortEarlyStart() {
        running.set(false)
        lastStats = ""
        activeInitialIps = emptyList()
        clearActiveReceiverIps()
        lastNotificationSignature = ""
        releaseLocks()
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        stopSelf()
        main.post { targetChangeListener?.invoke() }
    }

    private fun stopCast() {
        if (!running.getAndSet(false)) return
        event("停止投送并清理资源")
        switchingToPackage = null
        hotSwitchFeedback.set("")
        main.post { stopAutoSourceMonitor() }
        val wasLoopbackMute = activeMuteHandling == AudioCaptureBackend.LocalMuteHandling.ISOLATED_APP_LOOPBACK ||
            activeMuteHandling == AudioCaptureBackend.LocalMuteHandling.SYSTEM_ROUTE_LOOPBACK
        if (wasLoopbackMute) {
            localMute.skip("stopped")
            localMuteStatus = LocalOutputMute.Status.IDLE
            localMuteStatusResId = R.string.cast_mute_status_stopped
            localMuteStatusZh = "本机出声（已停止投送）"
        } else {
            localMuteStatusResId = 0
            publishMute(localMute.restore())
        }
        activeMuteHandling = AudioCaptureBackend.LocalMuteHandling.MANAGED_BY_GLOBAL_STREAM_MUTE
        synchronized(captureLock) {
            stopCaptureOnlyLocked()
            encoder?.release(); encoder = null
            runCatching { projection?.stop() }
            projection = null
        }
        val h = handle
        handle = 0L
        lastStats = ""
        activeInitialIps = emptyList()
        clearActiveReceiverIps()
        lastNotificationSignature = ""
        if (h != 0L) {
            Thread {
                val finalStats = NativeBridge.stats(h)
                Log.i(TAG, "FINAL $finalStats")
                NativeBridge.stop(h)
            }.start()
        }
        activeGrantSession?.let { sessionPolicy.hasReusableGrant(it) }
        activeGrantSession = null
        captureUid = -1
        releaseLocks()
        // 通知可被用户关掉后仍可通过 App 停止；这里主动取消 ongoing
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        main.post { targetChangeListener?.invoke() }
    }

    override fun onDestroy() {
        stopCast()
        stopAutoSourceMonitor()
        switchExecutor.shutdownNow()
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
        val locCtx = com.cozyting.app.ui.AppLocaleManager.localizedContext(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, locCtx.getString(R.string.cast_channel_name), NotificationManager.IMPORTANCE_LOW))
        val type = if (projectionType) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        val copy = formatNotificationCopy()
        lastNotificationSignature = "${paused}|${copy.title}|${copy.collapsedText}|${copy.expandedText.orEmpty()}"
        startForeground(NOTIF_ID, buildNotification(copy), type)
    }
}
