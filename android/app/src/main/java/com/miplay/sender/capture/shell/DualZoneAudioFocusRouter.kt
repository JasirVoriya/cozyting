package com.miplay.sender.capture.shell

/**
 * 独立单 App 无感投送期的双区音频焦点（AudioFocus）隔离路由器（纯 Kotlin，便于 JVM 单测）。
 *
 * 解决痛点：
 * - 投送音乐 App 到小米音箱（音箱出声），同时手机刷短视频/看视频（手机出声）时，
 *   Android 默认单栈 `MediaFocusControl` 会让视频请求 `AUDIOFOCUS_GAIN` 时向音乐 App 下发
 *   `AUDIOFOCUS_LOSS`（导致音乐自动暂停）；用户手动恢复音乐时又向视频下发 `LOSS`（导致视频短暂无声），
 *   每刷下一个视频都会循环复发。
 *
 * 双区隔离策略：
 * 1. **投送区（Cast Zone）**：仅包含当前单 App 捕获目标 `castTargetUid` 的焦点请求；
 * 2. **本机区（Local Zone）**：包含手机上所有其他应用（短视频、游戏、浏览器、导航等）；
 * 3. 两区之间完全不互发 `AUDIOFOCUS_LOSS*`：刷视频不会暂停音箱音乐，切歌/恢复音乐也不会让视频短暂无声；
 * 4. 同区内严格维持 AOSP 标准单栈行为（例如手机从抖音切到 B 站时，抖音仍会正常收到 `AUDIOFOCUS_LOSS` 暂停）；
 * 5. 仅系统蜂窝来电/通话（`AudioFocus_For_Phone_Ring_And_Calls`）作为跨区临时中断，挂断后双区自动恢复。
 */
class DualZoneAudioFocusRouter(
    val castTargetUid: Int,
) {
    companion object {
        const val AUDIOFOCUS_GAIN = 1
        const val AUDIOFOCUS_GAIN_TRANSIENT = 2
        const val AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3
        const val AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4

        const val AUDIOFOCUS_LOSS = -1
        const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3

        const val PHONE_CALL_CLIENT_ID = "AudioFocus_For_Phone_Ring_And_Calls"
    }

    data class FocusRequest(
        val clientId: String,
        val uid: Int,
        val packageName: String,
        val gainRequest: Int,
    )

    sealed class Action {
        data class Grant(val clientId: String) : Action()
        data class DispatchChange(val clientId: String, val focusChange: Int) : Action()
    }

    private data class StackEntry(
        val clientId: String,
        val uid: Int,
        val packageName: String,
        val gainRequest: Int,
        var transientlyLost: Boolean = false,
    )

    private val castZone = ArrayList<StackEntry>()
    private val localZone = ArrayList<StackEntry>()
    private val activePhoneCalls = LinkedHashSet<String>()

    @Synchronized
    fun onFocusRequest(req: FocusRequest): List<Action> {
        val actions = ArrayList<Action>()
        if (isSystemPhoneCall(req)) {
            activePhoneCalls.add(req.clientId)
            actions.add(Action.Grant(req.clientId))
            pauseZoneTransiently(localZone, req.clientId, AUDIOFOCUS_LOSS_TRANSIENT, actions)
            pauseZoneTransiently(castZone, req.clientId, AUDIOFOCUS_LOSS_TRANSIENT, actions)
            return actions
        }

        val zone = zoneFor(req.uid)
        // 移除同 clientId 旧项，避免自己给自己发 LOSS
        zone.removeAll { it.clientId == req.clientId }

        actions.add(Action.Grant(req.clientId))

        when (req.gainRequest) {
            AUDIOFOCUS_GAIN_TRANSIENT, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                pauseZoneTransiently(zone, req.clientId, AUDIOFOCUS_LOSS_TRANSIENT, actions)
            }
            AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                pauseZoneTransiently(zone, req.clientId, AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, actions)
            }
            else -> {
                // 永久焦点 AUDIOFOCUS_GAIN：向同区内已有持有者派发 AUDIOFOCUS_LOSS 并移出同区栈
                val evicted = zone.toList()
                zone.clear()
                for (entry in evicted) {
                    actions.add(Action.DispatchChange(entry.clientId, AUDIOFOCUS_LOSS))
                }
            }
        }

        zone.add(
            StackEntry(
                clientId = req.clientId,
                uid = req.uid,
                packageName = req.packageName,
                gainRequest = req.gainRequest,
                transientlyLost = false,
            ),
        )
        return actions
    }

    @Synchronized
    fun onFocusAbandon(clientId: String, uid: Int): List<Action> {
        val actions = ArrayList<Action>()
        if (activePhoneCalls.remove(clientId)) {
            if (activePhoneCalls.isEmpty()) {
                regainZoneTopIfTransientlyLost(localZone, actions)
                regainZoneTopIfTransientlyLost(castZone, actions)
            }
            return actions
        }

        val zone = zoneFor(uid)
        val idx = zone.indexOfLast { it.clientId == clientId }
        if (idx < 0) return actions
        val wasTop = idx == zone.lastIndex
        zone.removeAt(idx)
        if (wasTop && activePhoneCalls.isEmpty()) {
            regainZoneTopIfTransientlyLost(zone, actions)
        }
        return actions
    }

    @Synchronized
    fun castZoneSize(): Int = castZone.size

    @Synchronized
    fun localZoneSize(): Int = localZone.size

    private fun zoneFor(uid: Int): ArrayList<StackEntry> =
        if (castTargetUid >= 0 && uid == castTargetUid) castZone else localZone

    private fun isSystemPhoneCall(req: FocusRequest): Boolean =
        req.clientId == PHONE_CALL_CLIENT_ID

    private fun pauseZoneTransiently(
        zone: ArrayList<StackEntry>,
        excludingClientId: String,
        lossCode: Int,
        out: MutableList<Action>,
    ) {
        for (entry in zone) {
            if (entry.clientId != excludingClientId && !entry.transientlyLost) {
                entry.transientlyLost = true
                out.add(Action.DispatchChange(entry.clientId, lossCode))
            }
        }
    }

    private fun regainZoneTopIfTransientlyLost(
        zone: ArrayList<StackEntry>,
        out: MutableList<Action>,
    ) {
        val top = zone.lastOrNull() ?: return
        if (top.transientlyLost) {
            top.transientlyLost = false
            out.add(Action.DispatchChange(top.clientId, AUDIOFOCUS_GAIN))
        }
    }
}
