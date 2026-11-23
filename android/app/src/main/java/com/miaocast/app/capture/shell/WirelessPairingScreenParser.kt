package com.miaocast.app.capture.shell

/**
 * 跨 ROM、跨语言通用的无线调试配对码（6 位 PIN）与配对端口提取器。
 *
 * 纯 Kotlin 实现（不依赖 Android Framework 类），可直接在 JVM 单元测试中覆盖：
 * - AOSP 标准控件 ID（`*:id/pairing_code` / `*:id/ip_addr`）
 * - 国产定制 ROM 混淆或移除 View ID 后的纯文本节点（中文、英文、任意语言）
 * - 带空格或连字符分隔的 6 位配对码（如 `061 683`、`061-683`）
 * - 区分底层「无线调试主页面连接端口」与顶层弹窗「6 位配对码专属配对端口」
 */
object WirelessPairingScreenParser {

    data class NodeSnapshot(
        val viewIdResourceName: String? = null,
        val text: String? = null,
        val contentDescription: String? = null,
        val windowId: Int = 0,
        val windowLayer: Int = 0,
    )

    data class ExtractedPairingInfo(
        val pinCode: String,
        val pairingPort: Int,
        val matchedByViewId: Boolean,
    )

    // 匹配 IPv4:Port（兼容半角 `:` 与全角 `：` 及前后空白）
    private val IPV4_PORT_REGEX = Regex(
        """\b((?:\d{1,3}\.){3}\d{1,3})\s*[:：]\s*(\d{2,5})\b""",
    )

    // 匹配完整节点仅为 6 位数字（或 3+3 分隔）
    private val STANDALONE_PIN_REGEX = Regex(
        """^\s*(\d{6}|\d{3}[\s\-]\d{3})\s*$""",
    )

    // 匹配内嵌在多行或复合文本中的独立 6 位数字
    private val EMBEDDED_PIN_REGEX = Regex(
        """(?<!\d)(\d{6})(?!\d)""",
    )

    private val PAIRING_CODE_ID_SUFFIXES = listOf(
        ":id/pairing_code",
        ":id/adb_pairing_code",
        ":id/wireless_pairing_code",
    )

    private val PAIRING_IP_ID_SUFFIXES = listOf(
        ":id/ip_addr",
        ":id/pairing_ip_addr",
        ":id/adb_ip_addr",
    )

    /**
     * 从当前活跃窗口组（按顶层窗口优先，或按 `windowLayer` 降序、`windowId` 分组）提取 6 位配对码与配对端口。
     *
     * @param nodes 无障碍收集到的节点快照列表
     * @param mdnsPairingPort 后台 mDNS (`_adb-tls-pairing._tcp`) 实时探测到的配对端口（0 表示尚未探测到）
     */
    fun extract(
        nodes: List<NodeSnapshot>,
        mdnsPairingPort: Int = 0,
    ): ExtractedPairingInfo? {
        if (nodes.isEmpty()) return null
        val validMdnsPort = mdnsPairingPort.takeIf { it in 1024..65535 } ?: 0

        // 按窗口分组，并优先扫描最顶层窗口（Dialog 弹窗层通常 windowLayer 更高，或排在列表前面）
        val windows = nodes
            .groupBy { it.windowId }
            .entries
            .sortedByDescending { entry -> entry.value.maxOfOrNull { it.windowLayer } ?: 0 }

        for ((_, windowNodes) in windows) {
            extractFromSingleWindow(windowNodes, validMdnsPort)?.let { return it }
        }

        // 若某些 ROM 将弹窗与主页面放在不同虚拟 windowId，做一次跨窗口汇总兜底（仅当存在明确 ViewId 或 mDNS 已确认端口）
        return extractFromSingleWindow(nodes, validMdnsPort, requireStrongSignal = true)
    }

