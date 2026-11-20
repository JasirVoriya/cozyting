package com.miplay.sender

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.miplay.sender.capture.AndroidLaunchableApps
import com.miplay.sender.capture.AndroidPackageIdentityLookup
import com.miplay.sender.capture.CaptureTargetResolver
import com.miplay.sender.capture.CaptureTargetResult
import com.miplay.sender.capture.LaunchableApp
import com.miplay.sender.capture.LaunchableAppCatalog
import com.miplay.sender.capture.PrefsTargetAppStore
import com.miplay.sender.capture.TargetAppSelection
import com.miplay.sender.compat.CaptureSelectionLayers
import com.miplay.sender.compat.WindowInsetsApplyCompat

/**
 * 选择投送的 App：仅列出同用户可见的 MAIN/LAUNCHER 应用。
 * 置顶展示「最近使用」与「常用音乐与音频应用」，其余应用默认折叠，支持搜索与图标展示。
 */
class AppPickerActivity : Activity() {
    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CERT = "cert"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: PrefsTargetAppStore
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var filter: EditText
    private var all = emptyList<LaunchableApp>()
    private var expandOthers = false

    private val bgDark = Color.rgb(18, 22, 28)
    private val cardDark = Color.argb(210, 34, 40, 50)
    private val cardSelected = Color.argb(235, 220, 228, 238)
    private val textOnDark = Color.WHITE
    private val textOnLight = Color.rgb(20, 24, 28)
    private val textMuted = Color.argb(180, 190, 202, 214)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        store = PrefsTargetAppStore(this)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            TextView(this).apply {
                text = "选择投送音源 App"
                textSize = 20f
                setTextColor(textOnDark)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        headerRow.addView(
            Button(this).apply {
                text = "返回"
                setOnClickListener { finish() }
            },
        )
        root.addView(headerRow)
        root.addView(
            TextView(this).apply {
                text = "优先展示最近使用与常见音乐/播客应用；选中后仅保存包名与签名摘要，开始投送时再解析 UID。\n" +
                    CaptureSelectionLayers.settingsHintZh()
                textSize = 11f
                setTextColor(textMuted)
                setPadding(0, dp(6), 0, dp(10))
            },
        )
        filter = EditText(this).apply {
            hint = "搜索应用名称或包名…"
            setTextColor(textOnDark)
            setHintTextColor(textMuted)
            background = rounded(cardDark, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        root.addView(filter)
        status = TextView(this).apply {
            text = "正在加载可启动应用…"
            setTextColor(textMuted)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(6))
        }
        root.addView(status)
        root.addView(ProgressBar(this).apply { id = 1001 })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgDark)
            addView(root)
        }
        WindowInsetsApplyCompat.applySystemBarsPadding(scroll)
        setContentView(scroll)
        Thread {
            val apps = AndroidLaunchableApps.list(this)
            main.post {
                all = apps
                findViewById<ProgressBar>(1001)?.visibility = View.GONE
                val cat = LaunchableAppCatalog.categorize(
                    apps = apps,
                    recentPackages = store.loadRecentSelections().map { it.packageName },
                    selfPackage = packageName,
                )
                val musicCount = cat.recent.size + cat.recommendedMusic.size
                status.text = "已识别 $musicCount 个常用音频/最近应用（共 ${apps.size} 个可启动应用）"
                render()
            }
        }.start()
    }

    private fun render() {
        list.removeAllViews()
        val currentPkg = store.loadSelection()?.packageName.orEmpty()
        val q = filter.text?.toString()?.trim().orEmpty().lowercase()
        if (q.isNotEmpty()) {
            val shown = all.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
            if (shown.isEmpty()) {
                list.addView(
                    TextView(this).apply {
                        text = "没有匹配的可启动应用"
                        setTextColor(textMuted)
                        setPadding(0, dp(12), 0, dp(12))
                    },
                )
                return
            }
            addSectionHeader("搜索结果 (${shown.size})")
            shown.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
            return
        }

        val cat = LaunchableAppCatalog.categorize(
            apps = all,
            recentPackages = store.loadRecentSelections().map { it.packageName },
            selfPackage = packageName,
        )
        if (cat.recent.isNotEmpty()) {
            addSectionHeader("最近使用")
            cat.recent.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
        }
        if (cat.recommendedMusic.isNotEmpty()) {
            addSectionHeader("常用音乐与音频应用")
            cat.recommendedMusic.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
        }
        if (cat.others.isNotEmpty()) {
            val showDirectly = cat.recent.isEmpty() && cat.recommendedMusic.isEmpty()
            if (showDirectly) {
                addSectionHeader("全部可启动应用 (${cat.others.size})")
                cat.others.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
            } else {
                val toggle = TextView(this).apply {
                    text = if (expandOthers) {
                        "收起其他应用 (${cat.others.size}) ▾"
                    } else {
                        "展开其他全部应用 (${cat.others.size}) ▸"
                    }
                    setTextColor(textMuted)
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, dp(14), 0, dp(8))
                    setOnClickListener {
                        expandOthers = !expandOthers
                        render()
                    }
                }
                list.addView(toggle)
                if (expandOthers) {
                    cat.others.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
                }
            }
        }
    }

    private fun addSectionHeader(title: String) {
        list.addView(
            TextView(this).apply {
                text = title
                setTextColor(textMuted)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(4), dp(12), 0, dp(6))
            },
        )
    }

    private fun appRow(app: LaunchableApp, isSelected: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (isSelected) cardSelected else cardDark, 16f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { select(app) }
        }
        val iconView = ImageView(this).apply {
            val drawable = AndroidLaunchableApps.loadIcon(this@AppPickerActivity, app.packageName)
            if (drawable != null) {
                setImageDrawable(drawable)
            } else {
                setImageResource(android.R.drawable.sym_def_app_icon)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        meta.addView(
            TextView(this).apply {
                text = app.label
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isSelected) textOnLight else textOnDark)
            },
        )
        meta.addView(
            TextView(this).apply {
                text = app.packageName
                textSize = 11f
                setTextColor(if (isSelected) Color.rgb(60, 70, 80) else textMuted)
            },
        )
        val actionLabel = TextView(this).apply {
            text = if (isSelected) "已选 ✓" else "选择"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isSelected) Color.rgb(30, 95, 190) else textOnDark)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        row.addView(iconView)
        row.addView(meta)
        row.addView(actionLabel)
        return row
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

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
