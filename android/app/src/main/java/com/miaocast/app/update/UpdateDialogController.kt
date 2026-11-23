package com.miaocast.app.update

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.miaocast.app.BuildConfig
import com.miaocast.app.R
import com.miaocast.app.compat.ApkInstallerCompat
import com.miaocast.app.ui.UiComponents
import com.miaocast.app.ui.UiPalette
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Style B 更新详情与安全下载安装弹窗（供 MainActivity 与 SettingsActivity 复用）：
 * - 单版本升级直出变更条目；跨多版本升级按新到旧分节展示 `(current, target]` 区间差异日志；
 * - 支持应用内流式下载（4dp 胶囊进度条）+ 强制 SHA-256 与签名证书校验 + 未知来源权限引导；
 * - 始终保留「前往浏览器下载」与「稍后提醒 / 跳过此版本」非侵入操作。
 */
object UpdateDialogController {

    private val downloadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "miaocast-apk-dl").apply { isDaemon = true }
    }

    fun show(
        activity: Activity,
        palette: UiPalette,
        decision: UpdateDecision,
        onDismissed: () -> Unit = {},
    ): Dialog {
        val main = Handler(Looper.getMainLooper())
        val cancelled = AtomicBoolean(false)
        var downloading = false
        var verifiedApk: File? = null

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val dp: (Int) -> Int = { UiComponents.dp(activity, it) }
        val dpf: (Float) -> Float = { UiComponents.dpf(activity, it) }

        val dialogSolidBg = if (palette.isDark) Color.rgb(21, 26, 35) else Color.WHITE
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = dialogSolidBg,
                radiusPx = dpf(UiPalette.RADIUS_PANEL_DP.toFloat()),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        // —— 顶栏：标题 + 版本徽标 + 关闭按钮 ——
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.update_dialog_title)
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        titleRow.addView(
            TextView(activity).apply {
                text = decision.latestTag.ifBlank { "v${decision.latestVersion}" }
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.accentBright)
                background = UiComponents.roundedDrawable(palette.accentSoftBg, dpf(999f))
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }
            },
        )
        if (decision.isPrerelease) {
            titleRow.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.update_dialog_preview_badge)
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.statusWarning)
                    background = UiComponents.roundedDrawable(
                        Color.argb(36, Color.red(palette.statusWarning), Color.green(palette.statusWarning), Color.blue(palette.statusWarning)),
                        dpf(999f),
                    )
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(6) }
                },
            )
        }
        titleCol.addView(titleRow)

        val metaParts = mutableListOf<String>()
        if (decision.shortDate.isNotBlank()) metaParts += activity.getString(R.string.update_dialog_published_at, decision.shortDate)
        if (decision.assetSizeBytes > 0L) metaParts += activity.getString(R.string.update_dialog_apk_size, formatBytes(decision.assetSizeBytes))
        if (decision.versionsBehind > 1) metaParts += activity.getString(R.string.update_dialog_versions_behind, decision.versionsBehind)
        if (metaParts.isNotEmpty()) {
            titleCol.addView(
                TextView(activity).apply {
                    text = metaParts.joinToString(" · ")
                    textSize = 11.5f
                    setTextColor(palette.textSecondary)
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }
        header.addView(titleCol)
        header.addView(
            UiComponents.iconCircleButton(activity, palette, R.drawable.ic_ui_close, activity.getString(R.string.common_close), sizeDp = 32) {
                cancelled.set(true)
                dialog.dismiss()
            },
        )
        card.addView(header)

        // —— 跨版本提示胶囊 / 验收包或系统不兼容提示 ——
        if (decision.status == UpdateDecisionStatus.UNSUPPORTED_OS) {
            card.addView(noticeBanner(activity, palette, activity.getString(R.string.update_dialog_unsupported_os), warning = true))
        } else if (BuildConfig.IS_ACCEPTANCE) {
            card.addView(noticeBanner(activity, palette, activity.getString(R.string.update_dialog_acceptance_notice, activity.packageName), warning = true))
        }

        // —— 中部：可滚动更新日志（单版直出 / 跨多版按版本分节） ——
        val notesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val entries = decision.changelogEntries
        if (entries.size > 1) {
            notesContainer.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.update_dialog_changelog_header, decision.currentVersion, decision.versionsBehind)
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.accentBright)
                    setPadding(0, 0, 0, dp(8))
                },
            )
            entries.forEachIndexed { idx, entry ->
                if (idx > 0) {
                    notesContainer.addView(
                        View(activity).apply {
                            setBackgroundColor(palette.panelBorder)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(1).coerceAtLeast(1),
                            ).apply {
                                topMargin = dp(8)
                                bottomMargin = dp(8)
                            }
                        },
                    )
                }
                val verHeader = buildString {
                    append(entry.tag)
                    if (entry.shortDate.isNotBlank()) append(" · ${entry.shortDate}")
                    if (entry.prerelease) append(" · ").append(activity.getString(R.string.update_dialog_preview_badge))
                }
                notesContainer.addView(
                    TextView(activity).apply {
                        text = verHeader
                        textSize = 12.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(palette.textPrimary)
                        setPadding(0, 0, 0, dp(4))
                    },
                )
                notesContainer.addView(
                    TextView(activity).apply {
                        text = entry.displayNotes(activity)
                        textSize = 12.5f
                        setTextColor(palette.textSecondary)
                        setLineSpacing(dpf(2f), 1f)
                    },
                )
            }
            if (decision.changelogTruncated) {
                notesContainer.addView(
                    TextView(activity).apply {
                        text = activity.getString(R.string.update_dialog_changelog_truncated)
                        textSize = 11f
                        setTextColor(palette.textTertiary)
                        setPadding(0, dp(8), 0, 0)
                    },
                )
            }
        } else {
            val singleText = entries.firstOrNull()?.displayNotes(activity)
                ?: decision.localizedReleaseNotes(activity)
            notesContainer.addView(
                TextView(activity).apply {
                    text = singleText
                    textSize = 13f
                    setTextColor(palette.textSecondary)
                    setLineSpacing(dpf(2.5f), 1f)
                },
            )
        }

        val notesScroll = ScrollView(activity).apply {
            background = UiComponents.roundedDrawable(
                fillColor = palette.rowIdleBg,
                radiusPx = dpf(UiPalette.RADIUS_CONTROL_DP.toFloat()),
                strokeColor = palette.rowIdleBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
            addView(notesContainer)
        }
        // 限制日志区最大高度约 230dp，防止跨多版本内容撑出屏幕
        val maxScrollHeight = dp(230)
        notesScroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height > maxScrollHeight) {
                v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { height = maxScrollHeight }
            }
        }
        card.addView(notesScroll)

        // —— 下载与校验进度区（默认隐藏，点击下载后显示） ——
        val progressBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(10))
        }
        val trackHeight = dp(4).coerceAtLeast(3)
        val trackWrap = FrameLayout(activity).apply {
            background = UiComponents.roundedDrawable(palette.trackIdle, trackHeight / 2f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, trackHeight)
        }
        val trackFill = View(activity).apply {
            background = UiComponents.roundedDrawable(palette.accentBright, trackHeight / 2f)
            layoutParams = FrameLayout.LayoutParams(0, trackHeight)
        }
        trackWrap.addView(trackFill)
        val progressLabel = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(6), 0, 0)
        }
        progressBox.addView(trackWrap)
        progressBox.addView(progressLabel)
        card.addView(progressBox)

        // —— 反馈提示区 ——
        val statusMsg = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(palette.statusWarning)
            setPadding(dp(2), 0, dp(2), dp(8))
            visibility = View.GONE
        }
        card.addView(statusMsg)

        // —— 底部操作区 ——
        val canInAppInstall = decision.installable &&
            decision.status != UpdateDecisionStatus.UNSUPPORTED_OS &&
            !BuildConfig.IS_ACCEPTANCE &&
            UpdateConfig.isAllowedHttpsUrl(decision.downloadUrl)

        var primaryBtn: TextView? = null
        fun launchInstall(apk: File) {
            if (!ApkInstallerCompat.canRequestPackageInstalls(activity)) {
                statusMsg.text = activity.getString(R.string.update_dialog_need_install_perm)
                statusMsg.setTextColor(palette.statusWarning)
                statusMsg.visibility = View.VISIBLE
                primaryBtn?.text = activity.getString(R.string.update_dialog_btn_install_after_perm)
                runCatching {
                    activity.startActivity(ApkInstallerCompat.buildUnknownSourcesSettingsIntent(activity.packageName))
                }
                return
            }
            val started = runCatching {
                activity.startActivity(ApkInstallerCompat.buildInstallIntent(activity, apk))
            }.isSuccess
            if (started) {
                dialog.dismiss()
            } else {
                statusMsg.text = activity.getString(R.string.update_dialog_err_open_installer)
                statusMsg.setTextColor(palette.statusError)
                statusMsg.visibility = View.VISIBLE
            }
        }

        if (canInAppInstall) {
            val btn = UiComponents.tonalButton(activity, palette, activity.getString(R.string.update_dialog_btn_download_install), primary = true) {
                val readyFile = verifiedApk
                if (readyFile != null && readyFile.isFile) {
                    launchInstall(readyFile)
                    return@tonalButton
                }
                if (downloading) {
                    cancelled.set(true)
                    return@tonalButton
                }
                downloading = true
                cancelled.set(false)
                statusMsg.visibility = View.GONE
                progressBox.visibility = View.VISIBLE
                progressLabel.text = activity.getString(R.string.update_dialog_progress_connecting)
                primaryBtn?.text = activity.getString(R.string.update_dialog_btn_cancel_download)

                downloadExecutor.execute {
                    val result = ApkUpdateDownloader.downloadAndVerify(
                        context = activity.applicationContext,
                        decision = decision,
                        cancelled = cancelled,
                    ) { p ->
                        main.post {
                            if (!dialog.isShowing) return@post
                            val pct = p.percent
                            val tw = trackWrap.width
                            if (tw > 0 && pct >= 0) {
                                trackFill.layoutParams = (trackFill.layoutParams as FrameLayout.LayoutParams).apply {
                                    width = ((tw * pct) / 100).coerceIn(0, tw)
                                }
                            }
                            progressLabel.text = when (p.stage) {
                                ApkDownloadProgress.Stage.FETCHING_CHECKSUM -> activity.getString(R.string.update_dialog_progress_checksum)
                                ApkDownloadProgress.Stage.DOWNLOADING -> {
                                    if (pct >= 0) {
                                        activity.getString(R.string.update_dialog_progress_downloading_pct, pct, formatBytes(p.bytesRead), formatBytes(p.totalBytes))
                                    } else {
                                        activity.getString(R.string.update_dialog_progress_downloading_bytes, formatBytes(p.bytesRead))
                                    }
                                }
                                ApkDownloadProgress.Stage.VERIFYING -> activity.getString(R.string.update_dialog_progress_verifying)
                            }
                        }
                    }
                    main.post {
                        downloading = false
                        if (!dialog.isShowing) return@post
                        when (result) {
                            is ApkUpdateDownloader.DownloadResult.Verified -> {
                                verifiedApk = result.apkFile
                                val tw = trackWrap.width
                                if (tw > 0) {
                                    trackFill.layoutParams = (trackFill.layoutParams as FrameLayout.LayoutParams).apply { width = tw }
                                }
                                progressLabel.text = activity.getString(R.string.update_dialog_progress_verified)
                                primaryBtn?.text = activity.getString(R.string.update_dialog_btn_install_now)
                                launchInstall(result.apkFile)
                            }
                            is ApkUpdateDownloader.DownloadResult.Cancelled -> {
                                progressBox.visibility = View.GONE
                                primaryBtn?.text = activity.getString(R.string.update_dialog_btn_download_install)
                            }
                            is ApkUpdateDownloader.DownloadResult.Failed -> {
                                progressBox.visibility = View.GONE
                                statusMsg.text = result.reasonZh
                                statusMsg.setTextColor(palette.statusError)
                                statusMsg.visibility = View.VISIBLE
                                primaryBtn?.text = activity.getString(R.string.update_dialog_btn_retry_download)
                            }
                        }
                    }
                }
            }
            primaryBtn = btn
            card.addView(btn)
        }

        val browserBtn = UiComponents.tonalButton(
            context = activity,
            palette = palette,
            label = if (canInAppInstall) {
                activity.getString(R.string.update_dialog_btn_browser_download)
            } else {
                activity.getString(R.string.update_dialog_btn_release_page)
            },
            primary = !canInAppInstall,
        ) {
            val targetUrl = decision.releaseUrl.takeIf { UpdateConfig.isAllowedHttpsUrl(it) }
                ?: UpdateConfig.PUBLIC_RELEASES_WEB_URL
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
            }
        }.apply {
            if (canInAppInstall) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            }
        }
        card.addView(browserBtn)

        // 底部轻操作行：稍后提醒 · 跳过此版本
        val footerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        footerRow.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.update_dialog_remind_later)
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                setPadding(dp(12), dp(6), dp(12), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    cancelled.set(true)
                    dialog.dismiss()
                }
            },
        )
        footerRow.addView(
            TextView(activity).apply {
                text = "·"
                textSize = 12.5f
                setTextColor(palette.textTertiary)
            },
        )
        footerRow.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.update_dialog_skip_version)
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                setPadding(dp(12), dp(6), dp(12), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    cancelled.set(true)
                    if (decision.latestTag.isNotBlank()) {
                        AppUpdateManager.ignoreVersion(activity, decision.latestTag)
                    }
                    dialog.dismiss()
                }
            },
        )
        card.addView(footerRow)

        val outer = FrameLayout(activity).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener {
            cancelled.set(true)
            onDismissed()
        }
        dialog.show()
        return dialog
    }

    private fun noticeBanner(
        activity: Activity,
        palette: UiPalette,
        msg: String,
        warning: Boolean,
    ): TextView {
        val color = if (warning) palette.statusWarning else palette.accentBright
        return TextView(activity).apply {
            text = msg
            textSize = 11.5f
            setTextColor(color)
            background = UiComponents.roundedDrawable(
                Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)),
                UiComponents.dpf(activity, 10f),
            )
            val p = UiComponents.dp(activity, 10)
            setPadding(p, UiComponents.dp(activity, 8), p, UiComponents.dp(activity, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = UiComponents.dp(activity, 10) }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
