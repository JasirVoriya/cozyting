package com.cozyting.app.capture

/**
 * 手动选择音源（主界面快捷切源胶囊 / 常用音源管理页返回）时的投送衔接策略。
 *
 * 原则：
 * 1. 进入「常用音源与选择」页本身不中断当前投送；
 * 2. 仅当正在单 App 投送中且切往不同包名时，交由 `CastService` 异步单 UID 校验并热切换，
 *    不在热切成功前预写本地 `store`，避免系统限制热切失败回滚原音源时 UI 与实际捕获不一致；
 * 3. 正在单 App 投送中且点选同一包名时，不断线、不重复重建采集后端；
 * 4. 未投送、静音测试或全局媒体模式下，仅更新本地选中音源与 UI，不触发单 App 热切。
 */
object ManualSourceSwitchPolicy {
    data class Input(
        val serviceRunning: Boolean,
        val activeServiceSource: String,
        val activeScopeMode: CaptureScopeMode,
        val activeTargetPackage: String,
        val pickedPackage: String,
    )

    enum class Action {
        /** 未处于单 App 投送中：直接落盘所选 App 并刷新界面。 */
        PERSIST_SELECTION_ONLY,

        /** 正在单 App 投送中且选择了不同 App：保留原 store，交由前台服务热切换并在成功后统一落盘。 */
        HOT_SWITCH_IN_CAST,

        /** 正在单 App 投送中且点选当前已在投送的 App：保持不断流、不重复热切。 */
        KEEP_ACTIVE_TARGET,
    }

    fun decide(input: Input): Action {
        val inSingleAppCast = input.serviceRunning &&
            input.activeServiceSource == "app" &&
            input.activeScopeMode == CaptureScopeMode.SINGLE_APP
        if (!inSingleAppCast) return Action.PERSIST_SELECTION_ONLY
        val picked = input.pickedPackage.trim()
        val active = input.activeTargetPackage.trim()
        if (picked.isNotEmpty() && picked == active) {
            return Action.KEEP_ACTIVE_TARGET
        }
        return Action.HOT_SWITCH_IN_CAST
    }
}
