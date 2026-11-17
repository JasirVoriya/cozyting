package com.miplay.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject

/**
 * UI（代码构建，无 XML）：
 *  - 发现音箱列表（MiPlay 设备排前面），每行：勾选（多选分组）+ 名称/型号/IP + 状态 + 本台音量滑块；
 *  - 投送中勾选新音箱 = 中途加入，取消勾选 = 移出组；拖本台滑块 = 只改这一台的音量；
 *  - 音源：测试静音 / 系统音频（录屏授权）；延迟默认自动（5 GHz 800 ms，其他 1000 ms）；
 *  - 开始/停止、暂停/恢复；通知栏也有暂停/恢复/停止。断线重连由 core 自动完成（15 s 心跳超时）。
 *
 * adb 自动化（无需点击）：
 *   adb shell am start -n com.miplay.sender/.MainActivity --es ips 192.168.31.234,192.168.31.34 --es source silent --ei secs 20 --ez autostart true
 *   --es auth auto|legacy|safety（默认 auto：先明文，失败回退 Safety 加密）
 *   其他 extra：--ez discover true | --ez stop true | --ez pause true | --ez resume true | --ei set_volume N [--es ip X] | --es add IP | --es remove IP
 *   source=system 时会弹系统录屏授权（需用户点“立即开始”）。
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var statsView: TextView
    private lateinit var list: LinearLayout
    private lateinit var manualIps: EditText
    private lateinit var sourceGroup: RadioGroup
    private lateinit var delayEdit: EditText
    private lateinit var refSpinner: Spinner
    private lateinit var authSpinner: Spinner
    private lateinit var volume: SeekBar
    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button

    private class Row(val ip: String, val check: CheckBox, val state: TextView, val vol: SeekBar, val volLabel: TextView)
    private val rows = linkedMapOf<String, Row>()
    private var pendingStart: Intent? = null
    private var suppressCheck = false

    private lateinit var powerView: TextView
    private lateinit var powerBtn: Button

    private fun powerExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refreshPower() {
        val ok = powerExempt()
        powerView.text = if (ok) "后台/熄屏：已豁免电池优化" else "后台/熄屏：未豁免，熄屏或省电时可能被系统冻结而断流"
        powerBtn.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestPowerExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::powerView.isInitialized) refreshPower()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide() // 页面里已有标题；edge-to-edge 下标题栏会盖住内容
        buildUi()
        val need = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        refreshLoop()
        if (intent?.extras == null) discover()
        handleAutomation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomation(intent)
    }

    private fun handleAutomation(i: Intent?) {
        i ?: return
        i.getStringExtra("ips")?.let { applyIpsExtra(it) }
        when (i.getStringExtra("source")) { "system" -> sourceGroup.check(2); "silent" -> sourceGroup.check(1) }
        when (i.getStringExtra("auth")) { "legacy" -> authSpinner.setSelection(1); "safety" -> authSpinner.setSelection(2); "auto" -> authSpinner.setSelection(0) }
        i.getStringExtra("ref")?.let { r -> (0 until refSpinner.count).firstOrNull { refSpinner.getItemAtPosition(it) == r }?.let(refSpinner::setSelection) }
        if (i.getBooleanExtra("discover", false)) discover()
        if (i.getBooleanExtra("stop", false)) stopCast()
        if (i.getBooleanExtra("pause", false)) service(CastService.ACTION_PAUSE)
        if (i.getBooleanExtra("resume", false)) service(CastService.ACTION_RESUME)
        i.getStringExtra("add")?.let { service(CastService.ACTION_ADD) { putExtra(CastService.EXTRA_IP, it) } }
        i.getStringExtra("remove")?.let { service(CastService.ACTION_REMOVE) { putExtra(CastService.EXTRA_IP, it) } }
        if (i.hasExtra("set_volume")) setVolume(i.getStringExtra("ip").orEmpty(), i.getIntExtra("set_volume", 0))
        if (i.getBooleanExtra("autostart", false)) startCast(i.getIntExtra("secs", 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val pad = dp(12)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "MiPlay 发送端（局域网直连小米音箱）"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        status = TextView(this).apply { text = "空闲"; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        // 省电豁免：未豁免时 HyperOS 熄屏/Doze 会冻结本进程 → 音箱断流（实测 2026-11-17 12:16）。只开系统确认框，不替用户点。
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        powerView = TextView(this).apply { textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        powerBtn = Button(this).apply { text = "允许后台运行"; setOnClickListener { requestPowerExemption() } }
        prow.addView(powerView); prow.addView(powerBtn)
        root.addView(prow)
        refreshPower()
        root.addView(Button(this).apply { text = "重新发现音箱"; setOnClickListener { discover() } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manualIps = EditText(this).apply {
            hint = "手动添加 IP（逗号分隔）"; inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mrow.addView(manualIps)
        mrow.addView(Button(this).apply { text = "加入列表"; setOnClickListener { addManualRows() } })
        root.addView(mrow)
        root.addView(TextView(this).apply { text = "音源"; setTypeface(null, Typeface.BOLD) })
        sourceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(context).apply { id = 1; text = "测试静音" })
            addView(RadioButton(context).apply { id = 2; text = "系统音频（录屏授权）" })
            check(1)
        }
        root.addView(sourceGroup)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "延迟ms " })
        delayEdit = EditText(this).apply { hint = "自动(${CastService.autoDelayMs(this@MainActivity)})"; inputType = InputType.TYPE_CLASS_NUMBER; minEms = 5 }
        row.addView(delayEdit)
        refSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("自动", "single_room", "multi_room", "stereo"))
        }
        row.addView(refSpinner)
        authSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("认证:自动", "明文", "Safety加密"))
        }
        root.addView(row)
        root.addView(authSpinner)
        val vrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val vlabel = TextView(this).apply { text = "开始时音量 0" }
        volume = SeekBar(this).apply {
            max = 100; progress = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { vlabel.text = "开始时音量 $p" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (CastService.running.get()) { setVolume("", progress); rows.values.forEach { it.vol.progress = progress } }
                }
            })
        }
        vrow.addView(vlabel); vrow.addView(volume)
        root.addView(vrow)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply { text = "开始投送"; setOnClickListener { if (CastService.running.get()) stopCast() else startCast(0) } }
        pauseBtn = Button(this).apply {
            text = "暂停"; isEnabled = false
            setOnClickListener { service(if (CastService.paused) CastService.ACTION_RESUME else CastService.ACTION_PAUSE) }
        }
        btns.addView(startBtn); btns.addView(pauseBtn)
        root.addView(btns)
        statsView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 9f }
        root.addView(statsView)
        val scroll = ScrollView(this).apply { addView(root) }
        // Android 15+ 默认 edge-to-edge：按系统栏/标题栏 inset 加内边距，避免顶部内容被盖住
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        scroll.fitsSystemWindows = false
        setContentView(scroll)
    }

    private fun addRow(ip: String, label: String, checked: Boolean) {
        if (rows.containsKey(ip)) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cb = CheckBox(this).apply { text = label; isChecked = checked; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val state = TextView(this).apply { text = ""; textSize = 12f }
        top.addView(cb); top.addView(state)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(32), 0, 0, 0) }
        val vl = TextView(this).apply { text = "音量 0"; textSize = 12f; minEms = 4 }
        val sb = SeekBar(this).apply {
            max = 100; progress = 0; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { vl.text = "音量 $p" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { if (CastService.running.get()) setVolume(ip, progress) }
            })
        }
        bottom.addView(vl); bottom.addView(sb)
        box.addView(top); box.addView(bottom)
        list.addView(box)
        cb.setOnCheckedChangeListener { _, on ->
            if (suppressCheck || !CastService.running.get()) return@setOnCheckedChangeListener
            // 投送中：勾选 = 中途加入（复用组 TIME_OFFSET），取消 = 移出
            service(if (on) CastService.ACTION_ADD else CastService.ACTION_REMOVE) { putExtra(CastService.EXTRA_IP, ip) }
        }
        rows[ip] = Row(ip, cb, state, sb, vl)
    }

    /** adb 的 ips extra 是“精确选择”：列表里的勾上（没有就加一行），其余全部取消勾选。 */
    private fun applyIpsExtra(v: String) {
        val want = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        suppressCheck = true
        want.forEach { addRow(it, "手动  $it", true) }
        rows.values.forEach { it.check.isChecked = it.ip in want }
        suppressCheck = false
        manualIps.setText("")
        pendingIps = want
    }
    private var pendingIps: Set<String>? = null

    private fun addManualRows() {
        manualIps.text.toString().split(',').map { it.trim() }.filter { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }
            .forEach { addRow(it, "手动  $it", true) }
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
            val r = try { NativeBridge.discover(localIp(), 3000) } finally { lock.release() }
            Log.i(CastService.TAG, "discover: ${r.replace('\n', ';')}")
            main.post {
                var n = 0
                r.lines().filter { it.isNotBlank() && !it.startsWith("ERROR") }.forEach { line ->
                    val p = line.split('\t')
                    val miplay = p.getOrElse(3) { "1" } == "1"
                    val sel = pendingIps?.let { p[0] in it } ?: (miplay && !CastService.running.get())
                    if (rows.containsKey(p[0])) rows[p[0]]?.check?.text = "${p.getOrElse(1) { "?" }}  ${p.getOrElse(2) { "" }}  ${p[0]}"
                    addRow(p[0], "${p.getOrElse(1) { "?" }}  ${p.getOrElse(2) { "" }}  ${p[0]}${if (miplay) "" else "（非 MiPlay）"}", sel)
                    n++
                }
                status.text = if (n == 0) "未发现（$r）" else "发现 $n 台"
            }
        }.start()
    }

    private fun selectedIps(): String {
        addManualRows()
        return rows.values.filter { it.check.isChecked }.joinToString(",") { it.ip }
    }

    private fun startCast(secs: Int) {
        val ips = selectedIps()
        if (ips.isEmpty()) { status.text = "请勾选音箱"; return }
        val ref = (refSpinner.selectedItem as String).let { if (it == "自动") "" else it }
        val base = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_IPS, ips)
            .putExtra(CastService.EXTRA_DELAY, delayEdit.text.toString().toIntOrNull() ?: 0)
            .putExtra(CastService.EXTRA_PREMUTE, volume.progress) // 推流前和 PLAY 后都设为该值（默认 0 = 静音）
            .putExtra(CastService.EXTRA_REF, ref)
            .putExtra(CastService.EXTRA_SECS, secs)
            .putExtra(CastService.EXTRA_AUTH, authSpinner.selectedItemPosition)
        rows.values.forEach { it.vol.progress = volume.progress }
        if (sourceGroup.checkedRadioButtonId == 2) {
            pendingStart = base.putExtra(CastService.EXTRA_SOURCE, "system")
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 42)
            status.text = "等待录屏授权…"
        } else {
            startForegroundService(base.putExtra(CastService.EXTRA_SOURCE, "silent"))
            status.text = "启动中（静音测试）…"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        val p = pendingStart ?: return
        pendingStart = null
        if (resultCode != RESULT_OK || data == null) { status.text = "录屏授权被拒绝"; Log.i(CastService.TAG, "projection denied"); return }
        Log.i(CastService.TAG, "projection granted")
        startForegroundService(p.putExtra(CastService.EXTRA_PROJ_CODE, resultCode).putExtra(CastService.EXTRA_PROJ_DATA, data))
    }

    private fun service(action: String, extra: Intent.() -> Unit = {}) {
        startService(Intent(this, CastService::class.java).setAction(action).apply(extra))
    }

    private fun stopCast() = service(CastService.ACTION_STOP)

    private fun setVolume(ip: String, v: Int) = service(CastService.ACTION_VOLUME) {
        putExtra(CastService.EXTRA_VOLUME, v); if (ip.isNotEmpty()) putExtra(CastService.EXTRA_IP, ip)
    }

    private fun refreshLoop() {
        main.postDelayed(object : Runnable {
            override fun run() {
                val on = CastService.running.get()
                startBtn.text = if (on) "停止投送" else "开始投送"
                pauseBtn.isEnabled = on
                pauseBtn.text = if (CastService.paused) "恢复" else "暂停"
                if (on) status.text = CastService.lastEvent
                runCatching {
                    val o = JSONObject(CastService.lastStats)
                    val rs = o.getJSONArray("receivers")
                    val seen = mutableSetOf<String>()
                    for (i in 0 until rs.length()) {
                        val r = rs.getJSONObject(i)
                        val ip = r.getString("ip"); seen += ip
                        val row = rows[ip] ?: continue
                        val st = r.optInt("state", -1)
                        row.state.text = buildString {
                            append(when { !r.isNull("error") -> "断开"; st == 2 -> "播放中"; st == 3 -> "已暂停"; else -> r.optString("phase").take(10) })
                            if (r.optInt("reconnects") > 0) append(" 重连${r.optInt("reconnects")}")
                            if (!r.isNull("volume_now")) append(" 音量${r.optInt("volume_now")}")
                        }
                    }
                    if (on) rows.values.filter { it.ip !in seen }.forEach { it.state.text = if (it.check.isChecked) "连接中/重连中" else "" }
                    else rows.values.forEach { it.state.text = "" }
                }
                statsView.text = CastService.lastStats.replace(",\"", ",\n\"")
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }
}
