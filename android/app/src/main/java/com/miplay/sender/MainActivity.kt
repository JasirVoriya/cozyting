package com.miplay.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.miplay.sender.cast.CastProtocol
import com.miplay.sender.cast.CastRouteSelection
import com.miplay.sender.cast.CastRouteSelection.Effect
import com.miplay.sender.cast.CastRouteSelection.Kind
import com.miplay.sender.cast.NowPlayingInfo
import com.miplay.sender.cast.NowPlayingTracker
import com.miplay.sender.cast.ProtocolBadge
import com.miplay.sender.capture.LocalFilterAcceptanceGate
import com.miplay.sender.capture.PrefsTargetAppStore
import com.miplay.sender.capture.TargetAppSelection
import org.json.JSONObject
import java.util.UUID

/**
 * 主界面按小米系统「音乐投送」面板重做：毛玻璃卡片、本机/音箱互斥路由、MediaSession 正在播放。
 * 次要项（认证/延迟/验收探针）收在底部设置区，不另起花哨设计。
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: PrefsTargetAppStore
    private lateinit var routeState: CastRouteSelection.State

    private lateinit var rootFrame: FrameLayout
    private lateinit var bgArt: ImageView
    private lateinit var glassCard: LinearLayout
    private lateinit var albumArt: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var latencyLine: TextView
    private lateinit var muteLine: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var status: TextView
    private lateinit var settingsBox: LinearLayout
    private lateinit var targetLabel: TextView
    private lateinit var sourceGroup: RadioGroup
    private lateinit var delayEdit: EditText
    private lateinit var refSpinner: Spinner
    private lateinit var authSpinner: Spinner
    private lateinit var manualIps: EditText
    private var localProbeBtn: Button? = null
    private var localProbeStatus: TextView? = null

    private var pendingStart: Intent? = null
    private var pendingLocalProbe: Intent? = null
    private var selectedPackage: String = ""
    private var selectedLabel: String = ""
    private var selectedCert: String = ""
    private var lastGrantSession: String? = null
    private var pendingIps: Set<String>? = null
    private var suppressRoute = false
    private var nowPlaying: NowPlayingInfo = NowPlayingInfo.unavailable("加载中…")
    private var nowPlayingTracker: NowPlayingTracker? = null
    private val speakerVolume = linkedMapOf<String, Int>()

    // 面板色（对齐截图：深色玻璃 + 选中浅底）
    private val glassBg = Color.argb(200, 28, 32, 40)
    private val rowDark = Color.argb(160, 40, 44, 52)
    private val rowSelected = Color.argb(230, 220, 228, 236)
    private val textOnDark = Color.WHITE
    private val textOnLight = Color.rgb(20, 24, 28)
    private val accentMuted = Color.argb(180, 200, 210, 220)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        store = PrefsTargetAppStore(this)
        routeState = CastRouteSelection.initial(
            listOf(CastRouteSelection.Device("this", "This device", Kind.THIS_DEVICE, true)),
        )
        buildUi()
        refreshTargetFromStore()
        val need = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        nowPlayingTracker = NowPlayingTracker(this) { info ->
            main.post {
                nowPlaying = info
                bindNowPlaying()
            }
        }.also { it.start() }
        refreshLoop()
        if (intent?.extras == null) discover()
        handleAutomation(intent)
    }

    override fun onDestroy() {
        nowPlayingTracker?.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomation(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) refreshTargetFromStore()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            if (p == Manifest.permission.RECORD_AUDIO && !ok) {
                status.text = "未授予 RECORD_AUDIO：无法进行播放捕获（不采集麦克风）"
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun buildUi() {
        val pad = dp(16)
        rootFrame = FrameLayout(this)
        bgArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(placeholderArt())
            setRenderEffect(RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP))
            setColorFilter(Color.argb(120, 0, 0, 0))
        }
        rootFrame.addView(
            bgArt,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val scroll = ScrollView(this)
        glassCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(glassBg, 28f)
            setPadding(pad, pad, pad, pad)
        }

        // —— 正在播放 ——
        val mediaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        albumArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.argb(80, 255, 255, 255), 12f)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(12) }
            setImageBitmap(placeholderArt())
            clipToOutline = true
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = TextView(this).apply {
            text = "未知标题"
            setTextColor(textOnDark)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        artistView = TextView(this).apply {
            text = "未取得正在播放"
            setTextColor(accentMuted)
            textSize = 13f
            maxLines = 2
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fun ctl(res: Int, click: () -> Unit) = ImageButton(this).apply {
            setImageResource(res)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        controls.addView(ctl(android.R.drawable.ic_media_previous) { nowPlayingTracker?.skipPrevious() })
        playBtn = ctl(android.R.drawable.ic_media_play) { nowPlayingTracker?.playPause() }
        controls.addView(playBtn)
        controls.addView(ctl(android.R.drawable.ic_media_next) { nowPlayingTracker?.skipNext() })
        meta.addView(titleView)
        meta.addView(artistView)
        meta.addView(controls)
        mediaRow.addView(albumArt)
        mediaRow.addView(meta)
        glassCard.addView(mediaRow)

        progressBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setPadding(0, dp(12), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val dur = nowPlaying.durationMs ?: return
                    nowPlayingTracker?.seekTo(dur * progress / 1000L)
                }
            })
        }
        glassCard.addView(progressBar)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        posView = TextView(this).apply { setTextColor(accentMuted); textSize = 11f; text = "—:—" }
        durView = TextView(this).apply {
            setTextColor(accentMuted); textSize = 11f; text = "—:—"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
        }
        times.addView(posView)
        times.addView(durView)
        glassCard.addView(times)

        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        volRow.addView(
            ImageView(this).apply {
                setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                setColorFilter(accentMuted)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            },
        )
        volumeBar = SeekBar(this).apply {
            max = 100
            progress = 30
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (CastService.running.get()) {
                        setVolume("", progress)
                        routeState.selectedSpeakerIds.forEach { speakerVolume[it] = progress }
                        renderDevices()
                    }
                }
            })
        }
        volRow.addView(volumeBar)
        glassCard.addView(volRow)

        latencyLine = TextView(this).apply {
            setTextColor(Color.argb(160, 180, 190, 200))
            textSize = 10f
            text = "延迟 —"
            setPadding(0, 0, 0, dp(4))
        }
        glassCard.addView(latencyLine)
        muteLine = TextView(this).apply {
            setTextColor(Color.argb(160, 180, 190, 200))
            textSize = 10f
            text = CastService.localMuteStatusZh
            setPadding(0, 0, 0, dp(10))
        }
        glassCard.addView(muteLine)

        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        glassCard.addView(deviceList)

        status = TextView(this).apply {
            setTextColor(accentMuted)
            textSize = 12f
            text = "本机出声 · 勾选音箱开始投送"
            setPadding(0, dp(10), 0, dp(6))
        }
        glassCard.addView(status)

        // —— 设置（折叠） ——
        val settingsToggle = TextView(this).apply {
            text = "设置 ▸ 音源 / 认证 / 发现 / 验收"
            setTextColor(accentMuted)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener {
                settingsBox.visibility =
                    if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (settingsBox.visibility == View.VISIBLE) {
                    "设置 ▾ 音源 / 认证 / 发现 / 验收"
                } else {
                    "设置 ▸ 音源 / 认证 / 发现 / 验收"
                }
            }
        }
        glassCard.addView(settingsToggle)
        settingsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        buildSettings(settingsBox)
        glassCard.addView(settingsBox)

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad + dp(24), pad, pad)
            addView(glassCard)
        }
        scroll.addView(wrap)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        rootFrame.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(rootFrame)
        renderDevices()
        bindNowPlaying()
    }

    private fun buildSettings(box: LinearLayout) {
        box.addView(
            Button(this).apply {
                text = "重新发现设备"
                setOnClickListener { discover() }
            },
        )
        box.addView(
            Button(this).apply {
                text = "打开通知使用权（正在播放）"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            },
        )
        box.addView(
            Button(this).apply {
                text = "电池优化豁免"
                setOnClickListener {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            },
        )
        sourceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(context).apply { id = 1; text = "测试静音"; setTextColor(textOnDark) })
            addView(
                RadioButton(context).apply {
                    id = 2
                    text = "选定 App 音频（UID 过滤）"
                    setTextColor(textOnDark)
                },
            )
            check(2)
        }
        box.addView(sourceGroup)
        targetLabel = TextView(this).apply { setTextColor(accentMuted); textSize = 12f }
        box.addView(targetLabel)
        box.addView(
            Button(this).apply {
                text = "选择投送的 App"
                setOnClickListener { openAppPicker() }
            },
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "延迟ms "; setTextColor(accentMuted) })
        delayEdit = EditText(this).apply {
            hint = "自动(${CastService.autoDelayMs(this@MainActivity)})"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(textOnDark)
            setHintTextColor(accentMuted)
            minEms = 4
        }
        row.addView(delayEdit)
        refSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("自动", "single_room", "multi_room", "stereo"),
            )
        }
        row.addView(refSpinner)
        box.addView(row)
        authSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("认证:自动", "明文", "Safety加密"),
            )
        }
        box.addView(authSpinner)
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manualIps = EditText(this).apply {
            hint = "手动 IP"
            setTextColor(textOnDark)
            setHintTextColor(accentMuted)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mrow.addView(manualIps)
        mrow.addView(Button(this).apply { text = "加入"; setOnClickListener { addManualRows() } })
        box.addView(mrow)
        if (LocalFilterAcceptanceGate.exposeLocalProbeEntry(BuildConfig.IS_ACCEPTANCE)) {
            localProbeStatus = TextView(this).apply {
                text = "本地过滤验收：未运行"
                setTextColor(accentMuted)
                textSize = 11f
            }
            box.addView(localProbeStatus)
            localProbeBtn = Button(this).apply {
                text = "本地音频过滤验收"
                setOnClickListener {
                    if (LocalFilterProbeService.running.get()) stopLocalProbe() else startLocalFilterAcceptance()
                }
            }
            box.addView(localProbeBtn)
        }
    }

    private fun placeholderArt(): Bitmap {
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(50, 60, 80))
        val p = Paint().apply { color = Color.rgb(80, 100, 130) }
        c.drawRect(0f, 0f, 4f, 8f, p)
        return b
    }

    private fun bindNowPlaying() {
        titleView.text = NowPlayingInfo.displayTitle(nowPlaying)
        artistView.text = NowPlayingInfo.displayArtist(nowPlaying)
        val art = nowPlaying.artwork
        if (art != null) {
            albumArt.setImageBitmap(art)
            bgArt.setImageBitmap(art)
        } else {
            val ph = placeholderArt()
            albumArt.setImageBitmap(ph)
            bgArt.setImageBitmap(ph)
        }
        playBtn.setImageResource(
            if (nowPlaying.isPlaying == true) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
        )
        posView.text = NowPlayingInfo.formatTime(nowPlaying.positionMs)
        durView.text = NowPlayingInfo.formatTime(nowPlaying.durationMs)
        val dur = nowPlaying.durationMs
        val pos = nowPlaying.positionMs
        if (dur != null && dur > 0 && pos != null) {
            progressBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
        } else {
            progressBar.progress = 0
        }
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        suppressRoute = true
        routeState.devices.forEach { d ->
            deviceList.addView(deviceRow(d))
        }
        suppressRoute = false
    }

    private fun deviceRow(d: CastRouteSelection.Device): View {
        val selected = d.selected
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (selected) rowSelected else rowDark, 18f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onDeviceClicked(d) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(
                when (d.kind) {
                    Kind.THIS_DEVICE -> android.R.drawable.ic_menu_mylocation
                    Kind.SPEAKER -> android.R.drawable.ic_lock_silent_mode_off
                    Kind.TV -> android.R.drawable.ic_menu_view
                },
            )
            setColorFilter(if (selected) textOnLight else textOnDark)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) }
        }
        val name = TextView(this).apply {
            text = d.name
            setTextColor(if (selected) textOnLight else textOnDark)
            textSize = 15f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(icon)
        row.addView(name)
        // 协议小标：紧靠名称右侧；本机不标
        val protoBadge = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginStart = dp(6)
                marginEnd = dp(8)
            }
        }
        ProtocolBadge.bind(protoBadge, d.protocol, selected)
        row.addView(protoBadge)
        if (d.isCastSink) {
            val mark = TextView(this).apply {
                text = when {
                    !d.castable -> "○"
                    selected -> "✓"
                    else -> "○"
                }
                setTextColor(
                    when {
                        !d.castable -> Color.GRAY
                        selected -> Color.rgb(40, 100, 200)
                        else -> accentMuted
                    },
                )
                textSize = 16f
                // 离线/未实现：灰圈，不用禁用叉，避免把电视误当成永久禁止
                alpha = if (d.castable) 1f else 0.45f
            }
            row.addView(mark)
        }
        box.addView(row)
        if (d.isCastSink && selected && d.castable) {
            val vol = SeekBar(this).apply {
                max = 100
                progress = speakerVolume[d.id] ?: volumeBar.progress
                setPadding(dp(4), dp(6), dp(4), 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        speakerVolume[d.id] = progress
                        if (CastService.running.get()) setVolume(d.id, progress)
                    }
                })
            }
            box.addView(vol)
        }
        if (!d.castable && d.detail.isNotBlank()) {
            box.addView(
                TextView(this).apply {
                    text = d.detail
                    setTextColor(Color.GRAY)
                    textSize = 11f
                    setPadding(dp(32), dp(2), 0, 0)
                },
            )
        }
        return box
    }

    private fun onDeviceClicked(d: CastRouteSelection.Device) {
        if (suppressRoute) return
        val transition = when (d.kind) {
            Kind.THIS_DEVICE -> CastRouteSelection.selectThisDevice(routeState)
            Kind.SPEAKER, Kind.TV -> CastRouteSelection.setSpeakerChecked(routeState, d.id, !d.selected)
        }
        routeState = transition.state
        renderDevices()
        applyEffects(transition.effects)
    }

    private fun applyEffects(effects: List<Effect>) {
        effects.forEach { e ->
            when (e) {
                is Effect.StopCastToThisDevice -> {
                    stopCast()
                    status.text = "已切回 This device · 本机出声"
                }
                is Effect.StartCast -> startCastTo(e.speakerIds)
                is Effect.AddSpeaker -> {
                    if (CastService.running.get()) {
                        service(CastService.ACTION_ADD) { putExtra(CastService.EXTRA_IP, e.id) }
                        status.text = "加入音箱 ${e.id}"
                    } else {
                        startCastTo(routeState.selectedSpeakerIds)
                    }
                }
                is Effect.RemoveSpeaker -> {
                    service(CastService.ACTION_REMOVE) { putExtra(CastService.EXTRA_IP, e.id) }
                    status.text = "移除音箱 ${e.id}"
                }
                is Effect.RejectNonCastable -> {
                    status.text = "无法投送：${e.reason}"
                }
            }
        }
    }

    private fun startCastTo(speakerIds: Set<String>) {
        if (speakerIds.isEmpty()) return
        val appMode = sourceGroup.checkedRadioButtonId != 1
        if (appMode && selectedPackage.isBlank()) {
            status.text = "请先在设置中选择投送的 App"
            settingsBox.visibility = View.VISIBLE
            // 回滚到 This device，避免勾选了却未投送
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            return
        }
        if (appMode && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "需要 RECORD_AUDIO（播放捕获，非麦克风）"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
            return
        }
        val ips = speakerIds.joinToString(",")
        val ref = (refSpinner.selectedItem as String).let { if (it == "自动") "" else it }
        val authMode = AuthModeResolve.coerceMode(authSpinner.selectedItemPosition)
        val base = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_IPS, ips)
            .putExtra(CastService.EXTRA_DELAY, delayEdit.text.toString().toIntOrNull() ?: 0)
            .putExtra(CastService.EXTRA_PREMUTE, volumeBar.progress)
            .putExtra(CastService.EXTRA_REF, ref)
            .putExtra(CastService.EXTRA_AUTH, authMode)
        speakerIds.forEach { speakerVolume[it] = volumeBar.progress }
        if (appMode) {
            val grantSession = UUID.randomUUID().toString()
            lastGrantSession = grantSession
            pendingStart = base
                .putExtra(CastService.EXTRA_SOURCE, "app")
                .putExtra(CastService.EXTRA_TARGET_PACKAGE, selectedPackage)
                .putExtra(CastService.EXTRA_TARGET_CERT, selectedCert)
                .putExtra(CastService.EXTRA_GRANT_SESSION, grantSession)
            startActivityForResult(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
                42,
            )
            status.text = "等待录屏授权后投送到音箱…"
        } else {
            startForegroundService(base.putExtra(CastService.EXTRA_SOURCE, "silent"))
            status.text = "启动静音测试投送…"
        }
    }

    private fun refreshTargetFromStore() {
        val sel = store.loadSelection()
        if (sel != null) {
            selectedPackage = sel.packageName
            selectedLabel = sel.label
            selectedCert = sel.signingCertSha256
        }
        if (::targetLabel.isInitialized) {
            targetLabel.text = if (selectedPackage.isBlank()) {
                "未选择投送 App"
            } else {
                "投送目标：$selectedLabel\n$selectedPackage"
            }
        }
    }

    private fun openAppPicker() {
        if (CastService.running.get() && CastService.source == "app") {
            stopCast()
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
        }
        startActivityForResult(Intent(this, AppPickerActivity::class.java), 43)
    }

    private fun localIp(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val ip = wm.connectionInfo.ipAddress
        return if (ip == 0) "0.0.0.0" else "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun discover() {
        status.text = "正在发现…"
        Thread {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wm.createMulticastLock("miplay-mdns").apply { setReferenceCounted(false); acquire() }
            val r = try {
                NativeBridge.discover(localIp(), 3000)
            } finally {
                lock.release()
            }
            Log.i(CastService.TAG, "discover: ${r.replace('\n', ';')}")
            main.post {
                val found = mutableListOf<CastRouteSelection.Device>()
                r.lines().filter { it.isNotBlank() && !it.startsWith("ERROR") }.forEach { line ->
                    val p = line.split('\t')
                    val ip = p[0]
                    val name = p.getOrElse(1) { ip }
                    val model = p.getOrElse(2) { "" }
                    // 发现栈为 _mi-connect；电视/音箱均按 MiPlay 可投目标（产品行为）。
                    // 验收脚本勿主动勾选电视；App 本身不得禁止用户点选电视。
                    val label = listOf(name, model).filter { it.isNotBlank() }.joinToString(" · ")
                    val kind = if (CastRouteSelection.looksLikeTv(name, model)) Kind.TV else Kind.SPEAKER
                    found += CastRouteSelection.Device(
                        id = ip,
                        name = label.ifBlank { ip },
                        kind = kind,
                        castable = true,
                        protocol = CastProtocol.MIPLAY,
                    )
                }
                var next = CastRouteSelection.mergeDiscovered(routeState, found)
                pendingIps?.forEach { ip ->
                    if (next.devices.any { it.id == ip && it.castable }) {
                        next = CastRouteSelection.setSpeakerChecked(next, ip, true).state
                    }
                }
                pendingIps = null
                routeState = next
                renderDevices()
                status.text = if (found.isEmpty()) {
                    "未发现设备"
                } else {
                    "发现 ${found.size} 台 · 勾选音箱/电视投送（会话支持多选）"
                }
            }
        }.start()
    }

    private fun addManualRows() {
        val ips = manualIps.text.toString().split(',').map { it.trim() }
            .filter { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }
        if (ips.isEmpty()) return
        val extra = ips.map {
            CastRouteSelection.Device(
                it,
                "手动 $it",
                Kind.SPEAKER,
                castable = true,
                protocol = CastProtocol.MIPLAY,
            )
        }
        val merged = routeState.devices.filter { it.kind != Kind.THIS_DEVICE } + extra
        routeState = CastRouteSelection.mergeDiscovered(routeState, merged.distinctBy { it.id })
        renderDevices()
        manualIps.setText("")
    }

    private fun handleAutomation(i: Intent?) {
        i ?: return
        i.getStringExtra("ips")?.let {
            pendingIps = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.toSet()
        }
        when (i.getStringExtra("source")) {
            "app", "system" -> sourceGroup.check(2)
            "silent" -> sourceGroup.check(1)
        }
        i.getStringExtra("target_package")?.let { pkg ->
            selectedPackage = pkg
            selectedLabel = i.getStringExtra("target_label") ?: pkg
            selectedCert = i.getStringExtra("target_cert").orEmpty()
            refreshTargetFromStore()
            targetLabel.text = "投送目标：$selectedLabel\n$selectedPackage"
        }
        AuthModeResolve.fromIntent(i)?.let { authSpinner.setSelection(it) }
        i.getStringExtra("ref")?.let { r ->
            (0 until refSpinner.count).firstOrNull { refSpinner.getItemAtPosition(it) == r }
                ?.let(refSpinner::setSelection)
        }
        if (i.getBooleanExtra("discover", false)) discover()
        if (i.getBooleanExtra("stop", false)) {
            stopCast()
            routeState = CastRouteSelection.selectThisDevice(routeState).state
            renderDevices()
        }
        if (i.getBooleanExtra("pause", false)) service(CastService.ACTION_PAUSE)
        if (i.getBooleanExtra("resume", false)) service(CastService.ACTION_RESUME)
        i.getStringExtra("add")?.let { ip ->
            val t = CastRouteSelection.setSpeakerChecked(routeState, ip, true)
            routeState = t.state
            renderDevices()
            applyEffects(t.effects)
        }
        i.getStringExtra("remove")?.let { ip ->
            val t = CastRouteSelection.setSpeakerChecked(routeState, ip, false)
            routeState = t.state
            renderDevices()
            applyEffects(t.effects)
        }
        if (i.hasExtra("set_volume")) setVolume(i.getStringExtra("ip").orEmpty(), i.getIntExtra("set_volume", 0))
        if (i.getBooleanExtra("autostart", false)) {
            val want = pendingIps
            if (!want.isNullOrEmpty()) {
                discover()
                main.postDelayed({
                    var s = routeState
                    want.forEach { ip ->
                        if (s.devices.none { it.id == ip }) {
                            s = CastRouteSelection.mergeDiscovered(
                                s,
                                s.devices.filter { it.kind != Kind.THIS_DEVICE } +
                                    CastRouteSelection.Device(
                                        ip, ip, Kind.SPEAKER, true,
                                        protocol = CastProtocol.MIPLAY,
                                    ),
                            )
                        }
                        val t = CastRouteSelection.setSpeakerChecked(s, ip, true)
                        s = t.state
                        routeState = s
                        renderDevices()
                        applyEffects(t.effects)
                    }
                }, 3500)
            }
        }
    }

    private fun startLocalFilterAcceptance() {
        if (!LocalFilterAcceptanceGate.exposeLocalProbeEntry(BuildConfig.IS_ACCEPTANCE)) return
        val gate = LocalFilterAcceptanceGate.validateStart(
            LocalFilterAcceptanceGate.Mode.LOCAL_PCM_PROBE,
            selectedPackage.isNotBlank(),
            0,
        )
        if (gate is LocalFilterAcceptanceGate.StartDecision.Block) {
            status.text = gate.messageZh
            return
        }
        if (CastService.running.get()) {
            status.text = "请先停止音箱投送"
            return
        }
        val grantSession = UUID.randomUUID().toString()
        lastGrantSession = grantSession
        pendingLocalProbe = Intent(this, LocalFilterProbeService::class.java)
            .setAction(LocalFilterProbeService.ACTION_START)
            .putExtra(LocalFilterProbeService.EXTRA_TARGET_PACKAGE, selectedPackage)
            .putExtra(LocalFilterProbeService.EXTRA_TARGET_CERT, selectedCert)
            .putExtra(LocalFilterProbeService.EXTRA_GRANT_SESSION, grantSession)
        startActivityForResult(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
            44,
        )
        status.text = "本地过滤验收：等待投影授权…"
    }

    private fun stopLocalProbe() {
        startService(Intent(this, LocalFilterProbeService::class.java).setAction(LocalFilterProbeService.ACTION_STOP))
        localProbeBtn?.text = "本地音频过滤验收"
        status.text = "已停止本地探针"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            42 -> {
                val p = pendingStart ?: return
                pendingStart = null
                if (resultCode != RESULT_OK || data == null) {
                    status.text = "录屏授权被拒绝 · 已回 This device"
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    renderDevices()
                    lastGrantSession = null
                    return
                }
                startForegroundService(
                    p.putExtra(CastService.EXTRA_PROJ_CODE, resultCode)
                        .putExtra(CastService.EXTRA_PROJ_DATA, data),
                )
                status.text = "正在投送到音箱…"
            }
            43 -> {
                if (resultCode != RESULT_OK || data == null) return
                selectedPackage = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE).orEmpty()
                selectedLabel = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty()
                selectedCert = data.getStringExtra(AppPickerActivity.EXTRA_CERT).orEmpty()
                store.saveSelection(
                    TargetAppSelection(
                        packageName = selectedPackage,
                        label = selectedLabel,
                        signingCertSha256 = selectedCert,
                    ),
                )
                lastGrantSession = null
                refreshTargetFromStore()
                sourceGroup.check(2)
                status.text = "已选择 $selectedLabel"
            }
            44 -> {
                val p = pendingLocalProbe ?: return
                pendingLocalProbe = null
                if (resultCode != RESULT_OK || data == null) {
                    status.text = "本地过滤验收：授权被拒绝"
                    return
                }
                startForegroundService(
                    p.putExtra(LocalFilterProbeService.EXTRA_PROJ_CODE, resultCode)
                        .putExtra(LocalFilterProbeService.EXTRA_PROJ_DATA, data),
                )
                localProbeBtn?.text = "停止本地过滤验收"
                status.text = "本地过滤验收已启动"
            }
        }
    }

    private fun service(action: String, extra: Intent.() -> Unit = {}) {
        startService(Intent(this, CastService::class.java).setAction(action).apply(extra))
    }

    private fun stopCast() = service(CastService.ACTION_STOP)

    private fun setVolume(ip: String, v: Int) = service(CastService.ACTION_VOLUME) {
        putExtra(CastService.EXTRA_VOLUME, v)
        if (ip.isNotEmpty()) putExtra(CastService.EXTRA_IP, ip)
    }

    private fun refreshLoop() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (CastService.running.get()) {
                    status.text = CastService.lastEvent.ifBlank { status.text }
                } else if (CastService.lastFailMessage.isNotBlank() && routeState.thisDeviceSelected) {
                    // keep failure visible briefly
                }
                muteLine.text = CastService.localMuteStatusZh
                runCatching {
                    val o = JSONObject(CastService.lastStats.ifBlank { "{}" })
                    latencyLine.text = if (CastService.running.get()) {
                        LatencyDisplay.formatCompactLine(o)
                    } else {
                        "延迟 —"
                    }
                }
                if (BuildConfig.IS_ACCEPTANCE && LocalFilterProbeService.running.get()) {
                    localProbeStatus?.text = LocalFilterProbeService.lastProbe.ifBlank {
                        LocalFilterProbeService.lastEvent
                    }
                }
                // 若投送已停但 UI 仍勾选音箱（异常断线），保持勾选；用户可点 This device
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }
}
