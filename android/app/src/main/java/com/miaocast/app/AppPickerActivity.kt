package com.miaocast.app

import android.app.Activity
import android.app.Dialog
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.miaocast.app.capture.AndroidLaunchableApps
import com.miaocast.app.capture.AndroidPackageIdentityLookup
import com.miaocast.app.capture.CaptureScopeMode
import com.miaocast.app.capture.CaptureTargetResolver
import com.miaocast.app.capture.CaptureTargetResult
import com.miaocast.app.capture.FavoriteAudioSource
import com.miaocast.app.capture.LaunchableApp
import com.miaocast.app.capture.LaunchableAppCatalog
import com.miaocast.app.capture.ManualSourceSwitchPolicy
import com.miaocast.app.capture.PrefsTargetAppStore
import com.miaocast.app.capture.TargetAppSelection
import com.miaocast.app.compat.CaptureSelectionLayers
import com.miaocast.app.compat.WindowInsetsApplyCompat
import com.miaocast.app.ui.LinearDragReorderController
import com.miaocast.app.ui.PrefsCastSettingsStore
import com.miaocast.app.ui.ThemeMode
import com.miaocast.app.ui.UiComponents
import com.miaocast.app.ui.UiIconCache
import com.miaocast.app.ui.UiPalette

/**
 * 常用音源管理与选择页：
 * - 用户可自由增删「常用音源」名单；
 * - 每个常用音源均提供独立的「自动识别」开关，仅允许已加入常用名单且开启该开关的 App 参与自动识别（含投送中热切换）；
 * - 点击任意应用主行可立即将其设为当前投送音源（仍走单 UID + 签名 fail-closed 校验）。
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
    private lateinit var scrollView: ScrollView
    private lateinit var list: LinearLayout
    private var favoritesContainer: LinearLayout? = null
    private var dragController: LinearDragReorderController? = null
    private val cardSelectedFlags = mutableMapOf<View, Boolean>()
    private lateinit var status: TextView
    private lateinit var filter: EditText
    private lateinit var clearBtn: ImageView
    private lateinit var loadingSpinner: ProgressBar
    private var all = emptyList<LaunchableApp>()
    private var favorites = emptyList<FavoriteAudioSource>()
    private var expandOthers = false
    private var previewMode = false
    private var infoDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        store = PrefsTargetAppStore(this)
        settingsStore = PrefsCastSettingsStore(this)
        previewMode = intent?.getBooleanExtra("ui_preview", false) == true
        buildUi()

        if (!previewMode) {
            Thread {
                val apps = AndroidLaunchableApps.list(this)
                val favs = store.ensureFavoriteSourcesInitialized(apps, selfPackage = packageName)
                main.post {
                    all = apps
                    favorites = favs
                    loadingSpinner.visibility = View.GONE
                    updateStatusSummary()
                    render()
                }
            }.start()
        }
    }

    override fun onDestroy() {
        runCatching { infoDialog?.dismiss() }
        infoDialog = null
        dragController?.cancel()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dragController?.onDispatchTouchEvent(ev) == true) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun buildUi() {
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
                text = "常用音源与选择"
                textSize = 19f
                setTextColor(palette.textPrimary)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        headerRow.addView(
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_info, "音源与自动识别说明") {
                runCatching { infoDialog?.dismiss() }
                infoDialog = UiComponents.showStyleBDialog(
                    activity = this,
                    palette = palette,
                    title = "常用音源与自动识别说明",
                    message = "1. 「常用音源」支持自由添加、移除以及长按拖动排序，排序结果会实时同步至主界面快捷切换栏。\n\n" +
                        "2. 自动识别仅会从「常用音源」且已开启「自动识别」开关的 App 中跟随；即使旧软件未关闭，投送途中切到另一个常用音乐 App 播放也会自动热切换，名单外或关闭开关的应用不会被自动切入。\n\n" +
                        "3. 无论自动识别还是手动点选，开始投送与热切换前都会实时校验独立 UID 与签名，绝不退化为全系统捕获。",
                    footnote = CaptureSelectionLayers.settingsHintZh(),
                    primaryLabel = "知道了",
                ) {
                    infoDialog = null
                }
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

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        root.clipChildren = false
        root.clipToPadding = false
        root.addView(list)

        scrollView = ScrollView(this).apply {
            setBackgroundColor(palette.bgCanvas)
            clipToPadding = false
            addView(root)
        }
        WindowInsetsApplyCompat.applySystemBarsPadding(scrollView)
        setContentView(scrollView)

        dragController?.cancel()
        dragController = LinearDragReorderController(
            context = this,
            axis = LinearDragReorderController.Axis.VERTICAL,
            containerProvider = { favoritesContainer },
            scrollViewProvider = { if (::scrollView.isInitialized) scrollView else null },
            itemKeyProvider = { it.tag as? String },
            isEnabled = { filter.text?.toString()?.trim().isNullOrEmpty() },
            dispatchSyntheticCancel = { super.dispatchTouchEvent(it) },
            onDragVisualStateChanged = { card, dragging ->
                applyFavoriteCardBackground(card, isSelected = cardSelectedFlags[card] == true, dragging = dragging)
            },
            onItemMoved = { fromPkg, toPkg ->
                favorites = LaunchableAppCatalog.reorderFavoriteSources(favorites, fromPkg, toPkg)
            },
            onDragFinished = { changed ->
                if (changed) {
                    store.saveFavoriteSources(favorites)
                    updateStatusSummary()
                }
            },
        )
    }

    private fun updateStatusSummary() {
        val cat = LaunchableAppCatalog.categorizeWithFavorites(
            apps = all,
            favoriteSources = favorites,
            selfPackage = packageName,
        )
        val autoCount = cat.favorites.count { it.second.autoDetectEnabled }
        status.text = "常用音源 ${cat.favorites.size} 个（允许自动识别 $autoCount 个）· 点击行设为当前音源"
    }

    private fun render() {
        dragController?.cancel()
        favoritesContainer = null
        cardSelectedFlags.clear()
        list.removeAllViews()
        val activeCastPkg = if (
            !previewMode &&
            CastService.running.get() &&
            CastService.source == "app" &&
            CastService.activeScopeMode == CaptureScopeMode.SINGLE_APP &&
            CastService.targetPackage.isNotBlank()
        ) {
            CastService.targetPackage
        } else {
            null
        }
        val currentPkg = activeCastPkg ?: store.loadSelection()?.packageName.orEmpty()
        val favByPkg = favorites.associateBy { it.packageName }
        val q = filter.text?.toString()?.trim().orEmpty().lowercase()

        if (q.isNotEmpty()) {
            val shown = LaunchableAppCatalog.filterLaunchable(all, selfPackage = packageName).filter {
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
            shown.forEach { app ->
                val fav = favByPkg[app.packageName]
                if (fav != null) {
                    list.addView(favoriteAppCard(app, fav, app.packageName == currentPkg, allowDrag = false))
                } else {
                    list.addView(otherAppRow(app, app.packageName == currentPkg))
                }
            }
            return
        }

        val cat = LaunchableAppCatalog.categorizeWithFavorites(
            apps = all,
            favoriteSources = favorites,
            selfPackage = packageName,
        )

        val allowDrag = cat.favorites.size >= 2
        val favHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(6))
        }
        favHeader.addView(
            TextView(this).apply {
                text = "常用音源 (${cat.favorites.size})"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (allowDrag) {
            favHeader.addView(
                TextView(this).apply {
                    text = "长按拖动排序"
                    textSize = 11f
                    setTextColor(palette.textTertiary)
                },
            )
        }
        list.addView(favHeader)
        if (cat.favorites.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = "暂无常用音源 · 在下方点击「+ 常用」添加，只有名单内且开启开关的 App 会参与自动识别"
                    setTextColor(palette.textSecondary)
                    textSize = 12.5f
                    background = UiComponents.roundedDrawable(
                        fillColor = palette.panelBg,
                        radiusPx = UiComponents.dpf(this@AppPickerActivity, UiPalette.RADIUS_CARD_DP.toFloat()),
                        strokeColor = palette.panelBorder,
                        strokeWidthPx = dp(1).coerceAtLeast(1),
                    )
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) }
                },
            )
        } else {
            val favBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            cat.favorites.forEach { (app, fav) ->
                favBox.addView(favoriteAppCard(app, fav, app.packageName == currentPkg, allowDrag = allowDrag))
            }
            favoritesContainer = favBox
            list.addView(favBox)
        }

        if (cat.others.isNotEmpty()) {
            val showDirectly = cat.favorites.isEmpty()
            if (showDirectly) {
                list.addView(UiComponents.sectionTitle(this, palette, "全部可启动应用 (${cat.others.size})"))
                cat.others.forEach { app -> list.addView(otherAppRow(app, app.packageName == currentPkg)) }
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
                        topMargin = dp(8)
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
                            "添加更多应用到常用音源 (${cat.others.size})"
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
                    cat.others.forEach { app -> list.addView(otherAppRow(app, app.packageName == currentPkg)) }
                }
            }
        }
    }

    private fun applyFavoriteCardBackground(card: View, isSelected: Boolean, dragging: Boolean) {
        val bgColor = if (isSelected) palette.rowSelectedBg else palette.panelBg
        val borderColor = when {
            dragging -> palette.accent
            isSelected -> palette.rowSelectedBorder
            else -> palette.panelBorder
        }
        val borderW = when {
            dragging -> dp(2).coerceAtLeast(2)
            isSelected && !palette.isDark -> dp(2).coerceAtLeast(2)
            else -> dp(1).coerceAtLeast(1)
        }
        card.background = UiComponents.roundedDrawable(
            fillColor = bgColor,
            radiusPx = UiComponents.dpf(this, UiPalette.RADIUS_CARD_DP.toFloat()),
            strokeColor = borderColor,
            strokeWidthPx = borderW,
        )
    }

    /** 常用音源卡片：上半部分点击设为当前音源，下半部分提供独立「自动识别」开关与「移出常用」按钮，支持长按拖动排序。 */
    private fun favoriteAppCard(
        app: LaunchableApp,
        fav: FavoriteAudioSource,
        isSelected: Boolean,
        allowDrag: Boolean,
    ): View {
        val titleColor = if (isSelected) palette.onSelectedPrimary else palette.textPrimary
        val subColor = if (isSelected) palette.onSelectedSecondary else palette.textSecondary
        val dividerColor = if (isSelected) {
            if (palette.isDark) Color.argb(26, 18, 22, 30) else Color.argb(28, 37, 99, 235)
        } else {
            palette.panelBorder
        }
        val switchOffTrack = if (isSelected) palette.trackOnSelected else palette.trackIdle

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            if (allowDrag) {
                tag = app.packageName
            }
        }
        cardSelectedFlags[card] = isSelected
        applyFavoriteCardBackground(card, isSelected = isSelected, dragging = false)

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiComponents.rippleSurface(
                fillColor = Color.TRANSPARENT,
                radiusPx = UiComponents.dpf(this@AppPickerActivity, UiPalette.RADIUS_CONTROL_DP.toFloat()),
                rippleColor = if (isSelected) palette.rippleOnSelected else palette.ripple,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { select(app) }
        }
        val iconSize = dp(36)
        val iconView = UiComponents.roundedImageView(this, dp(9).toFloat()).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(11) }
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
        mainRow.addView(iconView)
        mainRow.addView(meta)
        if (allowDrag) {
            val dragHandle = ImageView(this).apply {
                setImageResource(R.drawable.ic_ui_drag_handle)
                setColorFilter(subColor)
                alpha = 0.72f
                val hp = dp(5)
                setPadding(hp, hp, hp, hp)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(4) }
                tag = LinearDragReorderController.TAG_HANDLE
                contentDescription = "长按拖动排序"
            }
            mainRow.addView(dragHandle)
        }
        mainRow.addView(UiComponents.checkCircle(this, palette, selected = isSelected))
        card.addView(mainRow)

        val divider = View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1).coerceAtLeast(1),
            ).apply {
                topMargin = dp(9)
                bottomMargin = dp(8)
            }
        }
        card.addView(divider)

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val autoLabel = TextView(this).apply {
            text = if (fav.autoDetectEnabled) "自动识别：允许" else "自动识别：关闭"
            textSize = 12f
            setTypeface(null, if (fav.autoDetectEnabled) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                when {
                    fav.autoDetectEnabled && isSelected -> palette.selectedPillText
                    fav.autoDetectEnabled -> palette.accentBright
                    else -> subColor
                },
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }
        val autoSwitch = UiComponents.compactSwitch(
            context = this,
            palette = palette,
            initialChecked = fav.autoDetectEnabled,
            trackOffColor = switchOffTrack,
        ) { checked ->
            favorites = store.setFavoriteAutoDetect(app.packageName, checked)
            updateStatusSummary()
            render()
        }.apply {
            tag = LinearDragReorderController.TAG_IGNORE
        }
        val autoWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(autoSwitch)
            addView(
                View(this@AppPickerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                },
            )
            addView(autoLabel)
        }
        val removeBtn = TextView(this).apply {
            text = "移出常用"
            textSize = 11.5f
            setTextColor(subColor)
            background = UiComponents.rippleSurface(
                fillColor = if (isSelected) {
                    if (palette.isDark) Color.argb(24, 18, 22, 30) else Color.argb(18, 37, 99, 235)
                } else {
                    palette.controlSurface
                },
                radiusPx = UiComponents.dpf(this@AppPickerActivity, 999f),
                rippleColor = if (isSelected) palette.rippleOnSelected else palette.ripple,
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            isFocusable = true
            tag = LinearDragReorderController.TAG_IGNORE
            setOnClickListener {
                favorites = store.removeFavoriteSource(app.packageName)
                updateStatusSummary()
                render()
            }
        }
        actionBar.addView(autoWrap)
        actionBar.addView(removeBtn)
        card.addView(actionBar)
        return card
    }

    /** 非常用应用行：点击主行直接选为当前音源，点击右侧「+ 常用」将其加入常用音源名单。 */
    private fun otherAppRow(app: LaunchableApp, isSelected: Boolean): View {
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
        val iconSize = dp(36)
        val iconView = UiComponents.roundedImageView(this, dp(9).toFloat()).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(11) }
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
        val addFavBtn = TextView(this).apply {
            text = "+ 常用"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.accentBright)
            background = UiComponents.rippleSurface(
                fillColor = palette.accentSoftBg,
                radiusPx = UiComponents.dpf(this@AppPickerActivity, 999f),
                rippleColor = palette.ripple,
            )
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { if (isSelected) marginEnd = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                favorites = store.addFavoriteSource(app.packageName, autoDetectEnabled = true)
                updateStatusSummary()
                render()
            }
        }
        row.addView(iconView)
        row.addView(meta)
        row.addView(addFavBtn)
        if (isSelected) {
            row.addView(UiComponents.checkCircle(this, palette, selected = true))
        }
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
                    val action = ManualSourceSwitchPolicy.decide(
                        ManualSourceSwitchPolicy.Input(
                            serviceRunning = CastService.running.get(),
                            activeServiceSource = CastService.source,
                            activeScopeMode = CastService.activeScopeMode,
                            activeTargetPackage = CastService.targetPackage,
                            pickedPackage = r.packageName,
                        ),
                    )
                    if (action != ManualSourceSwitchPolicy.Action.HOT_SWITCH_IN_CAST) {
                        store.saveSelection(
                            TargetAppSelection(
                                packageName = r.packageName,
                                label = app.label,
                                signingCertSha256 = r.signingCertSha256,
                            ),
                        )
                    }
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

    /** 仅用于 JVM 截图预览单测注入确定性 UI 状态。 */
    internal fun applyUiPreviewStateForTest(
        isDark: Boolean,
        previewApps: List<LaunchableApp>,
        previewFavorites: List<FavoriteAudioSource>,
        previewSelectedPackage: String = "",
        previewExpandOthers: Boolean = true,
    ) {
        settingsStore.themeMode = if (isDark) ThemeMode.DARK else ThemeMode.LIGHT
        store.saveFavoriteSources(previewFavorites)
        val selectedApp = previewApps.firstOrNull { it.packageName == previewSelectedPackage }
        if (selectedApp != null) {
            store.saveSelection(
                TargetAppSelection(
                    packageName = selectedApp.packageName,
                    label = selectedApp.label,
                    signingCertSha256 = "preview-cert",
                ),
            )
        } else {
            store.clearSelection()
        }
        all = previewApps
        favorites = previewFavorites
        expandOthers = previewExpandOthers
        buildUi()
        loadingSpinner.visibility = View.GONE
        updateStatusSummary()
        render()
    }

    private fun dp(v: Int) = UiComponents.dp(this, v)
}
