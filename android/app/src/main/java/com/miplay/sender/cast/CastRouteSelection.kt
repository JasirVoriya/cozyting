package com.miplay.sender.cast

/**
 * 「This device」与 MiPlay 接收端（音箱/电视）多选互斥逻辑。
 * 电视与音箱同为可投目标；仅 [castable]=false（离线/协议未实现）时拒绝。
 * 会话层当前支持多接收端并行，电视+音箱可同时勾选。
 */
object CastRouteSelection {

    enum class Kind { THIS_DEVICE, SPEAKER, TV }

    data class Device(
        val id: String,
        val name: String,
        val kind: Kind,
        /**
         * 可否作为本 App 投送目标。
         * MiPlay 音箱/电视：true；未实现协议（DLNA/AirPlay/Cast）或离线：false。
         * **不得**仅因外观是电视就设为 false。
         */
        val castable: Boolean,
        val selected: Boolean = false,
        val detail: String = "",
        /** 本机为 null；真实发现设备填所属协议。 */
        val protocol: CastProtocol? = null,
    ) {
        val isCastSink: Boolean get() = kind == Kind.SPEAKER || kind == Kind.TV
    }

    data class State(
        val thisDeviceSelected: Boolean,
        /** 已选接收端 id（音箱或电视）。 */
        val selectedSpeakerIds: Set<String>,
        val devices: List<Device>,
    ) {
        val casting: Boolean get() = selectedSpeakerIds.isNotEmpty()
    }

    sealed class Effect {
        data object StopCastToThisDevice : Effect()
        data class StartCast(val speakerIds: Set<String>) : Effect()
        data class AddSpeaker(val id: String) : Effect()
        data class RemoveSpeaker(val id: String) : Effect()
        /** 离线或协议未实现——不得假装投送成功。 */
        data class RejectNonCastable(val id: String, val reason: String) : Effect()
    }

    data class Transition(val state: State, val effects: List<Effect>)

    data class DiscoveredRow(
        val device: Device,
        val advertisesMiPlay: Boolean,
        val service: String,
        val apps: String,
        val visibleByDefault: Boolean,
        val logLine: String,
    )

    fun initial(devices: List<Device>): State {
        val normalized = devices.map {
            when (it.kind) {
                Kind.THIS_DEVICE -> it.copy(selected = true, castable = true, protocol = null)
                else -> it.copy(selected = false)
            }
        }
        return State(thisDeviceSelected = true, selectedSpeakerIds = emptySet(), devices = normalized)
    }

    fun selectThisDevice(state: State): Transition {
        val devices = state.devices.map {
            when (it.kind) {
                Kind.THIS_DEVICE -> it.copy(selected = true)
                Kind.SPEAKER, Kind.TV -> it.copy(selected = false)
            }
        }
        val next = State(true, emptySet(), devices)
        val effects = if (state.casting) listOf(Effect.StopCastToThisDevice) else emptyList()
        return Transition(next, effects)
    }

