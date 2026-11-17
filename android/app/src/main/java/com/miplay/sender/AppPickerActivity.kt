package com.miplay.sender

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.miplay.sender.capture.AndroidLaunchableApps
import com.miplay.sender.capture.AndroidPackageIdentityLookup
import com.miplay.sender.capture.CaptureTargetResolver
import com.miplay.sender.capture.CaptureTargetResult
import com.miplay.sender.capture.LaunchableApp
import com.miplay.sender.capture.PrefsTargetAppStore
import com.miplay.sender.capture.TargetAppSelection

/**
 * 选择投送的 App：仅列出同用户可见的 MAIN/LAUNCHER 应用。
 * 选中后只保存包名与签名摘要；开始投送时再解析 UID。
 */
class AppPickerActivity : Activity() {
    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CERT = "cert"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var filter: EditText
    private var all = emptyList<LaunchableApp>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            TextView(this).apply {
                text = "选择投送的 App"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "仅显示可启动应用（非全部安装包）。共享 UID / 其他用户资料的应用在开始时会被拒绝。无法保证禁止捕获的第三方 App 可被捕获，也不会静音原 App 本地输出。"
                textSize = 12f
                setPadding(0, pad / 2, 0, pad / 2)
            },
        )
        filter = EditText(this).apply { hint = "搜索名称或包名" }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        root.addView(filter)
        status = TextView(this).apply { text = "正在加载可启动应用…" }
        root.addView(status)
        root.addView(ProgressBar(this).apply { id = 1001 })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(
            Button(this).apply {
                text = "取消"
                setOnClickListener { finish() }
            },
        )
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        Thread {
            val apps = AndroidLaunchableApps.list(this)
            main.post {
                all = apps
                findViewById<ProgressBar>(1001)?.visibility = android.view.View.GONE
                status.text = "共 ${apps.size} 个可启动应用（queries=MAIN/LAUNCHER）"
                render()
            }
        }.start()
    }

    private fun render() {
        list.removeAllViews()
        val q = filter.text?.toString()?.trim().orEmpty().lowercase()
        val shown = if (q.isEmpty()) all else all.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply { text = "没有匹配的可启动应用" })
            return
        }
        for (app in shown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(
                TextView(this).apply {
                    text = app.label
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                },
            )
            row.addView(
                TextView(this).apply {
                    text = app.packageName
                    textSize = 12f
                },
            )
            row.addView(
                Button(this).apply {
                    text = "选择"
                    setOnClickListener { select(app) }
                },
            )
            list.addView(row)
        }
    }

    private fun select(app: LaunchableApp) {
        status.text = "正在校验 ${app.packageName}…"
        Thread {
            val resolver = CaptureTargetResolver(AndroidPackageIdentityLookup(this))
            when (val r = resolver.resolveForCapture(app.packageName)) {
                is CaptureTargetResult.Fail -> main.post {
                    status.text = r.messageZh
                }
                is CaptureTargetResult.Ok -> {
                    val store = PrefsTargetAppStore(this)
                    store.saveSelection(
                        TargetAppSelection(
                            packageName = r.packageName,
                            label = app.label,
                            signingCertSha256 = r.signingCertSha256,
                        ),
                    )
                    main.post {
                        setResult(
                            RESULT_OK,
                            Intent()
                                .putExtra(EXTRA_PACKAGE, r.packageName)
                                .putExtra(EXTRA_LABEL, app.label)
                                .putExtra(EXTRA_CERT, r.signingCertSha256),
                        )
                        finish()
                    }
                }
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
