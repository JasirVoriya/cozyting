package com.miaocast.app.capture.shell

import android.content.Context
import com.miaocast.app.R
import com.miaocast.app.capture.CaptureEngineCapabilities

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

    data class Strings(
        val unsupportedHeadline: String,
        val unsupportedSub: (Int) -> String,
        val step1Title: String,
        val step1DetailDone: String,
        val step1DetailTodo: String,
        val step1Action: String,
        val step2Title: String,
        val step2DetailDone: String,
        val step2DetailTodo: String,
        val step2Action: String,
        val step3Title: String,
        val step3DetailEnabled: String,
        val step3DetailSkipped: String,
        val step3DetailTodo: String,
        val step3BadgeRecommended: String,
        val step3BadgeSkipped: String,
        val step3ActionEnable: String,
        val step3ActionReenable: String,
        val step4TitleA11y: String,
        val step4TitleNotif: String,
        val step4DetailDone: String,
        val step4DetailA11y: String,
        val step4DetailNotif: String,
        val step4ActionRepair: String,
        val step4ActionA11y: String,
        val step4ActionNotif: String,
        val step5Title: String,
        val step5DetailVerifiedPort: (Int) -> String,
        val step5DetailVerified: String,
        val step5DetailPaired: String,
        val step5DetailTodo: String,
        val step5ActionRetest: String,
        val step5ActionTest: String,
        val badgeDone: String,
        val badgeTodo: String,
        val badgeEnabled: String,
        val badgePaired: String,
        val badgeUnpaired: String,
        val badgeReady: String,
        val badgeVerify: String,
        val badgePending: String,
        val headlineReady: String,
        val subReady: String,
        val headlinePaired: String,
        val subPaired: String,
        val headlineDefault: String,
        val subDefault: String,
    ) {
        companion object {
            val DEFAULT_ZH = Strings(
                unsupportedHeadline = "当前系统不支持内置无线调试无感采集",
                unsupportedSub = { sdk -> "Android 10（API $sdk）无原生无线调试配对与 shell 音频捕获权限，请继续使用「标准投屏模式」。" },
                step1Title = "允许通知权限",
                step1DetailDone = "已允许通知，可用于展示后台投送播控栏及配对码输入兜底横幅",
                step1DetailTodo = "用于展示前台投送播控栏与通知栏配对码输入兜底横幅，请先允许通知权限",
                step1Action = "立即允许通知",
                step2Title = "开启「开发者选项」",
                step2DetailDone = "已检测到系统「开发者选项」处于开启状态",
                step2DetailTodo = "前往系统「关于手机」，连续点击「版本号」7 次（小米 / HyperOS 连续点击「OS 版本」），开启后返回本页自动打勾",
                step2Action = "前往「关于手机」",
                step3Title = "开启「无障碍自动配对」（推荐）",
                step3DetailEnabled = "已开启无障碍自动配对：无需悬浮窗权限，在系统「无线调试」PIN 码页顶部直接显示悬浮数字键盘，并自动读取 6 位配对码完成秒配",
                step3DetailSkipped = "已跳过无障碍，将使用「通知栏横幅」手动输入配对码（若未看到输入按钮，请长按通知展开输入框）；也可随时重新开启自动识别",
                step3DetailTodo = "无需开启悬浮窗权限！开启「妙投 · 无线调试自动配对助手」后，可在系统设置 PIN 码页同屏显示悬浮数字键盘（不被系统隐藏）并自动读取 6 位配对码秒配；配对完成后自动休眠",
                step3BadgeRecommended = "推荐 · 免手输",
                step3BadgeSkipped = "已跳过",
                step3ActionEnable = "去开启无障碍（推荐）",
                step3ActionReenable = "改为开启无障碍",
                step4TitleA11y = "开启「无线调试」并点开配对码弹窗",
                step4TitleNotif = "开启「无线调试」并在通知栏输入 6 位配对码",
                step4DetailDone = "已保存本机 TLS 配对证书；以后无需重复输入配对码（重启手机后只需重新打开无线调试开关）",
                step4DetailA11y = "① 点击下方前往「开发者选项 → 无线调试」并打开开关\n② 点「使用配对码配对设备」弹出 6 位数字弹窗（保持弹窗不关闭）\n③ 顶部悬浮框将自动识别 6 位码并秒配跳回本页（也可直接点悬浮数字键盘或长按通知栏输入）",
                step4DetailNotif = "① 点击下方前往「开发者选项 → 无线调试」并打开开关\n② 点「使用配对码配对设备」保持 6 位码弹窗不关闭\n③ 下拉通知栏点「输入 6 位配对码」（若未看到输入框，请【长按本条通知】展开）",
                step4ActionRepair = "重新配对",
                step4ActionA11y = "启动自动配对并前往设置",
                step4ActionNotif = "前往无线调试（通知栏输入）",
                step5Title = "验证本机连通性并启用",
                step5DetailVerifiedPort = { port -> "已连通本机无线调试（127.0.0.1:$port · uid=2000），现在可零弹窗投送音频" },
                step5DetailVerified = "本机无线调试连通验证通过，现在可零弹窗投送音频",
                step5DetailPaired = "请确保「无线调试」开关处于开启状态，点击测试连接完成最后一步验证",
                step5DetailTodo = "完成第 4 步配对后，将自动检测本机无线调试连通性",
                step5ActionRetest = "重新测试连接",
                step5ActionTest = "测试连接",
                badgeDone = "已完成",
                badgeTodo = "待开启",
                badgeEnabled = "已开启",
                badgePaired = "已配对",
                badgeUnpaired = "待配对",
                badgeReady = "已就绪",
                badgeVerify = "待验证",
                badgePending = "未开始",
                headlineReady = "无感采集已全部就绪",
                subReady = "点击下方按钮启用无感模式，投送时将不再弹出投屏确认框或常驻「正在投屏」提示",
                headlinePaired = "已完成配对 · 还差最后一步验证",
                subPaired = "确认系统「无线调试」已开启后点击「测试连接」，验证通过即可零弹窗投送",
                headlineDefault = "5 步开启免投屏无感采集（仅需首次配置）",
                subDefault = "无需悬浮窗权限或电脑 · 开启无障碍即可自动识别 6 位配对码，同时保留通知栏长按输入兜底",
            )

            fun fromContext(context: Context): Strings = Strings(
                unsupportedHeadline = context.getString(R.string.onboard_unsupported_headline),
                unsupportedSub = { sdk -> context.getString(R.string.onboard_unsupported_sub, sdk) },
                step1Title = context.getString(R.string.onboard_step1_title),
                step1DetailDone = context.getString(R.string.onboard_step1_detail_done),
                step1DetailTodo = context.getString(R.string.onboard_step1_detail_todo),
                step1Action = context.getString(R.string.onboard_step1_action),
                step2Title = context.getString(R.string.onboard_step2_title),
                step2DetailDone = context.getString(R.string.onboard_step2_detail_done),
                step2DetailTodo = context.getString(R.string.onboard_step2_detail_todo),
                step2Action = context.getString(R.string.onboard_step2_action),
                step3Title = context.getString(R.string.onboard_step3_title),
                step3DetailEnabled = context.getString(R.string.onboard_step3_detail_enabled),
                step3DetailSkipped = context.getString(R.string.onboard_step3_detail_skipped),
                step3DetailTodo = context.getString(R.string.onboard_step3_detail_todo),
                step3BadgeRecommended = context.getString(R.string.onboard_step3_badge_recommended),
                step3BadgeSkipped = context.getString(R.string.onboard_step3_badge_skipped),
                step3ActionEnable = context.getString(R.string.onboard_step3_action_enable),
                step3ActionReenable = context.getString(R.string.onboard_step3_action_reenable),
                step4TitleA11y = context.getString(R.string.onboard_step4_title_a11y),
                step4TitleNotif = context.getString(R.string.onboard_step4_title_notif),
                step4DetailDone = context.getString(R.string.onboard_step4_detail_done),
                step4DetailA11y = context.getString(R.string.onboard_step4_detail_a11y),
                step4DetailNotif = context.getString(R.string.onboard_step4_detail_notif),
                step4ActionRepair = context.getString(R.string.onboard_step4_action_repair),
                step4ActionA11y = context.getString(R.string.onboard_step4_action_a11y),
                step4ActionNotif = context.getString(R.string.onboard_step4_action_notif),
                step5Title = context.getString(R.string.onboard_step5_title),
                step5DetailVerifiedPort = { port -> context.getString(R.string.onboard_step5_detail_verified_port, port) },
                step5DetailVerified = context.getString(R.string.onboard_step5_detail_verified),
                step5DetailPaired = context.getString(R.string.onboard_step5_detail_paired),
                step5DetailTodo = context.getString(R.string.onboard_step5_detail_todo),
                step5ActionRetest = context.getString(R.string.onboard_step5_action_retest),
                step5ActionTest = context.getString(R.string.onboard_step5_action_test),
                badgeDone = context.getString(R.string.onboard_badge_done),
                badgeTodo = context.getString(R.string.onboard_badge_todo),
                badgeEnabled = context.getString(R.string.onboard_badge_enabled),
                badgePaired = context.getString(R.string.onboard_badge_paired),
                badgeUnpaired = context.getString(R.string.onboard_badge_unpaired),
                badgeReady = context.getString(R.string.onboard_badge_ready),
                badgeVerify = context.getString(R.string.onboard_badge_verify),
                badgePending = context.getString(R.string.onboard_badge_pending),
                headlineReady = context.getString(R.string.onboard_headline_ready),
                subReady = context.getString(R.string.onboard_sub_ready),
                headlinePaired = context.getString(R.string.onboard_headline_paired),
                subPaired = context.getString(R.string.onboard_sub_paired),
                headlineDefault = context.getString(R.string.onboard_headline_default),
                subDefault = context.getString(R.string.onboard_sub_default),
            )
        }
    }

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
            strings: Strings = Strings.DEFAULT_ZH,
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
                    headlineZh = strings.unsupportedHeadline,
                    subheadlineZh = strings.unsupportedSub(sdkInt),
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
                titleZh = strings.step1Title,
                detailZh = if (notificationsGranted) strings.step1DetailDone else strings.step1DetailTodo,
                status = statusFor(StepId.NOTIFICATION, notificationsGranted),
                statusBadgeZh = if (notificationsGranted) strings.badgeDone else strings.badgeTodo,
                primaryActionLabelZh = if (notificationsGranted) null else strings.step1Action,
            )

            val step2 = StepItem(
                id = StepId.DEVELOPER_OPTIONS,
                titleZh = strings.step2Title,
                detailZh = if (developerOptionsEnabled) strings.step2DetailDone else strings.step2DetailTodo,
                status = statusFor(StepId.DEVELOPER_OPTIONS, developerOptionsEnabled),
                statusBadgeZh = if (developerOptionsEnabled) strings.badgeDone else strings.badgeTodo,
                primaryActionLabelZh = if (developerOptionsEnabled) null else strings.step2Action,
            )

            val step3 = StepItem(
                id = StepId.ACCESSIBILITY_HELPER,
                titleZh = strings.step3Title,
                detailZh = when {
                    accessibilityEnabled -> strings.step3DetailEnabled
                    accessibilitySkipped -> strings.step3DetailSkipped
                    else -> strings.step3DetailTodo
                },
                status = statusFor(StepId.ACCESSIBILITY_HELPER, accessibilityReady),
                statusBadgeZh = when {
                    accessibilityEnabled -> strings.badgeEnabled
                    accessibilitySkipped -> strings.step3BadgeSkipped
                    paired -> strings.badgeDone
                    else -> strings.step3BadgeRecommended
                },
                primaryActionLabelZh = when {
                    accessibilityEnabled -> null
                    accessibilitySkipped -> strings.step3ActionReenable
                    else -> strings.step3ActionEnable
                },
            )

            val step4 = StepItem(
                id = StepId.WIRELESS_PAIRING,
                titleZh = if (accessibilityEnabled) strings.step4TitleA11y else strings.step4TitleNotif,
                detailZh = when {
                    paired -> strings.step4DetailDone
                    accessibilityEnabled -> strings.step4DetailA11y
                    else -> strings.step4DetailNotif
                },
                status = statusFor(StepId.WIRELESS_PAIRING, paired),
                statusBadgeZh = if (paired) strings.badgePaired else strings.badgeUnpaired,
                primaryActionLabelZh = when {
                    paired -> strings.step4ActionRepair
                    accessibilityEnabled -> strings.step4ActionA11y
                    else -> strings.step4ActionNotif
                },
            )

            val step5 = StepItem(
                id = StepId.VERIFY_CONNECTION,
                titleZh = strings.step5Title,
                detailZh = when {
                    connectionVerified && connectedPort > 0 -> strings.step5DetailVerifiedPort(connectedPort)
                    connectionVerified -> strings.step5DetailVerified
                    paired -> strings.step5DetailPaired
                    else -> strings.step5DetailTodo
                },
                status = statusFor(StepId.VERIFY_CONNECTION, connectionVerified),
                statusBadgeZh = when {
                    connectionVerified -> strings.badgeReady
                    paired -> strings.badgeVerify
                    else -> strings.badgePending
                },
                primaryActionLabelZh = when {
                    !paired -> null
                    connectionVerified -> strings.step5ActionRetest
                    else -> strings.step5ActionTest
                },
            )

            val steps = listOf(step1, step2, step3, step4, step5)
            val completed = steps.count { it.status == StepStatus.DONE }

            val (headline, sub) = when {
                connectionVerified -> strings.headlineReady to strings.subReady
                paired -> strings.headlinePaired to strings.subPaired
                else -> strings.headlineDefault to strings.subDefault
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
