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
import com.miplay.sender.capture.PrefsTargetAppStore
import com.miplay.sender.capture.ProjectionSessionPolicy
import org.json.JSONObject
import java.util.UUID

/**
 * UI（代码构建）：
 *  - 发现音箱列表，多选分组、中途加入/移出、单台音量；
 *  - 音源：测试静音 / 选定 App 音频（严格 UID 过滤，每次重新 MediaProjection 授权）；
 *  - 开始/停止、暂停/恢复；通知栏同步。断线重连由 core 完成。
 *
 * adb：
 *   --es source silent|app --es target_package com.example --ez autostart true
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
    private lateinit var targetLabel: TextView
    private lateinit var pickAppBtn: Button
    private lateinit var clearAppBtn: Button
    private lateinit var captureHint: TextView

    private class Row(val ip: String, val check: CheckBox, val state: TextView, val vol: SeekBar, val volLabel: TextView)
    private val rows = linkedMapOf<String, Row>()
    private var pendingStart: Intent? = null
    private var suppressCheck = false
    private var selectedPackage: String = ""
    private var selectedLabel: String = ""
    private var selectedCert: String = ""
    private var lastGrantSession: String? = null

    private lateinit var powerView: TextView
    private lateinit var powerBtn: Button
    private lateinit var store: PrefsTargetAppStore

    private fun powerExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refreshPower() {
        val ok = powerExempt()
        powerView.text = if (ok) {
            "后台/熄屏：已请求电池优化豁免（不等于全品牌保活保证）"
        } else {
            "后台/熄屏：未豁免。部分系统（如 HyperOS Greeze）仍可能冻结进程；省电入口≠保活修复"
        }
        powerBtn.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestPowerExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::powerView.isInitialized) refreshPower()
        if (::store.isInitialized) refreshTargetFromStore()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        store = PrefsTargetAppStore(this)
        buildUi()
        refreshTargetFromStore()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            when (p) {
                Manifest.permission.RECORD_AUDIO -> if (!ok) {
                    status.text = "未授予 RECORD_AUDIO：无法进行播放捕获（不采集麦克风）"
                }
                Manifest.permission.POST_NOTIFICATIONS -> if (!ok) {
                    status.text = "未授予通知权限：投送仍可能进行，但前台服务通知不可见，请从本页停止"
                }
            }
        }
    }

    private fun handleAutomation(i: Intent?) {
        i ?: return
        i.getStringExtra("ips")?.let { applyIpsExtra(it) }
        when (i.getStringExtra("source")) {
            "app", "system" -> sourceGroup.check(2)
            "silent" -> sourceGroup.check(1)
        }
        i.getStringExtra("target_package")?.let { pkg ->
            selectedPackage = pkg
            selectedLabel = i.getStringExtra("target_label") ?: pkg
            selectedCert = i.getStringExtra("target_cert").orEmpty()
            refreshTargetUi()
        }
        when (i.getStringExtra("auth")) {
            "legacy" -> authSpinner.setSelection(1)
            "safety" -> authSpinner.setSelection(2)
            "auto" -> authSpinner.setSelection(0)
        }
        i.getStringExtra("ref")?.let { r ->
            (0 until refSpinner.count).firstOrNull { refSpinner.getItemAtPosition(it) == r }?.let(refSpinner::setSelection)
        }
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
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        if (BuildConfig.IS_ACCEPTANCE) {
            root.addView(
                TextView(this).apply {
                    text = "验收变体 com.miplay.sender.acceptance（独立签名，不覆盖正式 debug/release）。\n" +
                        "受控音源：A=1000Hz / B=2000Hz / deny=禁止捕获。\n" +
                        "MediaProjection 须系统对话框授权，不用 adb appops 绕过。\n" +
                        "若 PCM 探针为静音，不能据此证明 UID 过滤（音量0可能导致全静音）。"
                    textSize = 12f
                    setPadding(0, 0, 0, pad / 2)
                },
            )
        } else {
            root.addView(
                TextView(this).apply {
                    text = "局域网直连小米音箱 · 选定 App 音频严格 UID 过滤"
                    textSize = 12f
                },
            )
        }
        status = TextView(this).apply { text = "空闲"; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        powerView = TextView(this).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        powerBtn = Button(this).apply { text = "打开电池优化设置"; setOnClickListener { requestPowerExemption() } }
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
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(context).apply { id = 1; text = "测试静音" })
            addView(RadioButton(context).apply { id = 2; text = "选定 App 音频（UID 过滤，需录屏授权）" })
            check(1)
            setOnCheckedChangeListener { _, checkedId ->
                val appMode = checkedId == 2
                pickAppBtn.isEnabled = appMode
                clearAppBtn.isEnabled = appMode
                captureHint.visibility = if (appMode) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        root.addView(sourceGroup)

        targetLabel = TextView(this).apply { text = "未选择投送 App"; textSize = 14f }
        root.addView(targetLabel)
        captureHint = TextView(this).apply {
            text = ProjectionSessionPolicy.recordAudioPurposeZh() +
                " 切换目标会停止旧会话并要求重新授权。不能保证禁止捕获的 App 可捕获，也不会静音原 App 本地输出。"
            textSize = 11f
            visibility = android.view.View.GONE
        }
        root.addView(captureHint)
        val arow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickAppBtn = Button(this).apply {
            text = "选择投送的 App"
            isEnabled = false
            setOnClickListener { openAppPicker() }
        }
        clearAppBtn = Button(this).apply {
            text = "清除"
            isEnabled = false
            setOnClickListener {
                if (CastService.running.get() && CastService.source == "app") {
                    status.text = "切换/清除目标前先停止当前投送"
                    stopCast()
                }
                store.clearSelection()
                selectedPackage = ""; selectedLabel = ""; selectedCert = ""
                lastGrantSession = null
                refreshTargetUi()
            }
        }
        arow.addView(pickAppBtn); arow.addView(clearAppBtn)
        root.addView(arow)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "延迟ms " })
        delayEdit = EditText(this).apply {
            hint = "自动(${CastService.autoDelayMs(this@MainActivity)})"
            inputType = InputType.TYPE_CLASS_NUMBER
            minEms = 5
        }
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
                    if (CastService.running.get()) {
                        setVolume("", progress)
                        rows.values.forEach { it.vol.progress = progress }
                    }
                }
            })
        }
        vrow.addView(vlabel); vrow.addView(volume)
        root.addView(vrow)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply {
            text = "开始投送"
            setOnClickListener { if (CastService.running.get()) stopCast() else startCast(0) }
        }
        pauseBtn = Button(this).apply {
            text = "暂停"; isEnabled = false
            setOnClickListener { service(if (CastService.paused) CastService.ACTION_RESUME else CastService.ACTION_PAUSE) }
        }
        btns.addView(startBtn); btns.addView(pauseBtn)
        root.addView(btns)
        statsView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 9f }
        root.addView(statsView)
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        scroll.fitsSystemWindows = false
        setContentView(scroll)
    }

    private fun refreshTargetFromStore() {
        val sel = store.loadSelection()
        if (sel != null) {
            selectedPackage = sel.packageName
            selectedLabel = sel.label
            selectedCert = sel.signingCertSha256
        }
        refreshTargetUi()
    }

    private fun refreshTargetUi() {
        targetLabel.text = if (selectedPackage.isBlank()) {
            "未选择投送 App"
        } else {
            "投送目标：$selectedLabel\n$selectedPackage"
        }
    }

    private fun openAppPicker() {
        if (CastService.running.get() && CastService.source == "app") {
            // 切目标：先停旧会话，再选，随后需重新授权
            stopCast()
            status.text = "已停止旧会话；选择新目标后需重新授权"
            lastGrantSession = null
        }
        startActivityForResult(Intent(this, AppPickerActivity::class.java), 43)
    }

    private fun addRow(ip: String, label: String, checked: Boolean) {
        if (rows.containsKey(ip)) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cb = CheckBox(this).apply {
            text = label; isChecked = checked
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val state = TextView(this).apply { text = ""; textSize = 12f }
        top.addView(cb); top.addView(state)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(32), 0, 0, 0)
        }
        val vl = TextView(this).apply { text = "音量 0"; textSize = 12f; minEms = 4 }
        val sb = SeekBar(this).apply {
            max = 100; progress = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
            service(if (on) CastService.ACTION_ADD else CastService.ACTION_REMOVE) { putExtra(CastService.EXTRA_IP, ip) }
        }
        rows[ip] = Row(ip, cb, state, sb, vl)
    }

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
                    if (rows.containsKey(p[0])) {
                        rows[p[0]]?.check?.text = "${p.getOrElse(1) { "?" }}  ${p.getOrElse(2) { "" }}  ${p[0]}"
                    }
                    addRow(
                        p[0],
                        "${p.getOrElse(1) { "?" }}  ${p.getOrElse(2) { "" }}  ${p[0]}${if (miplay) "" else "（非 MiPlay）"}",
                        sel,
                    )
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
        val appMode = sourceGroup.checkedRadioButtonId == 2
        if (appMode && selectedPackage.isBlank()) {
            status.text = "请先选择投送的 App（不会回退全系统捕获）"
            return
        }
        if (appMode && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "需要 RECORD_AUDIO 才能捕获选定 App 的播放音频（非麦克风）"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val ref = (refSpinner.selectedItem as String).let { if (it == "自动") "" else it }
        val base = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_IPS, ips)
            .putExtra(CastService.EXTRA_DELAY, delayEdit.text.toString().toIntOrNull() ?: 0)
            .putExtra(CastService.EXTRA_PREMUTE, volume.progress)
            .putExtra(CastService.EXTRA_REF, ref)
            .putExtra(CastService.EXTRA_SECS, secs)
            .putExtra(CastService.EXTRA_AUTH, authSpinner.selectedItemPosition)
        rows.values.forEach { it.vol.progress = volume.progress }
        if (appMode) {
            val grantSession = UUID.randomUUID().toString()
            lastGrantSession = grantSession
            pendingStart = base
                .putExtra(CastService.EXTRA_SOURCE, "app")
                .putExtra(CastService.EXTRA_TARGET_PACKAGE, selectedPackage)
                .putExtra(CastService.EXTRA_TARGET_CERT, selectedCert)
                .putExtra(CastService.EXTRA_GRANT_SESSION, grantSession)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 42)
            status.text = "等待录屏授权（每次会话需重新授权）…"
        } else {
            startForegroundService(base.putExtra(CastService.EXTRA_SOURCE, "silent"))
            status.text = "启动中（静音测试）…"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            42 -> {
                val p = pendingStart ?: return
                pendingStart = null
                if (resultCode != RESULT_OK || data == null) {
                    status.text = "录屏授权被拒绝"
                    Log.i(CastService.TAG, "projection denied")
                    lastGrantSession = null
                    return
                }
                Log.i(CastService.TAG, "projection granted session=$lastGrantSession")
                startForegroundService(
                    p.putExtra(CastService.EXTRA_PROJ_CODE, resultCode)
                        .putExtra(CastService.EXTRA_PROJ_DATA, data),
                )
            }
            43 -> {
                if (resultCode != RESULT_OK || data == null) return
                selectedPackage = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE).orEmpty()
                selectedLabel = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty()
                selectedCert = data.getStringExtra(AppPickerActivity.EXTRA_CERT).orEmpty()
                lastGrantSession = null // 切目标后旧授权作废
                refreshTargetUi()
                sourceGroup.check(2)
                status.text = "已选择 $selectedLabel，开始投送时将重新授权并解析 UID"
            }
        }
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
                if (on) {
                    status.text = CastService.lastEvent
                } else if (CastService.lastFailMessage.isNotBlank()) {
                    status.text = CastService.lastFailMessage
                }
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
                            append(
                                when {
                                    !r.isNull("error") -> "断开"
                                    st == 2 -> "播放中"
                                    st == 3 -> "已暂停"
                                    else -> r.optString("phase").take(10)
                                },
                            )
                            if (r.optInt("reconnects") > 0) append(" 重连${r.optInt("reconnects")}")
                            if (!r.isNull("volume_now")) append(" 音量${r.optInt("volume_now")}")
                        }
                    }
                    if (on) {
                        rows.values.filter { it.ip !in seen }.forEach {
                            it.state.text = if (it.check.isChecked) "连接中/重连中" else ""
                        }
                    } else {
                        rows.values.forEach { it.state.text = "" }
                    }
                }
                val probe = if (BuildConfig.IS_ACCEPTANCE && CastService.lastPcmProbe.isNotBlank()) {
                    "\n\nPCM探针:\n${CastService.lastPcmProbe}"
                } else {
                    ""
                }
                statsView.text = CastService.lastStats.replace(",\"", ",\n\"") + probe
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }
}
