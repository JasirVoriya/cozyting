package com.cozyting.app.compat

import android.content.Context
import com.cozyting.app.R

/**
 * 两层选择必须分开表述，禁止声称「系统 MediaProjection 对话框选了谁」会改写发送端 UID 过滤。
 *
 * 1. App 选择器：决定 AudioPlaybackCapture 的 matchingUid（唯一权威）。
 * 2. 系统 MediaProjection 授权框：只授予捕获能力；OEM 若展示「测试音源/整屏」选项，
 *    与本 App 的 UID 白名单无关。
 */
object CaptureSelectionLayers {
    fun statusLine(packageName: String, uid: Int): String =
        "捕获 UID 过滤=$uid（$packageName）；系统授权框不会改写该 UID"

    fun explainMismatch(
        appPickerPackage: String,
        appPickerUid: Int,
        systemDialogHint: String,
    ): String = buildString {
        append("两层选择不一致风险：")
        append("发送端 App 选择器锁定 $appPickerPackage uid=$appPickerUid；")
        append("系统对话框提示「$systemDialogHint」并不会、也不能自动改写 UID 过滤。")
        append("若系统侧选了其他音源而 UID 仍指向 $appPickerPackage，捕获可能全静音——这是预期，不是授权已切换。")
    }

    fun settingsHintZh(): String =
        "选定 App 决定 UID 过滤。系统录屏/捕获授权框只负责授权；其中的测试音源或其它选项不会自动改写本 App 的 UID。"

    fun settingsHint(context: Context): String =
        context.getString(R.string.capture_layers_hint)
}
