package com.miplay.sender

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.miplay.sender.ui.PrefsCastSettingsStore
import com.miplay.sender.ui.UiComponents
import com.miplay.sender.ui.UiIconCache
import com.miplay.sender.ui.UiPalette

/**
 * 选择投送音源 App：与主界面共享同一套明暗主题与样式 B 组件规范，
 * 使用 [UiIconCache] 异步缓存图标避免展开全部应用时卡顿。
 */
class AppPickerActivity : Activity() {
    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CERT = "cert"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: PrefsTargetAppStore
    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var palette: UiPalette
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var filter: EditText
    private lateinit var clearBtn: ImageView
    private lateinit var loadingSpinner: ProgressBar
    private var all = emptyList<LaunchableApp>()
    private var expandOthers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        store = PrefsTargetAppStore(this)
        settingsStore = PrefsCastSettingsStore(this)
        val isDark = settingsStore.isDark(this)
        palette = UiPalette.forMode(isDark)
        WindowInsetsApplyCompat.applyEdgeToEdge(this, isDark)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(10), pad, dp(24))
        }

        // —— 统一顶栏 ——
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        headerRow.addView(
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_back, "返回") {
                finish()
            },
        )
        headerRow.addView(
            TextView(this).apply {
                text = "选择投送音源"
                textSize = 19f
                setTextColor(palette.textPrimary)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        headerRow.addView(
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_info, "捕获说明") {
                AlertDialog.Builder(this)
                    .setTitle("音源捕获说明")
                    .setMessage(
                        "仅列出本机可启动的同用户应用；选中后仅保存包名与签名摘要，开始投送时再实时解析 UID。\n\n" +
                            CaptureSelectionLayers.settingsHintZh(),
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            },
        )
        root.addView(headerRow)

        // —— 统一搜索栏（图标 + 输入框 + 一键清空） ——
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = palette.panelBg,
                radiusPx = UiComponents.dpf(this@AppPickerActivity, UiPalette.RADIUS_CONTROL_DP.toFloat()),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(12), dp(2), dp(8), dp(2))
        }
        searchBox.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_ui_search)
                setColorFilter(palette.textTertiary)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
            },
        )
        filter = EditText(this).apply {
            hint = "搜索应用名称或包名…"
            textSize = 14f
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textTertiary)
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        clearBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_close)
            setColorFilter(palette.textSecondary)
            val padClear = dp(6)
            setPadding(padClear, padClear, padClear, padClear)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "清空搜索"
            setOnClickListener { filter.setText("") }
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                render()
            }
        })
        searchBox.addView(filter)
        searchBox.addView(clearBtn)
        root.addView(searchBox)

        status = TextView(this).apply {
            text = "正在加载可启动应用…"
            setTextColor(palette.textSecondary)
            textSize = 12f
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        root.addView(status)

        loadingSpinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
        }
        root.addView(loadingSpinner)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.bgCanvas)
            addView(root)
        }
        WindowInsetsApplyCompat.applySystemBarsPadding(scroll)
        setContentView(scroll)

        Thread {
            val apps = AndroidLaunchableApps.list(this)
            main.post {
                all = apps
                loadingSpinner.visibility = View.GONE
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
                        setTextColor(palette.textSecondary)
                        textSize = 13f
                        setPadding(dp(4), dp(18), 0, dp(12))
                    },
                )
                return
            }
            list.addView(UiComponents.sectionTitle(this, palette, "搜索结果 (${shown.size})"))
            shown.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
            return
        }

        val cat = LaunchableAppCatalog.categorize(
            apps = all,
            recentPackages = store.loadRecentSelections().map { it.packageName },
            selfPackage = packageName,
        )
        if (cat.recent.isNotEmpty()) {
            list.addView(UiComponents.sectionTitle(this, palette, "最近使用"))
            cat.recent.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
        }
        if (cat.recommendedMusic.isNotEmpty()) {
            list.addView(UiComponents.sectionTitle(this, palette, "常用音乐与音频应用"))
            cat.recommendedMusic.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
        }
        if (cat.others.isNotEmpty()) {
            val showDirectly = cat.recent.isEmpty() && cat.recommendedMusic.isEmpty()
            if (showDirectly) {
                list.addView(UiComponents.sectionTitle(this, palette, "全部可启动应用 (${cat.others.size})"))
                cat.others.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
            } else {
                val toggle = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = UiComponents.rippleSurface(
                        fillColor = palette.controlSurface,
                        radiusPx = UiComponents.dpf(this@AppPickerActivity, UiPalette.RADIUS_CONTROL_DP.toFloat()),
                        rippleColor = palette.ripple,
                        strokeColor = palette.panelBorder,
                        strokeWidthPx = dp(1).coerceAtLeast(1),
                    )
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(10)
                        bottomMargin = dp(8)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        expandOthers = !expandOthers
                        render()
                    }
                }
                toggle.addView(
                    TextView(this).apply {
                        text = if (expandOthers) {
                            "收起其他应用 (${cat.others.size})"
                        } else {
                            "展开其他全部应用 (${cat.others.size})"
                        }
                        setTextColor(palette.textPrimary)
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                toggle.addView(
                    ImageView(this).apply {
                        setImageResource(
                            if (expandOthers) R.drawable.ic_ui_chevron_down else R.drawable.ic_ui_chevron_right,
                        )
                        setColorFilter(palette.textSecondary)
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                    },
                )
                list.addView(toggle)
                if (expandOthers) {
                    cat.others.forEach { app -> list.addView(appRow(app, app.packageName == currentPkg)) }
                }
            }
        }
    }

    private fun appRow(app: LaunchableApp, isSelected: Boolean): View {
        val bgColor = if (isSelected) palette.rowSelectedBg else palette.panelBg
        val borderColor = if (isSelected) palette.rowSelectedBorder else palette.panelBorder
        val borderW = if (isSelected && !palette.isDark) dp(2).coerceAtLeast(2) else dp(1).coerceAtLeast(1)
        val titleColor = if (isSelected) palette.onSelectedPrimary else palette.textPrimary
        val subColor = if (isSelected) palette.onSelectedSecondary else palette.textSecondary

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = bgColor,
                radiusPx = UiComponents.dpf(this@AppPickerActivity, UiPalette.RADIUS_CARD_DP.toFloat()),
                rippleColor = if (isSelected) palette.rippleOnSelected else palette.ripple,
                strokeColor = borderColor,
                strokeWidthPx = borderW,
            )
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
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
            setColorFilter(palette.textSecondary)
        }
        UiIconCache.bindAppIcon(this, iconView, app.packageName)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        meta.addView(
            TextView(this).apply {
                text = app.label
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(titleColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        meta.addView(
            TextView(this).apply {
                text = app.packageName
                textSize = 11f
                setTextColor(subColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        row.addView(iconView)
        row.addView(meta)
        row.addView(UiComponents.checkCircle(this, palette, selected = isSelected))
        return row
    }

    private fun select(app: LaunchableApp) {
        status.text = "正在校验 ${app.label}…"
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

    private fun dp(v: Int) = UiComponents.dp(this, v)
}
