package com.miplay.testtone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 受控测试音源：生成固定频率正弦，不读取用户第三方内容。
 * 媒体音量=0 时扬声器可能全静音，但 AudioTrack 仍按数字幅度写入；
 * 过滤验收应以发送端捕获 PCM 的能量/频谱为准，不能仅凭「听不到」证明。
 */
class ToneActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
            },
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.tone_desc)
                textSize = 14f
            },
        )
        root.addView(
            TextView(this).apply {
                text = "包名 $packageName\nUID ${Process.myUid()}\n频率 ${BuildConfig.TONE_HZ} Hz\n允许捕获=${BuildConfig.ALLOW_CAPTURE}"
                textSize = 13f
                setPadding(0, pad, 0, pad)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "说明：本工具只生成受控正弦波。系统媒体音量为 0 时可能听不到声音；UID 过滤是否生效请看验收发送端的 PCM RMS/频谱探针。若捕获也全静音，不能据此证明过滤。"
                textSize = 12f
            },
        )
        status = TextView(this).apply { text = "未播放"; setPadding(0, pad, 0, pad) }
        root.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(
            Button(this).apply {
                text = "开始播放"
                setOnClickListener {
                    startForegroundService(Intent(this@ToneActivity, ToneService::class.java).setAction(ToneService.ACTION_START))
                    status.text = "正在播放 ${BuildConfig.TONE_HZ} Hz"
                }
            },
        )
        row.addView(
            Button(this).apply {
                text = "停止"
                setOnClickListener {
                    startService(Intent(this@ToneActivity, ToneService::class.java).setAction(ToneService.ACTION_STOP))
                    status.text = "已停止"
                }
            },
        )
        root.addView(row)
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
