package com.cozyting.app.diag

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cozyting.app.BuildConfig
import com.cozyting.app.R
import com.cozyting.app.compat.WindowInsetsApplyCompat
import com.cozyting.app.ui.AppLocaleManager
import com.cozyting.app.ui.PrefsCastSettingsStore
import com.cozyting.app.ui.UiComponents
import com.cozyting.app.ui.UiPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 独立运行日志浏览与导出页面（Style B 结构化时间线，支持明暗主题与中/英/日三语）。
 *
 * - 浏览本地异步轮转的 Kotlin + Rust `CozyTingCore` 日志及 `cast_ops.log`；
 * - 结构化展示每条日志的时间、级别色条/徽章、来源标签、事件主干与 `key=value` 参数胶囊；
 * - 超长嵌入 JSON 自动折叠为摘要胶囊，点击按需格式化并语法高亮展开；
 * - 支持分类筛选（全部 / 告警与错误 / 核心协议 / 操作流水）、关键词搜索、结构化/原始视图切换、单条复制与一键导出；
 * - 严格遵守纯本地沙箱原则：不联网、不自动上传，仅在用户主动点击「导出」时通过 `FileProvider` 交由系统分享。
 */
class LogViewerActivity : Activity() {
    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var palette: UiPalette
    private lateinit var logColors: LogThemeColors
    private val main = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cozyting-log-viewer").apply { isDaemon = true }
    }

    private var activeFilter: AppLogStore.Filter = AppLogStore.Filter.ALL
    private var searchQuery: String = ""
    private var isRawMode: Boolean = false
    private var allFilterEntries: List<LogEntryParser.LogEntry> = emptyList()
    private var visibleEntries: List<LogEntryParser.LogEntry> = emptyList()
    private var totalSizeText: String = "0 B"
    private var totalWarnCount: Int = 0
    private var totalErrCount: Int = 0
    private val expandedJsonIds = HashSet<Int>()
    private val prettyJsonCache = HashMap<Int, CharSequence>()

    private var summaryView: TextView? = null
    private var warnPillView: TextView? = null
    private var errPillView: TextView? = null
    private var filterTabViews: List<LinearLayout> = emptyList()
    private var filterTabLabels: List<TextView> = emptyList()
    private var warnTabBadgeView: TextView? = null
    private var searchInputView: EditText? = null
    private var searchClearBtn: ImageView? = null
    private var modeToggleBtn: TextView? = null
    private var listView: ListView? = null
    private var listAdapter: StructuredLogAdapter? = null
    private var rawScrollView: ScrollView? = null
    private var rawTextView: TextView? = null
    private var emptyView: TextView? = null
    private var jumpLatestBtn: LinearLayout? = null
    private var clearDialog: Dialog? = null

    private data class LogThemeColors(
        val coreFg: Int,
        val coreBg: Int,
        val opsFg: Int,
        val opsBg: Int,
        val warnBg: Int,
        val errBg: Int,
        val okBg: Int,
        val rowBg: Int,
        val rowWarnBg: Int,
        val rowErrBg: Int,
        val jsonBg: Int,
        val jsonKey: Int,
        val jsonStr: Int,
        val jsonNum: Int,
        val jsonBool: Int,
        val searchHighlight: Int,
        val dangerBg: Int,
    )

    private sealed class ListRow {
        data class DateDivider(val date: String) : ListRow()
        data class Entry(val entry: LogEntryParser.LogEntry) : ListRow()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        AppLog.init(applicationContext)
        settingsStore = PrefsCastSettingsStore(this)
        renderUi()
        reloadLogs(scrollToBottom = true)
    }

    override fun onDestroy() {
        runCatching { clearDialog?.dismiss() }
        clearDialog = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildLogThemeColors(p: UiPalette): LogThemeColors = if (p.isDark) {
        LogThemeColors(
            coreFg = Color.rgb(229, 109, 72),
            coreBg = Color.argb(42, 229, 109, 72),
            opsFg = Color.rgb(167, 139, 250),
            opsBg = Color.argb(38, 167, 139, 250),
            warnBg = Color.argb(36, 251, 191, 36),
            errBg = Color.argb(40, 248, 113, 113),
            okBg = Color.argb(36, 52, 211, 153),
            rowBg = Color.argb(11, 255, 255, 255),
            rowWarnBg = Color.argb(22, 251, 191, 36),
            rowErrBg = Color.argb(26, 248, 113, 113),
            jsonBg = Color.argb(72, 0, 0, 0),
            jsonKey = Color.rgb(125, 211, 252),
            jsonStr = Color.rgb(167, 243, 208),
            jsonNum = Color.rgb(252, 211, 77),
            jsonBool = Color.rgb(196, 181, 253),
            searchHighlight = Color.argb(96, 251, 191, 36),
            dangerBg = Color.argb(28, 248, 113, 113),
        )
    } else {
        LogThemeColors(
            coreFg = Color.rgb(197, 78, 42),
            coreBg = Color.argb(32, 229, 109, 72),
            opsFg = Color.rgb(124, 58, 237),
            opsBg = Color.argb(26, 124, 58, 237),
            warnBg = Color.argb(30, 217, 119, 6),
            errBg = Color.argb(26, 220, 38, 38),
            okBg = Color.argb(28, 22, 163, 74),
            rowBg = Color.rgb(248, 250, 252),
            rowWarnBg = Color.argb(22, 217, 119, 6),
            rowErrBg = Color.argb(20, 220, 38, 38),
            jsonBg = Color.rgb(241, 245, 249),
            jsonKey = Color.rgb(3, 105, 161),
            jsonStr = Color.rgb(4, 120, 87),
            jsonNum = Color.rgb(180, 83, 9),
            jsonBool = Color.rgb(109, 40, 217),
            searchHighlight = Color.argb(92, 251, 191, 36),
            dangerBg = Color.argb(22, 220, 38, 38),
        )
    }

    private fun renderUi() {
        val isDark = settingsStore.isDark(this)
        palette = UiPalette.forMode(isDark)
        logColors = buildLogThemeColors(palette)
        WindowInsetsApplyCompat.applyEdgeToEdge(this, isDark)

        val pad = dp(16)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bgCanvas)
        }
        WindowInsetsApplyCompat.applySystemBarsPadding(outer)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(10), pad, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        outer.addView(root)

        root.addView(buildTopBar())
        root.addView(buildFilterBar())
        root.addView(buildSearchAndModeBar())
        root.addView(buildLogPanel())
        root.addView(buildBottomActions())

        setContentView(outer)
    }

    private fun buildTopBar(): LinearLayout {
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        topBar.addView(
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_back, getString(R.string.common_back)) {
                finish()
            },
        )
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(
            TextView(this).apply {
                text = getString(R.string.title_log_viewer)
                textSize = 18.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        summaryView = TextView(this).apply {
            text = getString(R.string.log_viewer_loading)
            textSize = 11.5f
            setTextColor(palette.textSecondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subRow.addView(summaryView)

        warnPillView = buildStatusMiniPill(palette.statusWarning, logColors.warnBg) {
            toggleWarnErrorFilter()
        }
        errPillView = buildStatusMiniPill(palette.statusError, logColors.errBg) {
            toggleWarnErrorFilter()
        }
        subRow.addView(warnPillView)
        subRow.addView(errPillView)
        titleCol.addView(subRow)
        topBar.addView(titleCol)

        topBar.addView(
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_refresh, getString(R.string.log_viewer_refresh)) {
                reloadLogs(scrollToBottom = true)
            },
        )
        return topBar
    }

    private fun buildStatusMiniPill(fgColor: Int, bgColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            visibility = View.GONE
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(fgColor)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = UiComponents.roundedDrawable(bgColor, dpf(999f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun buildFilterBar(): LinearLayout {
        val filters = listOf(
            AppLogStore.Filter.ALL to getString(R.string.log_viewer_filter_all),
            AppLogStore.Filter.WARN_ERROR to getString(R.string.log_viewer_filter_warn_error),
            AppLogStore.Filter.CORE to getString(R.string.log_viewer_filter_core),
            AppLogStore.Filter.OPS to getString(R.string.log_viewer_filter_ops),
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = UiComponents.roundedDrawable(
                fillColor = palette.panelBg,
                radiusPx = dpf(13f),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }

        val tabs = ArrayList<LinearLayout>(filters.size)
        val labels = ArrayList<TextView>(filters.size)
        filters.forEach { (filter, label) ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(7), dp(4), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (activeFilter != filter) {
                        activeFilter = filter
                        updateFilterBarStyles()
                        reloadLogs(scrollToBottom = true)
                    }
                }
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            tab.addView(tv)
            if (filter == AppLogStore.Filter.WARN_ERROR) {
                val badge = TextView(this).apply {
                    visibility = View.GONE
                    textSize = 9.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(4) }
                }
                warnTabBadgeView = badge
                tab.addView(badge)
            }
            tabs.add(tab)
            labels.add(tv)
            container.addView(tab)
        }
        filterTabViews = tabs
        filterTabLabels = labels
        updateFilterBarStyles()
        return container
    }

    private fun updateFilterBarStyles() {
        val filters = listOf(
            AppLogStore.Filter.ALL,
            AppLogStore.Filter.WARN_ERROR,
            AppLogStore.Filter.CORE,
            AppLogStore.Filter.OPS,
        )
        filters.forEachIndexed { idx, filter ->
            val selected = filter == activeFilter
            val tab = filterTabViews.getOrNull(idx) ?: return@forEachIndexed
            val tv = filterTabLabels.getOrNull(idx) ?: return@forEachIndexed
            tab.background = if (selected) {
                UiComponents.roundedDrawable(palette.accent, dpf(10f))
            } else {
                null
            }
            tv.setTextColor(if (selected) Color.WHITE else palette.textSecondary)
            tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        updateWarnErrIndicators()
    }

    private fun updateWarnErrIndicators() {
        val warnPill = warnPillView
        val errPill = errPillView
        if (warnPill != null) {
            if (totalWarnCount > 0) {
                warnPill.text = getString(R.string.log_viewer_warn_count_fmt, totalWarnCount)
                warnPill.visibility = View.VISIBLE
            } else {
                warnPill.visibility = View.GONE
            }
        }
        if (errPill != null) {
            if (totalErrCount > 0) {
                errPill.text = getString(R.string.log_viewer_err_count_fmt, totalErrCount)
                errPill.visibility = View.VISIBLE
            } else {
                errPill.visibility = View.GONE
            }
        }
        val badge = warnTabBadgeView ?: return
        val issueCount = totalWarnCount + totalErrCount
        if (issueCount > 0) {
            val isWarnTabActive = activeFilter == AppLogStore.Filter.WARN_ERROR
            badge.text = if (issueCount > 99) "99+" else issueCount.toString()
            badge.visibility = View.VISIBLE
            if (isWarnTabActive) {
                badge.setTextColor(Color.WHITE)
                badge.background = UiComponents.roundedDrawable(Color.argb(58, 255, 255, 255), dpf(999f))
            } else {
                val hasErr = totalErrCount > 0
                badge.setTextColor(if (hasErr) palette.statusError else palette.statusWarning)
                badge.background = UiComponents.roundedDrawable(
                    if (hasErr) logColors.errBg else logColors.warnBg,
                    dpf(999f),
                )
            }
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun toggleWarnErrorFilter() {
        activeFilter = if (activeFilter == AppLogStore.Filter.WARN_ERROR) {
            AppLogStore.Filter.ALL
        } else {
            AppLogStore.Filter.WARN_ERROR
        }
        updateFilterBarStyles()
        reloadLogs(scrollToBottom = true)
    }

    private fun buildSearchAndModeBar(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            background = UiComponents.roundedDrawable(
                fillColor = palette.panelBg,
                radiusPx = dpf(11f),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(8) }
        }

        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_search)
            setColorFilter(palette.textTertiary)
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(7) }
        }
        searchBox.addView(searchIcon)

        val editText = EditText(this).apply {
            hint = getString(R.string.log_viewer_search_hint)
            setHintTextColor(palette.textTertiary)
            setTextColor(palette.textPrimary)
            textSize = 12.5f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim().orEmpty()
                    searchClearBtn?.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                    if (q != searchQuery) {
                        searchQuery = q
                        applySearchAndRender(scrollToBottom = false)
                    }
                }
            })
        }
        searchInputView = editText
        searchBox.addView(editText)

        val clearBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_ui_close)
            setColorFilter(palette.textTertiary)
            contentDescription = getString(R.string.log_viewer_search_clear)
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setOnClickListener {
                searchInputView?.setText("")
            }
        }
        searchClearBtn = clearBtn
        searchBox.addView(clearBtn)
        row.addView(searchBox)

        val modeBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(11), 0, dp(11), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                isRawMode = !isRawMode
                updateModeToggleStyle()
                applySearchAndRender(scrollToBottom = true)
            }
        }
        modeToggleBtn = modeBtn
        updateModeToggleStyle()
        row.addView(modeBtn)
        return row
    }

    private fun updateModeToggleStyle() {
        val btn = modeToggleBtn ?: return
        btn.text = getString(if (isRawMode) R.string.log_viewer_mode_structured else R.string.log_viewer_mode_raw)
        if (isRawMode) {
            btn.setTextColor(palette.accentBright)
            btn.background = UiComponents.rippleSurface(
                fillColor = palette.accentSoftBg,
                radiusPx = dpf(11f),
                rippleColor = palette.ripple,
                strokeColor = palette.accentBright,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
        } else {
            btn.setTextColor(palette.textSecondary)
            btn.background = UiComponents.rippleSurface(
                fillColor = palette.panelBg,
                radiusPx = dpf(11f),
                rippleColor = palette.ripple,
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
        }
    }

    private fun buildLogPanel(): FrameLayout {
        val panel = FrameLayout(this).apply {
            background = UiComponents.roundedDrawable(
                fillColor = palette.panelBg,
                radiusPx = dpf(20f),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            clipChildren = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { bottomMargin = dp(12) }
        }

        val lv = ListView(this).apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(dp(10), dp(10), dp(10), dp(48))
            isVerticalScrollBarEnabled = true
            setSelector(android.R.color.transparent)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int,
                ) {
                    val atBottom = totalItemCount == 0 || (firstVisibleItem + visibleItemCount >= totalItemCount - 1)
                    jumpLatestBtn?.visibility = if (!isRawMode && !atBottom && totalItemCount > 3) View.VISIBLE else View.GONE
                }
            })
        }
        val adapter = StructuredLogAdapter()
        lv.adapter = adapter
        listView = lv
        listAdapter = adapter
        panel.addView(lv)

        val scroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            setPadding(dp(12), dp(12), dp(12), dp(48))
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val rawTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1.18f)
            setTextColor(palette.textPrimary)
            setTextIsSelectable(true)
        }
        scroll.addView(rawTv)
        rawScrollView = scroll
        rawTextView = rawTv
        panel.addView(scroll)

        val empty = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(palette.textTertiary)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        emptyView = empty
        panel.addView(empty)

        val jumpBtn = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(11), dp(6))
            background = UiComponents.rippleSurface(
                fillColor = palette.panelBg,
                radiusPx = dpf(999f),
                rippleColor = palette.ripple,
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            elevation = dpf(4f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                marginEnd = dp(12)
                bottomMargin = dp(10)
            }
            setOnClickListener { scrollToLatest() }
        }
        jumpBtn.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_ui_chevron_down)
                setColorFilter(palette.accentBright)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(4) }
            },
        )
        jumpBtn.addView(
            TextView(this).apply {
                text = getString(R.string.log_viewer_jump_latest)
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        jumpLatestBtn = jumpBtn
        panel.addView(jumpBtn)

        return panel
    }

    private fun buildBottomActions(): LinearLayout {
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionsRow.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.log_viewer_export), primary = true) {
                exportAndShareLogs()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f).apply {
                    marginEnd = dp(8)
                }
            },
        )
        actionsRow.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.log_viewer_copy)) {
                copyVisibleLogs()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            },
        )
        val clearBtn = TextView(this).apply {
            text = getString(R.string.log_viewer_clear)
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(palette.statusError)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = UiComponents.rippleSurface(
                fillColor = logColors.dangerBg,
                radiusPx = dpf(UiPalette.RADIUS_CONTROL_DP.toFloat()),
                rippleColor = palette.ripple,
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f)
            setOnClickListener { confirmClearLogs() }
        }
        actionsRow.addView(clearBtn)
        return actionsRow
    }

    private fun reloadLogs(scrollToBottom: Boolean) {
        val filter = activeFilter
        ioExecutor.execute {
            val store = AppLog.init(applicationContext)
            val lines = store.readFilteredLines(filter)
            val allMerged = if (filter == AppLogStore.Filter.ALL) lines else store.readMergedRuntimeLines()
            var warnCount = 0
            var errCount = 0
            for (line in allMerged) {
                if (line.contains(" W/")) warnCount++
                else if (line.contains(" E/")) errCount++
            }
            val parsed = LogEntryParser.parseLines(lines, defaultIsOps = filter == AppLogStore.Filter.OPS)
            val sizeText = AppLogStore.formatSize(store.totalSizeBytes())
            main.post {
                if (isFinishing || isDestroyed) return@post
                allFilterEntries = parsed
                totalSizeText = sizeText
                totalWarnCount = warnCount
                totalErrCount = errCount
                expandedJsonIds.clear()
                prettyJsonCache.clear()
                updateWarnErrIndicators()
                applySearchAndRender(scrollToBottom = scrollToBottom)
            }
        }
    }

    private fun applySearchAndRender(scrollToBottom: Boolean) {
        val q = searchQuery
        visibleEntries = if (q.isEmpty()) {
            allFilterEntries
        } else {
            allFilterEntries.filter { it.raw.contains(q, ignoreCase = true) }
        }

        summaryView?.text = getString(R.string.log_viewer_summary_fmt, visibleEntries.size, totalSizeText)

        if (visibleEntries.isEmpty()) {
            listView?.visibility = View.GONE
            rawScrollView?.visibility = View.GONE
            jumpLatestBtn?.visibility = View.GONE
            emptyView?.visibility = View.VISIBLE
            emptyView?.text = getString(
                if (q.isNotEmpty() || activeFilter != AppLogStore.Filter.ALL) {
                    R.string.log_viewer_empty_filtered
                } else {
                    R.string.log_viewer_empty
                },
            )
            return
        }

        emptyView?.visibility = View.GONE
        if (isRawMode) {
            listView?.visibility = View.GONE
            jumpLatestBtn?.visibility = View.GONE
            rawScrollView?.visibility = View.VISIBLE
            val rawAll = visibleEntries.joinToString("\n") { it.raw }
            rawTextView?.text = highlightSearch(rawAll, q)
            if (scrollToBottom) {
                rawScrollView?.post { rawScrollView?.fullScroll(View.FOCUS_DOWN) }
            }
        } else {
            rawScrollView?.visibility = View.GONE
            listView?.visibility = View.VISIBLE
            val rows = buildListRows(visibleEntries)
            listAdapter?.submit(rows)
            if (scrollToBottom) {
                scrollToLatest()
            }
        }
    }

    private fun buildListRows(entries: List<LogEntryParser.LogEntry>): List<ListRow> {
        val rows = ArrayList<ListRow>(entries.size + 4)
        var lastDate: String? = null
        for (entry in entries) {
            val d = entry.date
            if (d != null && d != lastDate) {
                rows.add(ListRow.DateDivider(d))
                lastDate = d
            }
            rows.add(ListRow.Entry(entry))
        }
        return rows
    }

    private fun scrollToLatest() {
        if (isRawMode) {
            rawScrollView?.post { rawScrollView?.fullScroll(View.FOCUS_DOWN) }
        } else {
            val lv = listView ?: return
            val count = listAdapter?.count ?: 0
            if (count > 0) {
                lv.post { lv.setSelection(count - 1) }
            }
        }
    }

    private fun copyText(label: String, text: String, toastRes: Int) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(toastRes), Toast.LENGTH_SHORT).show()
    }

    private fun copyVisibleLogs() {
        if (visibleEntries.isEmpty()) {
            Toast.makeText(this, getString(R.string.log_viewer_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val text = visibleEntries.joinToString("\n") { it.raw }
        copyText("CozyTing Logs", text, R.string.log_viewer_copied)
    }

    private fun exportAndShareLogs() {
        val header = buildDiagnosticHeader()
        ioExecutor.execute {
            val result = runCatching {
                val file = AppLog.exportFile(applicationContext, header)
                val authority = "${applicationContext.packageName}.update.fileprovider"
                val uri = FileProvider.getUriForFile(applicationContext, authority, file)
                file to uri
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { (file, uri) ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.log_viewer_export_chooser)))
                    }.onFailure {
                        Toast.makeText(this, getString(R.string.log_viewer_export_failed), Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(this, getString(R.string.log_viewer_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmClearLogs() {
        runCatching { clearDialog?.dismiss() }
        clearDialog = UiComponents.showStyleBDialog(
            activity = this,
            palette = palette,
            title = getString(R.string.log_viewer_clear_title),
            message = getString(R.string.log_viewer_clear_msg),
            primaryLabel = getString(R.string.log_viewer_clear_confirm),
            secondaryLabel = getString(R.string.common_cancel),
            onPrimary = {
                ioExecutor.execute {
                    AppLog.clearAll(applicationContext)
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        Toast.makeText(this, getString(R.string.log_viewer_cleared), Toast.LENGTH_SHORT).show()
                        reloadLogs(scrollToBottom = false)
                    }
                }
            },
            onDismissed = { clearDialog = null },
        )
    }

    private fun buildDiagnosticHeader(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("=== CozyTing Diagnostic Logs ===")
            appendLine("exported_at: $now")
            appendLine("app_version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("package: ${BuildConfig.APPLICATION_ID}")
            appendLine("android_sdk: ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
        }
    }

    private fun highlightSearch(text: String, query: String): CharSequence {
        if (query.isEmpty()) return text
        val lowerText = text.lowercase(Locale.ROOT)
        val lowerQuery = query.lowercase(Locale.ROOT)
        var start = lowerText.indexOf(lowerQuery)
        if (start < 0) return text
        val spannable = SpannableString(text)
        while (start >= 0) {
            val end = start + query.length
            spannable.setSpan(
                BackgroundColorSpan(logColors.searchHighlight),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            start = lowerText.indexOf(lowerQuery, end)
        }
        return spannable
    }

    private fun highlightJsonSyntax(prettyJson: String): CharSequence {
        val sb = SpannableStringBuilder(prettyJson)
        val regex = Regex("""("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?""")
        for (match in regex.findAll(prettyJson)) {
            val start = match.range.first
            val end = match.range.last + 1
            val token = match.value
            val color = when {
                match.groups[2] != null -> {
                    val keyEnd = match.groups[1]!!.range.last + 1
                    sb.setSpan(ForegroundColorSpan(logColors.jsonKey), start, keyEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    continue
                }
                token.startsWith("\"") -> logColors.jsonStr
                token == "true" || token == "false" || token == "null" -> logColors.jsonBool
                else -> logColors.jsonNum
            }
            sb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun getFormattedJson(entry: LogEntryParser.LogEntry): CharSequence {
        return prettyJsonCache.getOrPut(entry.id) {
            val pretty = LogEntryParser.prettyFormatJson(entry.jsonRaw.orEmpty())
            highlightJsonSyntax(pretty)
        }
    }

    private inner class StructuredLogAdapter : BaseAdapter() {
        private var items: List<ListRow> = emptyList()

        fun submit(newItems: List<ListRow>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): ListRow = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is ListRow.DateDivider -> 0
            is ListRow.Entry -> 1
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val item = items[position]) {
                is ListRow.DateDivider -> bindDateDivider(item, convertView)
                is ListRow.Entry -> bindEntryRow(item.entry, convertView)
            }
        }

        private fun bindDateDivider(item: ListRow.DateDivider, convertView: View?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@LogViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(6), dp(2), dp(8))
                val leftLine = View(this@LogViewerActivity).apply {
                    setBackgroundColor(palette.panelBorder)
                    layoutParams = LinearLayout.LayoutParams(0, dp(1).coerceAtLeast(1), 1f)
                }
                val label = TextView(this@LogViewerActivity).apply {
                    tag = "dateLabel"
                    typeface = Typeface.MONOSPACE
                    textSize = 10.5f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(palette.textTertiary)
                    setPadding(dp(8), 0, dp(8), 0)
                }
                val rightLine = View(this@LogViewerActivity).apply {
                    setBackgroundColor(palette.panelBorder)
                    layoutParams = LinearLayout.LayoutParams(0, dp(1).coerceAtLeast(1), 1f)
                }
                addView(leftLine)
                addView(label)
                addView(rightLine)
            }
            row.findViewWithTag<TextView>("dateLabel").text = item.date
            return row
        }

        private fun bindEntryRow(entry: LogEntryParser.LogEntry, convertView: View?): View {
            val holder: EntryViewHolder
            val rootView: FrameLayout
            if (convertView is FrameLayout && convertView.tag is EntryViewHolder) {
                rootView = convertView
                holder = convertView.tag as EntryViewHolder
            } else {
                holder = createEntryViewHolder()
                rootView = holder.wrapper
                rootView.tag = holder
            }

            // Card background & left accent bar
            val cardBg = when (entry.level) {
                'E' -> logColors.rowErrBg
                'W' -> logColors.rowWarnBg
                else -> logColors.rowBg
            }
            holder.card.background = UiComponents.roundedDrawable(
                fillColor = cardBg,
                radiusPx = dpf(12f),
                strokeColor = palette.rowIdleBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            val barColor = when {
                entry.level == 'E' -> palette.statusError
                entry.level == 'W' -> palette.statusWarning
                entry.isCore -> logColors.coreFg
                entry.isOps -> logColors.opsFg
                else -> palette.accentBright
            }
            holder.accentBar.background = UiComponents.roundedDrawable(barColor, dpf(3f))

            // Meta header
            if (entry.time != null) {
                holder.timeView.text = entry.time
                holder.timeView.visibility = View.VISIBLE
            } else {
                holder.timeView.visibility = View.GONE
            }

            val (lvlFg, lvlBg) = when (entry.level) {
                'E' -> palette.statusError to logColors.errBg
                'W' -> palette.statusWarning to logColors.warnBg
                else -> palette.accentBright to palette.accentSoftBg
            }
            holder.levelBadge.text = entry.level.toString()
            holder.levelBadge.setTextColor(lvlFg)
            holder.levelBadge.background = UiComponents.roundedDrawable(lvlBg, dpf(5f))

            if (entry.tag.isNotEmpty()) {
                val (tagFg, tagBg) = when {
                    entry.isCore -> logColors.coreFg to logColors.coreBg
                    entry.isOps -> logColors.opsFg to logColors.opsBg
                    else -> palette.textSecondary to palette.controlSurface
                }
                holder.tagBadge.text = entry.tag
                holder.tagBadge.setTextColor(tagFg)
                holder.tagBadge.background = UiComponents.roundedDrawable(tagBg, dpf(5f))
                holder.tagBadge.visibility = View.VISIBLE
            } else {
                holder.tagBadge.visibility = View.GONE
            }

            holder.copyBtn.setOnClickListener {
                copyText("CozyTing Log Entry", entry.raw, R.string.log_viewer_entry_copied)
            }

            // Event title
            if (entry.eventTitle.isNotEmpty()) {
                holder.eventTitle.text = highlightSearch(entry.eventTitle, searchQuery)
                holder.eventTitle.setTextColor(
                    when (entry.level) {
                        'E' -> palette.statusError
                        'W' -> palette.statusWarning
                        else -> palette.textPrimary
                    },
                )
                holder.eventTitle.visibility = View.VISIBLE
            } else {
                holder.eventTitle.visibility = View.GONE
            }

            // Key-value chips
            holder.kvContainer.removeAllViews()
            if (entry.kvPairs.isNotEmpty()) {
                holder.kvContainer.visibility = View.VISIBLE
                for ((k, v) in entry.kvPairs) {
                    holder.kvContainer.addView(buildKvChip(k, v))
                }
            } else {
                holder.kvContainer.visibility = View.GONE
            }

            // Plain body if any
            if (!entry.plainBody.isNullOrEmpty()) {
                holder.plainBody.text = highlightSearch(entry.plainBody, searchQuery)
                holder.plainBody.visibility = View.VISIBLE
            } else {
                holder.plainBody.visibility = View.GONE
            }

            // Collapsible JSON section
            val jsonSummary = entry.jsonSummary
            if (entry.jsonRaw != null && jsonSummary != null) {
                val expanded = expandedJsonIds.contains(entry.id)
                holder.jsonToggleRow.visibility = View.VISIBLE
                val sizeStr = AppLogStore.formatSize(jsonSummary.byteCount.toLong())
                val count = jsonSummary.itemCount ?: 0
                holder.jsonSummaryText.text = getString(R.string.log_viewer_json_summary_items_fmt, count, sizeStr)
                holder.jsonActionText.text = getString(
                    if (expanded) R.string.log_viewer_json_collapse else R.string.log_viewer_json_expand,
                )
                holder.jsonToggleRow.setOnClickListener {
                    if (expanded) {
                        expandedJsonIds.remove(entry.id)
                    } else {
                        expandedJsonIds.add(entry.id)
                    }
                    notifyDataSetChanged()
                }
                if (expanded) {
                    holder.jsonScroll.visibility = View.VISIBLE
                    holder.jsonContent.text = getFormattedJson(entry)
                } else {
                    holder.jsonScroll.visibility = View.GONE
                }
            } else {
                holder.jsonToggleRow.visibility = View.GONE
                holder.jsonScroll.visibility = View.GONE
            }

            return rootView
        }

        private fun buildKvChip(key: String, value: String): TextView {
            val sb = SpannableStringBuilder()
            sb.append(key)
            sb.append("=")
            sb.setSpan(ForegroundColorSpan(palette.textTertiary), 0, key.length + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val valStart = sb.length
            sb.append(value)
            val valColor = when (value) {
                "true" -> palette.statusSuccess
                "false" -> palette.textTertiary
                else -> palette.textPrimary
            }
            sb.setSpan(ForegroundColorSpan(valColor), valStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (searchQuery.isNotEmpty()) {
                val full = sb.toString().lowercase(Locale.ROOT)
                val q = searchQuery.lowercase(Locale.ROOT)
                var idx = full.indexOf(q)
                while (idx >= 0) {
                    sb.setSpan(
                        BackgroundColorSpan(logColors.searchHighlight),
                        idx,
                        idx + q.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    idx = full.indexOf(q, idx + q.length)
                }
            }
            return TextView(this@LogViewerActivity).apply {
                text = sb
                typeface = Typeface.MONOSPACE
                textSize = 10.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = UiComponents.roundedDrawable(palette.controlSurface, dpf(6f))
            }
        }

        private fun createEntryViewHolder(): EntryViewHolder {
            val wrapper = FrameLayout(this@LogViewerActivity).apply {
                setPadding(0, 0, 0, dp(7))
            }
            val card = FrameLayout(this@LogViewerActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val accentBar = View(this@LogViewerActivity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                }
            }
            card.addView(accentBar)

            val contentCol = LinearLayout(this@LogViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(10), dp(8))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            // Meta row
            val metaRow = LinearLayout(this@LogViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(4) }
            }
            val timeView = TextView(this@LogViewerActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10.5f
                setTextColor(palette.textTertiary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            }
            val levelBadge = TextView(this@LogViewerActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 9.5f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            }
            val tagBadge = TextView(this@LogViewerActivity).apply {
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val spacer = View(this@LogViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            val copyBtn = TextView(this@LogViewerActivity).apply {
                text = getString(R.string.log_viewer_copy)
                contentDescription = getString(R.string.log_viewer_copy_entry)
                textSize = 10f
                setTextColor(palette.textTertiary)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = UiComponents.rippleSurface(
                    fillColor = Color.TRANSPARENT,
                    radiusPx = dpf(6f),
                    rippleColor = palette.ripple,
                )
            }
            metaRow.addView(timeView)
            metaRow.addView(levelBadge)
            metaRow.addView(tagBadge)
            metaRow.addView(spacer)
            metaRow.addView(copyBtn)
            contentCol.addView(metaRow)

            // Event title
            val eventTitle = TextView(this@LogViewerActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setLineSpacing(dp(1).toFloat(), 1.15f)
            }
            contentCol.addView(eventTitle)

            // KV wrap container
            val kvContainer = FlowWrapLayout(this@LogViewerActivity, hGap = dp(5), vGap = dp(4)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
            }
            contentCol.addView(kvContainer)

            // Optional plain body
            val plainBody = TextView(this@LogViewerActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(palette.textSecondary)
                setLineSpacing(dp(1).toFloat(), 1.15f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(3) }
            }
            contentCol.addView(plainBody)

            // Collapsible JSON toggle bar
            val jsonToggleRow = LinearLayout(this@LogViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = UiComponents.rippleSurface(
                    fillColor = logColors.jsonBg,
                    radiusPx = dpf(8f),
                    rippleColor = palette.ripple,
                    strokeColor = palette.panelBorder,
                    strokeWidthPx = dp(1).coerceAtLeast(1),
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            }
            val jsonBadge = TextView(this@LogViewerActivity).apply {
                text = "JSON"
                typeface = Typeface.MONOSPACE
                textSize = 9.5f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(palette.accentBright)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = UiComponents.roundedDrawable(palette.accentSoftBg, dpf(4f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            }
            val jsonSummaryText = TextView(this@LogViewerActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10.5f
                setTextColor(palette.textSecondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val jsonActionText = TextView(this@LogViewerActivity).apply {
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.accentBright)
            }
            jsonToggleRow.addView(jsonBadge)
            jsonToggleRow.addView(jsonSummaryText)
            jsonToggleRow.addView(jsonActionText)
            contentCol.addView(jsonToggleRow)

            // Formatted JSON viewer
            val jsonScroll = HorizontalScrollView(this@LogViewerActivity).apply {
                isHorizontalScrollBarEnabled = true
                background = UiComponents.roundedDrawable(
                    fillColor = logColors.jsonBg,
                    radiusPx = dpf(8f),
                    strokeColor = palette.panelBorder,
                    strokeWidthPx = dp(1).coerceAtLeast(1),
                )
                setPadding(dp(8), dp(7), dp(8), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) }
            }
            val jsonContent = TextView(this@LogViewerActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10.5f
                setLineSpacing(dp(1).toFloat(), 1.15f)
                setTextColor(palette.textSecondary)
                setTextIsSelectable(true)
            }
            jsonScroll.addView(jsonContent)
            contentCol.addView(jsonScroll)

            card.addView(contentCol)
            wrapper.addView(card)

            return EntryViewHolder(
                wrapper = wrapper,
                card = card,
                accentBar = accentBar,
                timeView = timeView,
                levelBadge = levelBadge,
                tagBadge = tagBadge,
                copyBtn = copyBtn,
                eventTitle = eventTitle,
                kvContainer = kvContainer,
                plainBody = plainBody,
                jsonToggleRow = jsonToggleRow,
                jsonSummaryText = jsonSummaryText,
                jsonActionText = jsonActionText,
                jsonScroll = jsonScroll,
                jsonContent = jsonContent,
            )
        }
    }

    private data class EntryViewHolder(
        val wrapper: FrameLayout,
        val card: FrameLayout,
        val accentBar: View,
        val timeView: TextView,
        val levelBadge: TextView,
        val tagBadge: TextView,
        val copyBtn: TextView,
        val eventTitle: TextView,
        val kvContainer: FlowWrapLayout,
        val plainBody: TextView,
        val jsonToggleRow: LinearLayout,
        val jsonSummaryText: TextView,
        val jsonActionText: TextView,
        val jsonScroll: HorizontalScrollView,
        val jsonContent: TextView,
    )

    /** 轻量自适应流式换行容器（零外部依赖，用于排布 `key=value` 胶囊）。 */
    private class FlowWrapLayout(
        context: Context,
        private val hGap: Int,
        private val vGap: Int,
    ) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val childWidthSpec = if (maxWidth > 0) {
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            } else {
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            }
            val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

            var lineWidth = 0
            var lineHeight = 0
            var totalHeight = paddingTop + paddingBottom
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                child.measure(childWidthSpec, childHeightSpec)
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (lineWidth > 0 && maxWidth > 0 && lineWidth + hGap + cw > maxWidth) {
                    totalHeight += lineHeight + vGap
                    lineWidth = cw
                    lineHeight = ch
                } else {
                    lineWidth = if (lineWidth == 0) cw else lineWidth + hGap + cw
                    if (ch > lineHeight) lineHeight = ch
                }
            }
            totalHeight += lineHeight
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxWidth = (r - l) - paddingLeft - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (x > paddingLeft && maxWidth > 0 && (x - paddingLeft) + cw > maxWidth) {
                    x = paddingLeft
                    y += lineHeight + vGap
                    lineHeight = 0
                }
                child.layout(x, y, x + cw, y + ch)
                x += cw + hGap
                if (ch > lineHeight) lineHeight = ch
            }
        }
    }

    private fun dp(v: Int): Int = UiComponents.dp(this, v)
    private fun dpf(v: Float): Float = UiComponents.dpf(this, v)
}
