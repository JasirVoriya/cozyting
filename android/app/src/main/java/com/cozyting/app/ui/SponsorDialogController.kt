package com.cozyting.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cozyting.app.R

/**
 * 纯静态自愿赞赏弹窗（Style B，支持明暗主题与三语本地化）：
 * - 零第三方支付 SDK、零遥测埋点，仅提供外部浏览器/支付宝跳转与本地打包二维码展示；
 * - 支持通过 API 29+ 原生 MediaStore 一键保存赞赏码到相册（无需额外存储权限）。
 */
object SponsorDialogController {

    const val AFDIAN_URL = "https://afdian.com/a/voriya"
    const val ALIPAY_DIRECT_URL = "https://qr.alipay.com/fkx13666yfftru6suyvjk59"

    fun show(
        activity: Activity,
        palette: UiPalette,
        onDismissed: () -> Unit = {},
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        val dp = { v: Int -> UiComponents.dp(activity, v) }
        val cardRadius = UiComponents.dpf(activity, 20f)
        val btnRadius = UiComponents.dpf(activity, 12f)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.roundedDrawable(
                fillColor = palette.panelBg,
                radiusPx = cardRadius,
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }

        // 标题 + 副标题说明
        container.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.sponsor_dialog_title)
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            },
        )
        container.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.sponsor_dialog_desc)
                textSize = 12.5f
                setLineSpacing(UiComponents.dpf(activity, 2f), 1.0f)
                setTextColor(palette.textSecondary)
                setPadding(0, dp(6), 0, dp(14))
            },
        )

        // 顶部快捷外链按钮：爱发电（主） + 支付宝直达（次）
        container.addView(
            actionButton(
                activity = activity,
                text = activity.getString(R.string.sponsor_btn_afdian),
                bgColor = palette.accent,
                textColor = Color.WHITE,
                strokeColor = Color.TRANSPARENT,
                rippleColor = palette.ripple,
                radiusPx = btnRadius,
            ) {
                openUrl(activity, AFDIAN_URL)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            },
        )
        container.addView(
            actionButton(
                activity = activity,
                text = activity.getString(R.string.sponsor_btn_alipay_direct),
                bgColor = palette.controlSurface,
                textColor = palette.textPrimary,
                strokeColor = palette.panelBorder,
                rippleColor = palette.ripple,
                radiusPx = btnRadius,
            ) {
                openUrl(activity, ALIPAY_DIRECT_URL)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(14) }
            },
        )

        // 并排双码卡片（微信赞赏码 + 支付宝收款码）
        val qrRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        qrRow.addView(
            qrCard(
                activity = activity,
                palette = palette,
                drawableRes = R.drawable.sponsor_wechat_tip,
                label = activity.getString(R.string.sponsor_qr_wechat),
                saveButtonLabel = activity.getString(R.string.sponsor_btn_save_qr),
                saveFileName = "CozyTing_WeChat_Tip.png",
                mimeType = "image/png",
                savedToast = activity.getString(R.string.sponsor_saved_wechat),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
            },
        )
        qrRow.addView(
            qrCard(
                activity = activity,
                palette = palette,
                drawableRes = R.drawable.sponsor_alipay_tip,
                label = activity.getString(R.string.sponsor_qr_alipay),
                saveButtonLabel = activity.getString(R.string.sponsor_btn_save_qr),
                saveFileName = "CozyTing_Alipay_Tip.png",
                mimeType = "image/png",
                savedToast = activity.getString(R.string.sponsor_saved_alipay),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
            },
        )
        container.addView(qrRow)

        // 底部关闭按钮
        container.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.sponsor_btn_close)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(palette.textSecondary)
                background = UiComponents.rippleSurface(
                    fillColor = Color.TRANSPARENT,
                    radiusPx = btnRadius,
                    rippleColor = palette.ripple,
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            },
        )

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container)
        }

        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val widthPx = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt()
                .coerceAtMost(dp(380))
            setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { onDismissed() }
        dialog.show()
        return dialog
    }

    private fun qrCard(
        activity: Activity,
        palette: UiPalette,
        drawableRes: Int,
        label: String,
        saveButtonLabel: String,
        saveFileName: String,
        mimeType: String,
        savedToast: String,
    ): LinearLayout {
        val dp = { v: Int -> UiComponents.dp(activity, v) }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = UiComponents.roundedDrawable(
                fillColor = palette.rowIdleBg,
                radiusPx = UiComponents.dpf(activity, 14f),
                strokeColor = palette.panelBorder,
                strokeWidthPx = dp(1).coerceAtLeast(1),
            )
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val qrW = dp(128)
        val qrH = dp(152)
        card.addView(
            ImageView(activity).apply {
                setImageResource(drawableRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = label
                layoutParams = LinearLayout.LayoutParams(qrW, qrH).apply {
                    bottomMargin = dp(6)
                }
            },
        )
        card.addView(
            TextView(activity).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(4))
            },
        )
        card.addView(
            TextView(activity).apply {
                text = saveButtonLabel
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(palette.accentBright)
                background = UiComponents.rippleSurface(
                    fillColor = palette.accentSoftBg,
                    radiusPx = UiComponents.dpf(activity, 8f),
                    rippleColor = palette.ripple,
                )
                setPadding(dp(8), dp(5), dp(8), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val ok = saveDrawableToGallery(activity, drawableRes, saveFileName, mimeType)
                    val msg = if (ok) savedToast else activity.getString(R.string.sponsor_save_failed)
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
        return card
    }

    private fun actionButton(
        activity: Activity,
        text: String,
        bgColor: Int,
        textColor: Int,
        strokeColor: Int,
        rippleColor: Int,
        radiusPx: Float,
        onClick: () -> Unit,
    ): TextView {
        val dp = { v: Int -> UiComponents.dp(activity, v) }
        return TextView(activity).apply {
            this.text = text
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = UiComponents.rippleSurface(
                fillColor = bgColor,
                radiusPx = radiusPx,
                rippleColor = rippleColor,
                strokeColor = strokeColor,
                strokeWidthPx = if (strokeColor == Color.TRANSPARENT) 0 else dp(1).coerceAtLeast(1),
            )
            setPadding(dp(14), dp(11), dp(14), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun openUrl(context: Context, url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (!opened) {
            Toast.makeText(context, context.getString(R.string.sponsor_open_link_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveDrawableToGallery(
        context: Context,
        drawableRes: Int,
        displayName: String,
        mimeType: String,
    ): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CozyTing")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                context.resources.openRawResource(drawableRes).use { input ->
                    input.copyTo(out)
                }
            } ?: return false
            val ready = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, ready, null, null)
            true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }.getOrDefault(false)
}
