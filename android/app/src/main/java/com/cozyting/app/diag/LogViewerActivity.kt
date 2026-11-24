package com.cozyting.app.diag

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
 * 独立运行日志浏览与导出页面（Style B 视觉，支持明暗主题与中/英/日三语）。
 *
 * - 浏览本地异步轮转的 Kotlin + Rust `CozyTingCore` 日志及 `cast_ops.log`；
 * - 支持按分类筛选（全部 / 警告与错误 / 核心协议 / 操作流水）、刷新、复制、系统分享导出与清空；
 * - 严格遵守纯本地沙箱原则：不联网、不自动上传，仅在用户主动点击「导出」时通过 `FileProvider` 交由系统分享。
 */
class LogViewerActivity : Activity() {
    private lateinit var settingsStore: PrefsCastSettingsStore
    private lateinit var palette: UiPalette
    private val main = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cozyting-log-viewer").apply { isDaemon = true }
    }

    private var activeFilter: AppLogStore.Filter = AppLogStore.Filter.ALL
    private var currentLines: List<String> = emptyList()
    private var summaryView: TextView? = null
    private var logTextView: TextView? = null
    private var logScrollView: ScrollView? = null
    private var clearDialog: Dialog? = null

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

    private fun renderUi() {
        val isDark = settingsStore.isDark(this)
        palette = UiPalette.forMode(isDark)
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

        // —— 顶栏 ——
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
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        summaryView = TextView(this).apply {
            text = getString(R.string.log_viewer_loading)
            textSize = 11.5f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(summaryView)
        topBar.addView(titleCol)
        topBar.addView(
            UiComponents.iconCircleButton(this, palette, R.drawable.ic_ui_refresh, getString(R.string.log_viewer_refresh)) {
                reloadLogs(scrollToBottom = true)
            },
        )
        root.addView(topBar)

        // —— 分类筛选分段控制 ——
        val filters = listOf(
            AppLogStore.Filter.ALL to getString(R.string.log_viewer_filter_all),
            AppLogStore.Filter.WARN_ERROR to getString(R.string.log_viewer_filter_warn_error),
            AppLogStore.Filter.CORE to getString(R.string.log_viewer_filter_core),
            AppLogStore.Filter.OPS to getString(R.string.log_viewer_filter_ops),
        )
        val initialIdx = filters.indexOfFirst { it.first == activeFilter }.coerceAtLeast(0)
        root.addView(
            UiComponents.segmentedControl(
                context = this,
                palette = palette,
                labels = filters.map { it.second },
                initialIndex = initialIdx,
            ) { idx ->
                activeFilter = filters[idx].first
                reloadLogs(scrollToBottom = true)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            },
        )

        // —— 日志正文卡片（等宽字体、可复制选中、自动滚到底部） ——
        val logCard = UiComponents.panelCard(this, palette, padDp = 12).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { bottomMargin = dp(12) }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setTextColor(palette.textPrimary)
            setTextIsSelectable(true)
            text = getString(R.string.log_viewer_loading)
        }
        scroll.addView(tv)
        logCard.addView(scroll)
        root.addView(logCard)
        logScrollView = scroll
        logTextView = tv

        // —— 底部操作栏：导出分享 / 复制 / 清空 ——
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionsRow.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.log_viewer_export), primary = true) {
                exportAndShareLogs()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply {
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
        actionsRow.addView(
            UiComponents.tonalButton(this, palette, getString(R.string.log_viewer_clear)) {
                confirmClearLogs()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
            },
        )
        root.addView(actionsRow)

        setContentView(outer)
    }

    private fun reloadLogs(scrollToBottom: Boolean) {
        val filter = activeFilter
        ioExecutor.execute {
            val store = AppLog.init(applicationContext)
            val lines = store.readFilteredLines(filter)
            val sizeText = AppLogStore.formatSize(store.totalSizeBytes())
            main.post {
                if (isFinishing || isDestroyed) return@post
                currentLines = lines
                summaryView?.text = getString(R.string.log_viewer_summary_fmt, lines.size, sizeText)
                logTextView?.text = if (lines.isEmpty()) {
                    getString(R.string.log_viewer_empty)
                } else {
                    lines.joinToString("\n")
                }
                if (scrollToBottom) {
                    logScrollView?.post {
                        logScrollView?.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    private fun copyVisibleLogs() {
        val text = if (currentLines.isEmpty()) "" else currentLines.joinToString("\n")
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.log_viewer_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("CozyTing Logs", text))
        Toast.makeText(this, getString(R.string.log_viewer_copied), Toast.LENGTH_SHORT).show()
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

    private fun dp(v: Int): Int = UiComponents.dp(this, v)
}
