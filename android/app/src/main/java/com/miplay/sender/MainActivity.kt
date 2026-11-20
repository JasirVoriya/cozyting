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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.miplay.sender.compat.AcceptanceAudioDefaults
import com.miplay.sender.compat.BlurCompat
import com.miplay.sender.compat.CaptureSelectionLayers
import com.miplay.sender.compat.NotificationPermissionCompat
import com.miplay.sender.compat.WindowInsetsApplyCompat
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import android.widget.HorizontalScrollView
import com.miplay.sender.cast.PrefsDiscoveredDeviceStore
import com.miplay.sender.cast.ProtocolBadge
import com.miplay.sender.capture.AndroidLaunchableApps
import com.miplay.sender.capture.AndroidPackageIdentityLookup
import com.miplay.sender.capture.CaptureTargetResolver
import com.miplay.sender.capture.CaptureTargetResult
import com.miplay.sender.capture.LaunchableApp
import com.miplay.sender.capture.LaunchableAppCatalog
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
    private lateinit var deviceStore: PrefsDiscoveredDeviceStore
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
    private lateinit var muteLine: TextView
    private lateinit var mainTargetIcon: ImageView
    private lateinit var mainTargetTitle: TextView
    private lateinit var mainTargetSubtitle: TextView
    private lateinit var quickSwitchRow: LinearLayout
    private var cachedLaunchableApps: List<LaunchableApp> = emptyList()
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
    private val latencyIndicators = linkedMapOf<String, LatencyIndicatorViews>()
    private var showAllDiscovered: Boolean = false
    private var keepLocalOutput: Boolean = false
    private var castRequested: Boolean = false

    private data class LatencyIndicatorViews(
        val container: LinearLayout,
        val value: TextView,
        val spinner: ProgressBar,
        val unit: TextView,
    )

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
        deviceStore = PrefsDiscoveredDeviceStore(this)
        val cachedDevices = deviceStore.loadDevices()
        routeState = CastRouteSelection.initial(
            listOf(CastRouteSelection.Device("this", "This device", Kind.THIS_DEVICE, true)) + cachedDevices,
        )
        buildUi()
        refreshTargetFromStore()
        loadLaunchableAppsAsync()
        val need = NotificationPermissionCompat.runtimePermissionsForColdStart()
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
            BlurCompat.applyBackdrop(this)
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
            progress = AcceptanceAudioDefaults.speakerPremutePercent(BuildConfig.IS_ACCEPTANCE)
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

        // —— 投送音源（主面板直接展示与快捷切换） ——
        val sourceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(rowDark, 18f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val sourceHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { openAppPicker() }
        }
        mainTargetIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.sym_def_app_icon)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
        }
        val sourceMeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mainTargetTitle = TextView(this).apply {
            text = "投送音源：未选择"
            setTextColor(textOnDark)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        mainTargetSubtitle = TextView(this).apply {
            text = "点击选择需要投送的音乐或音频 App"
            setTextColor(accentMuted)
            textSize = 11f
            maxLines = 1
        }
        sourceMeta.addView(mainTargetTitle)
        sourceMeta.addView(mainTargetSubtitle)
        val changeAppBtn = TextView(this).apply {
            text = "更多 App ▸"
            setTextColor(textOnDark)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { openAppPicker() }
        }
        sourceHeader.addView(mainTargetIcon)
        sourceHeader.addView(sourceMeta)
        sourceHeader.addView(changeAppBtn)
        sourceCard.addView(sourceHeader)

        quickSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        val quickScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(quickSwitchRow)
        }
        sourceCard.addView(quickScroll)
        glassCard.addView(sourceCard)

        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        glassCard.addView(deviceList)

        status = TextView(this).apply {
            setTextColor(accentMuted)
            textSize = 12f
            text = "本机出声 · 勾选音箱开始投送"
            setPadding(0, dp(10), 0, dp(6))
        }
        glassCard.addView(status)

        // —— 高级与调试选项（折叠） ——
        val settingsToggle = TextView(this).apply {
            text = "高级与调试选项 ▸"
            setTextColor(accentMuted)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener {
                settingsBox.visibility =
                    if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (settingsBox.visibility == View.VISIBLE) {
                    "高级与调试选项 ▾"
                } else {
                    "高级与调试选项 ▸"
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
        WindowInsetsApplyCompat.applySystemBarsPadding(scroll)
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
        box.addView(
            CheckBox(this).apply {
                text = "投送时手机静音（静音全局 STREAM_MUSIC；取消勾选则保留手机本机出声）"
                setTextColor(textOnDark)
                isChecked = !keepLocalOutput
                setOnCheckedChangeListener { _, checked ->
                    keepLocalOutput = !checked
                }
            },
        )
        muteLine = TextView(this).apply {
            setTextColor(Color.argb(180, 185, 195, 208))
            textSize = 11f
            text = CastService.localMuteStatusZh
            setPadding(dp(4), dp(2), 0, dp(4))
        }
        box.addView(muteLine)
        box.addView(
            TextView(this).apply {
                text = "说明：Android 普通权限仅支持按 App UID 捕获音频，无公开 API 单独静音第三方 App 本机输出；默认开启投送时全局 STREAM_MUSIC 静音（切歌保持静音，但会影响同流其它视频 App）。"
                setTextColor(accentMuted)
                textSize = 11f
                setPadding(dp(4), 0, 0, dp(6))
            },
        )
        box.addView(
            CheckBox(this).apply {
                text = "显示全部发现设备（含未声明 MiPlay 节点）"
                setTextColor(textOnDark)
                isChecked = showAllDiscovered
                setOnCheckedChangeListener { _, checked ->
                    showAllDiscovered = checked
                    renderDevices()
                }
            },
        )
        box.addView(
            TextView(this).apply {
                text = CaptureSelectionLayers.settingsHintZh()
                setTextColor(accentMuted)
                textSize = 11f
            },
        )
        if (BuildConfig.IS_ACCEPTANCE) {
            box.addView(
                TextView(this).apply {
                    text = "验收默认音箱音量=0（静音测试）；停止投送后不恢复本机 STREAM_MUSIC 索引。"
                    setTextColor(accentMuted)
                    textSize = 11f
                },
            )
        }
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
        latencyIndicators.clear()
        suppressRoute = true
        val visible = CastRouteSelection.filterVisibleDevices(routeState.devices, showAllDiscovered)
        visible.forEach { d ->
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
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(icon)
        row.addView(name)
        if (d.isCastSink) {
            val value = TextView(this).apply {
                setTextColor(if (selected) textOnLight else accentMuted)
                textSize = 11f
            }
            val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    if (selected) textOnLight else accentMuted,
                )
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(3) }
            }
            val unit = TextView(this).apply {
                text = "ms"
                setTextColor(if (selected) textOnLight else accentMuted)
                textSize = 11f
            }
            val indicator = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                addView(spinner)
                addView(unit)
                addView(value)
            }
            latencyIndicators[d.id] = LatencyIndicatorViews(indicator, value, spinner, unit)
            row.addView(indicator)
        }
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
                        if (CastService.running.get() || castRequested) setVolume(d.id, progress)
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
                    castRequested = false
                    pendingStart = null
                    stopCast()
                    updateLatencyIndicators(null)
                    status.text = "已切回 This device · 本机出声"
                }
                is Effect.StartCast -> startCastTo(e.speakerIds)
                is Effect.AddSpeaker -> {
                    if (CastService.running.get() || castRequested) {
                        CastService.primeConnectingReceivers(setOf(e.id))
                        updateLatencyIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
                        service(CastService.ACTION_ADD) { putExtra(CastService.EXTRA_IP, e.id) }
                        status.text = "加入音箱 ${e.id}"
                    } else if (pendingStart != null) {
                        val ips = routeState.selectedSpeakerIds.joinToString(",")
                        pendingStart?.putExtra(CastService.EXTRA_IPS, ips)
                        status.text = "已加入待投送列表：${e.id}"
                    } else {
                        startCastTo(routeState.selectedSpeakerIds)
                    }
                }
                is Effect.RemoveSpeaker -> {
                    if (pendingStart != null && !CastService.running.get() && !castRequested) {
                        val ips = routeState.selectedSpeakerIds.joinToString(",")
                        pendingStart?.putExtra(CastService.EXTRA_IPS, ips)
                    } else {
                        CastService.lastStats = CastService.removeReceiverFromStatsJson(CastService.lastStats, e.id)
                        updateLatencyIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
                        service(CastService.ACTION_REMOVE) { putExtra(CastService.EXTRA_IP, e.id) }
                    }
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
            .putExtra(
                CastService.EXTRA_LOCAL_MUTE_POLICY,
                if (keepLocalOutput) "keep_local" else "global_mute",
            )
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
            status.text =
                "等待系统捕获授权…（UID 仍按已选 App=$selectedPackage；系统框选项不会改写 UID）"
        } else {
            castRequested = true
            CastService.primeConnectingReceivers(speakerIds)
            updateLatencyIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
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
                "投送目标：$selectedLabel\n$selectedPackage\n${CaptureSelectionLayers.settingsHintZh()}"
            }
        }
        if (::mainTargetTitle.isInitialized) {
            if (selectedPackage.isBlank()) {
                mainTargetTitle.text = "投送音源：未选择"
                mainTargetSubtitle.text = "点击选择需要投送的音乐或音频 App"
                mainTargetIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            } else {
                mainTargetTitle.text = "投送音源：$selectedLabel"
                mainTargetSubtitle.text = selectedPackage
                val icon = AndroidLaunchableApps.loadIcon(this, selectedPackage)
                if (icon != null) {
                    mainTargetIcon.setImageDrawable(icon)
                } else {
                    mainTargetIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }
        renderQuickSwitchRow()
    }

    private fun loadLaunchableAppsAsync() {
        Thread {
            val apps = AndroidLaunchableApps.list(this)
            main.post {
                cachedLaunchableApps = apps
                renderQuickSwitchRow()
            }
        }.start()
    }

    private fun renderQuickSwitchRow() {
        if (!::quickSwitchRow.isInitialized) return
        quickSwitchRow.removeAllViews()
        val recentPkgs = store.loadRecentSelections().map { it.packageName }
        val candidates = LaunchableAppCatalog.quickSwitchCandidates(
            apps = cachedLaunchableApps,
            recentPackages = recentPkgs,
            currentPackage = selectedPackage.takeIf { it.isNotBlank() },
            limit = 4,
            selfPackage = packageName,
        )
        if (candidates.isEmpty()) {
            quickSwitchRow.visibility = View.GONE
            return
        }
        quickSwitchRow.visibility = View.VISIBLE
        candidates.forEach { app ->
            val isCurrent = app.packageName == selectedPackage
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(
                    if (isCurrent) rowSelected else Color.argb(180, 56, 62, 74),
                    14f,
                )
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                isClickable = true
                isFocusable = true
                setOnClickListener { quickSelectApp(app) }
            }
            val icon = ImageView(this).apply {
                val drawable = AndroidLaunchableApps.loadIcon(this@MainActivity, app.packageName)
                if (drawable != null) {
                    setImageDrawable(drawable)
                } else {
                    setImageResource(android.R.drawable.sym_def_app_icon)
                }
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) }
            }
            val label = TextView(this).apply {
                text = if (isCurrent) "${app.label} ✓" else app.label
                textSize = 12f
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isCurrent) textOnLight else textOnDark)
            }
            chip.addView(icon)
            chip.addView(label)
            quickSwitchRow.addView(chip)
        }
    }

    private fun quickSelectApp(app: LaunchableApp) {
        if (app.packageName == selectedPackage && sourceGroup.checkedRadioButtonId == 2) {
            status.text = "当前音源已是 ${app.label}"
            return
        }
        Thread {
            val resolver = CaptureTargetResolver(AndroidPackageIdentityLookup(this))
            when (val r = resolver.resolveForCapture(app.packageName)) {
                is CaptureTargetResult.Fail -> main.post {
                    status.text = r.messageZh
                }
                is CaptureTargetResult.Ok -> {
                    store.saveSelection(
                        TargetAppSelection(
                            packageName = r.packageName,
                            label = app.label,
                            signingCertSha256 = r.signingCertSha256,
                        ),
                    )
                    main.post {
                        selectedPackage = r.packageName
                        selectedLabel = app.label
                        selectedCert = r.signingCertSha256
                        lastGrantSession = null
                        sourceGroup.check(2)
                        refreshTargetFromStore()
                        if (CastService.running.get() && CastService.source == "app") {
                            stopCast()
                            routeState = CastRouteSelection.selectThisDevice(routeState).state
                            renderDevices()
                            status.text = "已切换音源为 ${app.label} · 请重新勾选音箱开始投送"
                        } else {
                            status.text = "已选择音源：${app.label}"
                        }
                    }
                }
            }
        }.start()
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
                r.lines().mapNotNull(CastRouteSelection::parseDiscoveredLine).forEach { row ->
                    Log.i(CastService.TAG, row.logLine)
                    found += row.device
                }
                val mergedDevices = CastRouteSelection.mergeCachedAndDiscovered(
                    cached = deviceStore.loadDevices(),
                    discovered = found,
                )
                deviceStore.saveDevices(mergedDevices)
                var next = CastRouteSelection.mergeDiscovered(routeState, mergedDevices)
                pendingIps?.forEach { ip ->
                    if (next.devices.any { it.id == ip && it.castable }) {
                        next = CastRouteSelection.setSpeakerChecked(next, ip, true).state
                    }
                }
                pendingIps = null
                routeState = next
                renderDevices()
                val castableCount = found.count { it.castable }
                val hiddenCount = found.size - castableCount
                status.text = if (found.isEmpty()) {
                    "未发现设备"
                } else if (hiddenCount > 0 && !showAllDiscovered) {
                    "发现 $castableCount 台可投设备（已隐藏 $hiddenCount 台未声明 MiPlay 节点）"
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
        val merged = (routeState.devices.filter { it.kind != Kind.THIS_DEVICE } + extra).distinctBy { it.id }
        deviceStore.saveDevices(merged)
        routeState = CastRouteSelection.mergeDiscovered(routeState, merged)
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
            castRequested = false
            pendingStart = null
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
                    castRequested = false
                    status.text = "录屏授权被拒绝 · 已回 This device"
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    renderDevices()
                    lastGrantSession = null
                    return
                }
                val currentIps = routeState.selectedSpeakerIds
                if (currentIps.isEmpty()) {
                    castRequested = false
                    status.text = "未选择投送音箱 · 已回 This device"
                    routeState = CastRouteSelection.selectThisDevice(routeState).state
                    renderDevices()
                    lastGrantSession = null
                    return
                }
                p.putExtra(CastService.EXTRA_IPS, currentIps.joinToString(","))
                castRequested = true
                CastService.primeConnectingReceivers(currentIps)
                updateLatencyIndicators(runCatching { JSONObject(CastService.lastStats) }.getOrNull())
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
                    castRequested = false
                    status.text = CastService.lastEvent.ifBlank { status.text }
                } else if (CastService.lastFailMessage.isNotBlank()) {
                    castRequested = false
                }
                muteLine.text = CastService.localMuteStatusZh
                val stats = runCatching { JSONObject(CastService.lastStats) }.getOrNull()
                updateLatencyIndicators(stats)
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

    private fun updateLatencyIndicators(stats: JSONObject?) {
        val failed = CastService.lastFailMessage.isNotBlank()
        val isCasting = CastService.running.get() || castRequested
        latencyIndicators.forEach { (deviceId, views) ->
            val device = routeState.devices.firstOrNull { it.id == deviceId }
            when (
                val indicator = LatencyDisplay.receiverIndicator(
                    stats = stats,
                    deviceId = deviceId,
                    selected = device?.selected == true,
                    casting = isCasting,
                    failed = failed,
                )
            ) {
                LatencyDisplay.ReceiverIndicator.Hidden -> views.container.visibility = View.GONE
                LatencyDisplay.ReceiverIndicator.Loading -> {
                    views.container.visibility = View.VISIBLE
                    views.spinner.visibility = View.VISIBLE
                    views.unit.visibility = View.VISIBLE
                    views.value.text = ""
                }
                is LatencyDisplay.ReceiverIndicator.Value -> {
                    views.container.visibility = View.VISIBLE
                    views.spinner.visibility = View.GONE
                    views.unit.visibility = View.GONE
                    views.value.text = LatencyDisplay.formatReceiverValue(indicator.milliseconds)
                }
            }
        }
    }
}
