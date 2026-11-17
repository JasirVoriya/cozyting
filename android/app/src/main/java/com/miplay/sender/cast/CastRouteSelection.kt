package com.miplay.sender.cast

/**
 * 「This device」与音箱多选互斥逻辑（对齐小米音乐投送面板）。
 * 纯状态机：不碰 Android API，供 UI / 单测共用。
 */
object CastRouteSelection {

    enum class Kind { THIS_DEVICE, SPEAKER, OTHER }

    data class Device(
        val id: String,
        val name: String,
        val kind: Kind,
        /** 是否可走本 App 的 MiPlay 投送（电视等未接入协议 = false，可列出但不可假装成功）。 */
        val castable: Boolean,
        val selected: Boolean = false,
        val detail: String = "",
    )

    data class State(
        val thisDeviceSelected: Boolean,
        val selectedSpeakerIds: Set<String>,
        val devices: List<Device>,
    ) {
        val casting: Boolean get() = selectedSpeakerIds.isNotEmpty()
    }

    sealed class Effect {
        /** 停止一切音箱投送，恢复本机出声。 */
        data object StopCastToThisDevice : Effect()
        /** 开始向给定音箱投送（替换整组）。 */
        data class StartCast(val speakerIds: Set<String>) : Effect()
        /** 投送进行中增删成员。 */
        data class AddSpeaker(val id: String) : Effect()
        data class RemoveSpeaker(val id: String) : Effect()
        /** 用户点了不可投送项（如未接入协议的电视）。 */
        data class RejectNonCastable(val id: String, val reason: String) : Effect()
    }

    data class Transition(val state: State, val effects: List<Effect>)

    fun initial(devices: List<Device>): State {
        val normalized = devices.map {
            when (it.kind) {
                Kind.THIS_DEVICE -> it.copy(selected = true, castable = true)
                else -> it.copy(selected = false)
            }
        }
        return State(thisDeviceSelected = true, selectedSpeakerIds = emptySet(), devices = normalized)
    }

    fun selectThisDevice(state: State): Transition {
        val devices = state.devices.map {
            when (it.kind) {
                Kind.THIS_DEVICE -> it.copy(selected = true)
                Kind.SPEAKER -> it.copy(selected = false)
                Kind.OTHER -> it.copy(selected = false)
            }
        }
        val next = State(true, emptySet(), devices)
        val effects = if (state.casting) listOf(Effect.StopCastToThisDevice) else emptyList()
        return Transition(next, effects)
    }

    /**
     * @param wantSelected 用户想要的勾选结果。
     */
    fun setSpeakerChecked(state: State, speakerId: String, wantSelected: Boolean): Transition {
        val target = state.devices.find { it.id == speakerId }
            ?: return Transition(state, emptyList())
        if (target.kind != Kind.SPEAKER) {
            return Transition(state, listOf(Effect.RejectNonCastable(speakerId, "不是音箱")))
        }
        if (!target.castable) {
            return Transition(
                state,
                listOf(Effect.RejectNonCastable(speakerId, target.detail.ifBlank { "未接入 MiPlay，不能假装投送" })),
            )
        }
        if (wantSelected == (speakerId in state.selectedSpeakerIds)) {
            return Transition(state, emptyList())
        }

        val nextSelected = if (wantSelected) {
            state.selectedSpeakerIds + speakerId
        } else {
            state.selectedSpeakerIds - speakerId
        }

        // 取消最后一台 → 回到 This device
        if (nextSelected.isEmpty()) {
            return selectThisDevice(state.copy(selectedSpeakerIds = state.selectedSpeakerIds))
        }

        val devices = state.devices.map {
            when {
                it.kind == Kind.THIS_DEVICE -> it.copy(selected = false)
                it.id == speakerId -> it.copy(selected = wantSelected)
                it.id in nextSelected -> it.copy(selected = true)
                it.kind == Kind.SPEAKER -> it.copy(selected = it.id in nextSelected)
                else -> it
            }
        }
        val next = State(thisDeviceSelected = false, selectedSpeakerIds = nextSelected, devices = devices)

        val effects = when {
            !state.casting && wantSelected -> listOf(Effect.StartCast(nextSelected))
            state.casting && wantSelected -> listOf(Effect.AddSpeaker(speakerId))
            state.casting && !wantSelected -> listOf(Effect.RemoveSpeaker(speakerId))
            else -> emptyList()
        }
        return Transition(next, effects)
    }

    /** 发现结果合并：保留已选音箱勾选；This device 规则不变。 */
    fun mergeDiscovered(state: State, discovered: List<Device>): State {
        val thisDev = state.devices.find { it.kind == Kind.THIS_DEVICE }
            ?: Device("this", "This device", Kind.THIS_DEVICE, castable = true, selected = state.thisDeviceSelected)
        val others = discovered.filter { it.kind != Kind.THIS_DEVICE }.map { d ->
            val selected = d.kind == Kind.SPEAKER && d.castable && d.id in state.selectedSpeakerIds
            d.copy(selected = selected)
        }
        val stillSelected = others.filter { it.selected }.map { it.id }.toSet()
        val thisSelected = stillSelected.isEmpty()
        return State(
            thisDeviceSelected = thisSelected,
            selectedSpeakerIds = stillSelected,
            devices = listOf(thisDev.copy(selected = thisSelected)) + others,
        )
    }
}