    private fun extractFromSingleWindow(
        windowNodes: List<NodeSnapshot>,
        validMdnsPort: Int,
        requireStrongSignal: Boolean = false,
    ): ExtractedPairingInfo? {
        var viewIdPin: String? = null
        var viewIdPort: Int? = null

        val allPortOccurrences = mutableListOf<Pair<Int, Int>>() // (nodeIndex, port)
        val standalonePinOccurrences = mutableListOf<Pair<Int, String>>() // (nodeIndex, pin)

        windowNodes.forEachIndexed { idx, node ->
            val idLower = node.viewIdResourceName?.lowercase().orEmpty()
            val candidates = listOfNotNull(
                node.text?.trim()?.takeIf { it.isNotEmpty() },
                node.contentDescription?.trim()?.takeIf { it.isNotEmpty() },
            )

            for (raw in candidates) {
                // 1. 检查是否命中 AOSP / ROM 标准 View ID
                if (PAIRING_CODE_ID_SUFFIXES.any { idLower.endsWith(it) }) {
                    parsePinFromText(raw)?.let { viewIdPin = it }
                }
                if (PAIRING_IP_ID_SUFFIXES.any { idLower.endsWith(it) }) {
                    parsePortsFromText(raw).firstOrNull()?.let { viewIdPort = it }
                }

                // 2. 提取所有 IPv4:Port
                val portsInNode = parsePortsFromText(raw)
                portsInNode.forEach { port ->
                    allPortOccurrences.add(idx to port)
                }

                // 3. 提取独立 6 位数字节点（不能同时是 IP:Port 行里的 6 位，且排除端口号本身）
                val standaloneMatch = STANDALONE_PIN_REGEX.find(raw)
                if (standaloneMatch != null) {
                    val normalized = standaloneMatch.groupValues[1].replace(" ", "").replace("-", "")
                    if (normalized.length == 6 && normalized.all { it.isDigit() }) {
                        standalonePinOccurrences.add(idx to normalized)
                    }
                }
            }
        }

        // —— 路径 A：命中了明确的 AOSP View ID `*:id/pairing_code` ——
        if (viewIdPin != null) {
            val port = when {
                viewIdPort != null -> viewIdPort!!
                validMdnsPort > 0 -> validMdnsPort
                allPortOccurrences.isNotEmpty() -> allPortOccurrences.last().second
                else -> 0
            }
            return ExtractedPairingInfo(
                pinCode = viewIdPin!!,
                pairingPort = port,
                matchedByViewId = true,
            )
        }

        // —— 路径 B：语言无关的结构化特征提取（6 位数字 + IP:Port 共现，或 6 位数字 + mDNS 配对端口已激活） ——
        var candidatePin: String? = null
        var pinNodeIndex = -1

        if (standalonePinOccurrences.isNotEmpty()) {
            // 若存在多个独立 6 位数字，优先选择紧邻 IP:Port 节点的那一个（通常弹窗中 PIN 码与 IP:Port 距离 <= 4 个节点）
            val best = if (allPortOccurrences.isNotEmpty()) {
                standalonePinOccurrences.minByOrNull { (pinIdx, _) ->
                    allPortOccurrences.minOf { (portIdx, _) -> kotlin.math.abs(pinIdx - portIdx) }
                }
            } else {
                standalonePinOccurrences.lastOrNull()
            }
            if (best != null) {
                pinNodeIndex = best.first
                candidatePin = best.second
            }
        } else if (!requireStrongSignal) {
            // 某些定制 ROM 把标题、6 位配对码、IP:Port 拼在同一个 TextView 的多行文本里
            windowNodes.forEachIndexed { idx, node ->
                val combined = listOfNotNull(node.text, node.contentDescription).joinToString("\n")
                val ports = parsePortsFromText(combined)
                if (ports.isNotEmpty() || validMdnsPort > 0) {
                    val stripped = IPV4_PORT_REGEX.replace(combined, " ")
                    val embedded = EMBEDDED_PIN_REGEX.findAll(stripped)
                        .map { it.groupValues[1] }
                        .firstOrNull { pin -> ports.none { it.toString() == pin } }
                    if (embedded != null) {
                        candidatePin = embedded
                        pinNodeIndex = idx
                    }
                }
            }
        }

        val pin = candidatePin ?: return null

        // 共现校验：必须同窗存在 IPv4:Port，或后台 mDNS 已探测到活跃的配对端口
        val hasPortCoOccurrence = allPortOccurrences.isNotEmpty() || viewIdPort != null || validMdnsPort > 0
        if (!hasPortCoOccurrence) return null

        // 选择最匹配的配对端口：
        // 1. 优先 ViewID `:id/ip_addr`
        // 2. 其次选择与 6 位 PIN 码节点在视图树中距离最近的 IP:Port（避免误取底层页面主连接端口）
        // 3. 若屏幕上未提取到端口或与 mDNS 校验，回退 validMdnsPort
        val nearestScreenPort = if (allPortOccurrences.isNotEmpty() && pinNodeIndex >= 0) {
            allPortOccurrences.minByOrNull { (portIdx, _) ->
                kotlin.math.abs(portIdx - pinNodeIndex)
            }?.second
        } else {
            allPortOccurrences.lastOrNull()?.second
        }

        val resolvedPort = when {
            viewIdPort != null -> viewIdPort!!
            nearestScreenPort != null -> nearestScreenPort
            validMdnsPort > 0 -> validMdnsPort
            else -> 0
        }

        return ExtractedPairingInfo(
            pinCode = pin,
            pairingPort = resolvedPort,
            matchedByViewId = false,
        )
    }

    internal fun parsePinFromText(raw: String): String? {
        val m = STANDALONE_PIN_REGEX.find(raw)
        if (m != null) {
            val digits = m.groupValues[1].replace(" ", "").replace("-", "")
            if (digits.length == 6 && digits.all { it.isDigit() }) return digits
        }
        val stripped = IPV4_PORT_REGEX.replace(raw, " ")
        return EMBEDDED_PIN_REGEX.find(stripped)?.groupValues?.get(1)
    }

    internal fun parsePortsFromText(raw: String): List<Int> {
        return IPV4_PORT_REGEX.findAll(raw)
            .mapNotNull { match ->
                val ip = match.groupValues[1]
                val port = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (!isValidIpv4(ip) || port !in 1024..65535) null else port
            }
            .toList()
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return@all false
            n in 0..255
        }
    }
}