    /**
     * 勾选/取消音箱或电视。
     * @param wantSelected 用户想要的勾选结果。
     */
    fun setSpeakerChecked(state: State, speakerId: String, wantSelected: Boolean): Transition {
        val target = state.devices.find { it.id == speakerId }
            ?: return Transition(state, emptyList())
        if (!target.isCastSink) {
            return Transition(state, listOf(Effect.RejectNonCastable(speakerId, "不是可投送接收端")))
        }
        if (!target.castable) {
            val why = when {
                target.detail.isNotBlank() -> target.detail
                target.protocol != null && !target.protocol.castImplemented ->
                    "${target.protocol.labelZh} 协议尚未在本 App 实现，不能假装投送成功"
                else -> "设备不可投送"
            }
            return Transition(state, listOf(Effect.RejectNonCastable(speakerId, why)))
        }
        // 已实现协议校验：castable 的接收端必须是已实现协议（当前仅 MiPlay）
        val proto = target.protocol
        if (proto != null && !proto.castImplemented) {
            return Transition(
                state,
                listOf(
                    Effect.RejectNonCastable(
                        speakerId,
                        "${proto.labelZh} 协议尚未实现，不能假装投送成功",
                    ),
                ),
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

        if (nextSelected.isEmpty()) {
            return selectThisDevice(state.copy(selectedSpeakerIds = state.selectedSpeakerIds))
        }

        val devices = state.devices.map {
            when {
                it.kind == Kind.THIS_DEVICE -> it.copy(selected = false)
                it.id == speakerId -> it.copy(selected = wantSelected)
                it.isCastSink -> it.copy(selected = it.id in nextSelected)
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

    fun mergeDiscovered(state: State, discovered: List<Device>): State {
        val thisDev = state.devices.find { it.kind == Kind.THIS_DEVICE }
            ?: Device("this", "This device", Kind.THIS_DEVICE, castable = true, selected = state.thisDeviceSelected)
        val others = discovered.filter { it.kind != Kind.THIS_DEVICE }.map { d ->
            val selected = d.isCastSink && d.castable && d.id in state.selectedSpeakerIds
            d.copy(selected = selected)
        }
        val stillSelected = others.filter { it.selected }.map { it.id }.toSet()
        val thisSelected = stillSelected.isEmpty()
        return State(
            thisDeviceSelected = thisSelected,
            selectedSpeakerIds = stillSelected,
            devices = listOf(thisDev.copy(selected = thisSelected, protocol = null)) + others,
        )
    }

    /** 从名称/型号推断 UI 形态（图标）；不影响可投性。 */
    fun looksLikeTv(name: String, model: String = ""): Boolean {
        val s = "$name $model"
        return s.contains("TV", true) ||
            s.contains("电视", true) ||
            s.contains("G Pro", true) ||
            s.contains("显示器", true) ||
            s.contains("REDMI G", true) ||
            s.contains("Monitor", true)
    }

    /**
     * 解析 `NativeBridge.discover` 单行输出：
     * `ip\tname\tmodel\tadvertises_miplay\tservice\tapps`
     *
     * 规则：
     * - 声明 `advertises_miplay=1`（`apps=[5]`）或 `_mi-connect` 上的电视 → `castable=true`, `protocol=MIPLAY`
     * - 其它未声明 MiPlay 能力的节点（如 `_lyra-mdns._udp.local` 无 `apps=[5]`） → `castable=false`, `protocol=null`
     * - 绝不单纯按「十六进制名字」正则一刀切隐藏设备
     */
    fun parseDiscoveredLine(line: String): DiscoveredRow? {
        val trimmed = line.trimStart()
        if (trimmed.isBlank() || trimmed.startsWith("ERROR")) return null
        val p = line.split('\t')
        val ip = p.getOrElse(0) { "" }.trim()
        if (ip.isBlank()) return null
        val name = p.getOrElse(1) { ip }.trim()
        val model = p.getOrElse(2) { "" }.trim()
        val advertisesMiPlay = p.getOrElse(3) { "1" }.trim() == "1"
        val service = p.getOrElse(4) { "" }.trim()
        val apps = p.getOrElse(5) { "" }.trim()
        val kind = if (looksLikeTv(name, model)) Kind.TV else Kind.SPEAKER
        val isMiConnect = service.isBlank() || service.contains("_mi-connect", ignoreCase = true)
        val castable = advertisesMiPlay || (kind == Kind.TV && isMiConnect)
        val label = listOf(name, model).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { ip }
        val detail = if (castable) {
            ""
        } else {
            "未声明 MiPlay 投送能力（service=${service.ifBlank { "unknown" }} apps=${apps.ifBlank { "none" }}）"
        }
        val device = Device(
            id = ip,
            name = label,
            kind = kind,
            castable = castable,
            detail = detail,
            protocol = if (castable) CastProtocol.MIPLAY else null,
        )
        val logLine =
            "discover_row ip=$ip name=$name model=$model advertises_miplay=$advertisesMiPlay " +
                "service=${service.ifBlank { "unknown" }} apps=${apps.ifBlank { "none" }} " +
                "kind=$kind castable=$castable visible_default=$castable"
        return DiscoveredRow(device, advertisesMiPlay, service, apps, castable, logLine)
    }

    fun filterVisibleDevices(devices: List<Device>, showAll: Boolean): List<Device> {
        if (showAll) return devices
        return devices.filter { d ->
            d.kind == Kind.THIS_DEVICE || d.castable || d.protocol != null
        }
    }

    /**
     * 提取可持久化缓存的常驻设备（仅保留可投送的音箱/电视，去重并限制上限）。
     */
    fun extractCacheableDevices(devices: List<Device>, limit: Int = 16): List<Device> =
        devices
            .filter { it.isCastSink && it.castable }
            .distinctBy { it.id }
            .map { it.copy(selected = false) }
            .take(limit.coerceAtLeast(0))

    /**
     * 合并本轮实时扫描结果与本地缓存的常驻设备：
     * - 实时扫描到的设备优先，覆盖同 id 的旧缓存信息；
     * - 本轮未响应但此前缓存过的可投设备保留在末尾，避免刚打开 App 或单次 mDNS 丢包时音箱消失。
     */
    fun mergeCachedAndDiscovered(
        cached: List<Device>,
        discovered: List<Device>,
    ): List<Device> {
        val fresh = discovered.filter { it.kind != Kind.THIS_DEVICE }
        val freshIds = fresh.map { it.id }.toSet()
        val retainedCache = extractCacheableDevices(cached).filter { it.id !in freshIds }
        return fresh + retainedCache
    }
}
