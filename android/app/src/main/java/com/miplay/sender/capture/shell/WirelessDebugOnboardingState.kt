package com.miplay.sender.capture.shell

import com.miplay.sender.capture.CaptureEngineCapabilities

/**
 * 内置无线调试 5 步配对向导的纯状态计算模型（无 Android Framework 依赖，便于 JVM 单测）。
 */
data class WirelessDebugOnboardingState(
    val sdkInt: Int,
    val supported: Boolean,
    val notificationsGranted: Boolean,
    val overlayGranted: Boolean,
    val developerOptionsEnabled: Boolean,
    val paired: Boolean,
    val connectionVerified: Boolean,
    val connectedPort: Int,
    val steps: List<StepItem>,
    val activeStep: StepId?,
    val completedCount: Int,
    val headlineZh: String,
    val subheadlineZh: String,
) {
    enum class StepId(val index: Int) {
        NOTIFICATION(1),
        OVERLAY_PERMISSION(2),
        DEVELOPER_OPTIONS(3),
        WIRELESS_PAIRING(4),
        VERIFY_CONNECTION(5),
    }

    enum class StepStatus {
        DONE,
        ACTIVE,
        LOCKED,
    }

    data class StepItem(
        val id: StepId,
        val titleZh: String,
        val detailZh: String,
        val status: StepStatus,
        val statusBadgeZh: String,
        val primaryActionLabelZh: String?,
    )

    companion object {
        fun evaluate(
            sdkInt: Int,
            notificationsGranted: Boolean,
            overlayGranted: Boolean = false,
            developerOptionsEnabled: Boolean,
            paired: Boolean,
            connectionVerified: Boolean,
            connectedPort: Int = 0,
        ): WirelessDebugOnboardingState {
            val caps = CaptureEngineCapabilities.forSdkInt(sdkInt)
            if (!caps.supportsWirelessDebugEngine) {
                return WirelessDebugOnboardingState(
                    sdkInt = sdkInt,
                    supported = false,
                    notificationsGranted = notificationsGranted,
                    overlayGranted = overlayGranted,
                    developerOptionsEnabled = developerOptionsEnabled,
                    paired = false,
                    connectionVerified = false,
                    connectedPort = 0,
                    steps = emptyList(),
                    activeStep = null,
                    completedCount = 0,
                    headlineZh = "当前系统不支持内置无线调试无感采集",
                    subheadlineZh = "Android 10（API $sdkInt）无原生无线调试配对与 shell 音频捕获权限，请继续使用「标准投屏模式」。",
                )
            }

            val active = when {
                !notificationsGranted -> StepId.NOTIFICATION
                !overlayGranted -> StepId.OVERLAY_PERMISSION
                !developerOptionsEnabled -> StepId.DEVELOPER_OPTIONS
                !paired -> StepId.WIRELESS_PAIRING
                !connectionVerified -> StepId.VERIFY_CONNECTION
                else -> null
            }

            fun statusFor(id: StepId, done: Boolean): StepStatus = when {
                done -> StepStatus.DONE
                active == id -> StepStatus.ACTIVE
                else -> StepStatus.LOCKED
            }

            val step1 = StepItem(
                id = StepId.NOTIFICATION,
                titleZh = "允许通知权限",
                detailZh = if (notificationsGranted) {
                    "已允许通知，可用于展示后台投送状态与配对结果提醒"
                } else {
                    "用于展示前台投送播控栏与配对状态提示，请先允许通知权限"
                },
                status = statusFor(StepId.NOTIFICATION, notificationsGranted),
                statusBadgeZh = if (notificationsGranted) "已完成" else "待开启",
                primaryActionLabelZh = if (notificationsGranted) null else "立即允许通知",
            )

            val step2 = StepItem(
                id = StepId.OVERLAY_PERMISSION,
                titleZh = "开启「悬浮窗权限」",
                detailZh = if (overlayGranted) {
                    "已允许悬浮窗，配对时将在系统设置上方自动弹出 6 位配对码浮动数字键盘"
                } else {
                    "首次配对需在系统「无线调试」页面上方悬浮 6 位码浮动输入法（切回 App 会导致系统销毁配对码，且无需再下拉通知栏），请允许妙投显示在其他应用上层"
                },
                status = statusFor(StepId.OVERLAY_PERMISSION, overlayGranted),
                statusBadgeZh = if (overlayGranted) "已完成" else "待开启",
                primaryActionLabelZh = if (overlayGranted) null else "去开启悬浮窗权限",
            )

            val step3 = StepItem(
                id = StepId.DEVELOPER_OPTIONS,
                titleZh = "开启「开发者选项」",
                detailZh = if (developerOptionsEnabled) {
                    "已检测到系统「开发者选项」处于开启状态"
                } else {
                    "前往系统「关于手机」，连续点击「版本号」7 次（小米 / HyperOS 连续点击「OS 版本」），开启后返回本页自动打勾"
                },
                status = statusFor(StepId.DEVELOPER_OPTIONS, developerOptionsEnabled),
                statusBadgeZh = if (developerOptionsEnabled) "已完成" else "待开启",
                primaryActionLabelZh = if (developerOptionsEnabled) null else "前往「关于手机」",
            )

            val step4 = StepItem(
                id = StepId.WIRELESS_PAIRING,
                titleZh = "开启「无线调试」并在浮动框输入 6 位配对码",
                detailZh = if (paired) {
                    "已保存本机 TLS 配对证书；以后无需重复输入配对码（重启手机后只需重新打开无线调试开关）"
                } else {
                    "① 点击下方「启动浮动框并前往设置」，顶部将挂出可拖动的妙投配对浮动条\n" +
                        "② 打开「无线调试」开关并点「使用配对码配对设备」（保持 6 位码弹窗不关闭）\n" +
                        "③ 顶部浮动框会自动展开紧凑数字键盘，直接点按 6 位配对码即可自动完成配对并跳回本页"
                },
                status = statusFor(StepId.WIRELESS_PAIRING, paired),
                statusBadgeZh = if (paired) "已配对" else "待配对",
                primaryActionLabelZh = if (paired) "重新配对" else "启动浮动框并前往设置",
            )

            val step5 = StepItem(
                id = StepId.VERIFY_CONNECTION,
                titleZh = "验证本机连通性并启用",
                detailZh = when {
                    connectionVerified && connectedPort > 0 ->
                        "已连通本机无线调试（127.0.0.1:$connectedPort · uid=2000），现在可零弹窗投送音频"
                    connectionVerified ->
                        "本机无线调试连通验证通过，现在可零弹窗投送音频"
                    paired ->
                        "请确保「无线调试」开关处于开启状态，点击测试连接完成最后一步验证"
                    else ->
                        "完成第 4 步配对后，将自动检测本机无线调试连通性"
                },
                status = statusFor(StepId.VERIFY_CONNECTION, connectionVerified),
                statusBadgeZh = when {
                    connectionVerified -> "已就绪"
                    paired -> "待验证"
                    else -> "未开始"
                },
                primaryActionLabelZh = when {
                    !paired -> null
                    connectionVerified -> "重新测试连接"
                    else -> "测试连接"
                },
            )

            val steps = listOf(step1, step2, step3, step4, step5)
            val completed = steps.count { it.status == StepStatus.DONE }

            val (headline, sub) = when {
                connectionVerified ->
                    "无感采集已全部就绪" to "点击下方按钮启用无感模式，投送时将不再弹出投屏确认框或常驻「正在投屏」提示"
                paired ->
                    "已完成配对 · 还差最后一步验证" to "确认系统「无线调试」已开启后点击「测试连接」，验证通过即可零弹窗投送"
                else ->
                    "5 步开启免投屏无感采集（仅需首次配置）" to "纯本机内置无线调试直连，无需安装 Shizuku 或连接电脑 · 当前进度 $completed/5"
            }

            return WirelessDebugOnboardingState(
                sdkInt = sdkInt,
                supported = true,
                notificationsGranted = notificationsGranted,
                overlayGranted = overlayGranted,
                developerOptionsEnabled = developerOptionsEnabled,
                paired = paired,
                connectionVerified = connectionVerified,
                connectedPort = connectedPort,
                steps = steps,
                activeStep = active,
                completedCount = completed,
                headlineZh = headline,
                subheadlineZh = sub,
            )
        }
    }
}
