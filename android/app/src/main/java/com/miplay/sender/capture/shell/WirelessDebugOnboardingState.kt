package com.miplay.sender.capture.shell

import com.miplay.sender.capture.CaptureEngineCapabilities

/**
 * 内置无线调试 5 步配对向导的纯状态计算模型（无 Android Framework 依赖，便于 JVM 单测）。
 *
 * 步骤顺序：
 * 1. 允许通知权限（后台播控栏 + 通知栏长按展开输入框保底兜底）
 * 2. 开启「开发者选项」
 * 3. 开启「无障碍自动配对」（推荐：无需悬浮窗权限，在系统设置 PIN 码页同屏展示悬浮数字键盘并自动识别 6 位码；支持跳过改走通知栏兜底）
 * 4. 开启「无线调试」并完成 6 位码配对
 * 5. 验证本机连通性并启用
 */
data class WirelessDebugOnboardingState(
    val sdkInt: Int,
    val supported: Boolean,
    val notificationsGranted: Boolean,
    val developerOptionsEnabled: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilitySkipped: Boolean,
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
        DEVELOPER_OPTIONS(2),
        ACCESSIBILITY_HELPER(3),
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
            developerOptionsEnabled: Boolean,
            accessibilityEnabled: Boolean = false,
            accessibilitySkipped: Boolean = false,
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
                    developerOptionsEnabled = developerOptionsEnabled,
                    accessibilityEnabled = accessibilityEnabled,
                    accessibilitySkipped = accessibilitySkipped,
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

            // 已配对或用户主动跳过无障碍时，第 3 步不阻塞后续配对/验证
            val accessibilityReady = accessibilityEnabled || accessibilitySkipped || paired

            val active = when {
                !notificationsGranted -> StepId.NOTIFICATION
                !developerOptionsEnabled -> StepId.DEVELOPER_OPTIONS
                !accessibilityReady -> StepId.ACCESSIBILITY_HELPER
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
                    "已允许通知，可用于展示后台投送播控栏及配对码输入兜底横幅"
                } else {
                    "用于展示前台投送播控栏与通知栏配对码输入兜底横幅，请先允许通知权限"
                },
                status = statusFor(StepId.NOTIFICATION, notificationsGranted),
                statusBadgeZh = if (notificationsGranted) "已完成" else "待开启",
                primaryActionLabelZh = if (notificationsGranted) null else "立即允许通知",
            )

            val step2 = StepItem(
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

            val step3 = StepItem(
                id = StepId.ACCESSIBILITY_HELPER,
                titleZh = "开启「无障碍自动配对」（推荐）",
                detailZh = when {
                    accessibilityEnabled ->
                        "已开启无障碍自动配对：无需悬浮窗权限，在系统「无线调试」PIN 码页顶部直接显示悬浮数字键盘，并自动读取 6 位配对码完成秒配"
                    accessibilitySkipped ->
                        "已跳过无障碍，将使用「通知栏横幅」手动输入配对码（若未看到输入按钮，请长按通知展开输入框）；也可随时重新开启自动识别"
                    else ->
                        "无需开启悬浮窗权限！开启「妙投 · 无线调试自动配对助手」后，可在系统设置 PIN 码页同屏显示悬浮数字键盘（不被系统隐藏）并自动读取 6 位配对码秒配；配对完成后自动休眠"
                },
                status = statusFor(StepId.ACCESSIBILITY_HELPER, accessibilityReady),
                statusBadgeZh = when {
                    accessibilityEnabled -> "已开启"
                    accessibilitySkipped -> "已跳过"
                    paired -> "已完成"
                    else -> "推荐 · 免手输"
                },
                primaryActionLabelZh = when {
                    accessibilityEnabled -> null
                    accessibilitySkipped -> "改为开启无障碍"
                    else -> "去开启无障碍（推荐）"
                },
            )

            val step4 = StepItem(
                id = StepId.WIRELESS_PAIRING,
                titleZh = if (accessibilityEnabled) {
                    "开启「无线调试」并点开配对码弹窗"
                } else {
                    "开启「无线调试」并在通知栏输入 6 位配对码"
                },
                detailZh = when {
                    paired ->
                        "已保存本机 TLS 配对证书；以后无需重复输入配对码（重启手机后只需重新打开无线调试开关）"
                    accessibilityEnabled ->
                        "① 点击下方前往「开发者选项 → 无线调试」并打开开关\n" +
                            "② 点「使用配对码配对设备」弹出 6 位数字弹窗（保持弹窗不关闭）\n" +
                            "③ 顶部悬浮框将自动识别 6 位码并秒配跳回本页（也可直接点悬浮数字键盘或长按通知栏输入）"
                    else ->
                        "① 点击下方前往「开发者选项 → 无线调试」并打开开关\n" +
                            "② 点「使用配对码配对设备」保持 6 位码弹窗不关闭\n" +
                            "③ 下拉通知栏点「输入 6 位配对码」（若未看到输入框，请【长按本条通知】展开）"
                },
                status = statusFor(StepId.WIRELESS_PAIRING, paired),
                statusBadgeZh = if (paired) "已配对" else "待配对",
                primaryActionLabelZh = when {
                    paired -> "重新配对"
                    accessibilityEnabled -> "启动自动配对并前往设置"
                    else -> "前往无线调试（通知栏输入）"
                },
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
                    "5 步开启免投屏无感采集（仅需首次配置）" to "无需悬浮窗权限或电脑 · 开启无障碍即可自动识别 6 位配对码，同时保留通知栏长按输入兜底"
            }

            return WirelessDebugOnboardingState(
                sdkInt = sdkInt,
                supported = true,
                notificationsGranted = notificationsGranted,
                developerOptionsEnabled = developerOptionsEnabled,
                accessibilityEnabled = accessibilityEnabled,
                accessibilitySkipped = accessibilitySkipped,
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
