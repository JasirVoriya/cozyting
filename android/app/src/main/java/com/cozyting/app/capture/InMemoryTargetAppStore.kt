package com.cozyting.app.capture

/** 测试与进程内默认实现：只存包名/标签/签名摘要，不存 UID。 */
class InMemoryTargetAppStore : TargetAppStore {
    private val data = linkedMapOf<String, String>()
    private val recent = mutableListOf<TargetAppSelection>()

    override fun saveSelection(selection: TargetAppSelection) {
        data.clear()
        data["package"] = selection.packageName
        data["label"] = selection.label
        data["cert"] = selection.signingCertSha256
        recent.removeAll { it.packageName == selection.packageName }
        recent.add(0, selection)
        while (recent.size > MAX_RECENT) {
            recent.removeAt(recent.lastIndex)
        }
    }

    override fun loadSelection(): TargetAppSelection? {
        val pkg = data["package"] ?: return null
        return TargetAppSelection(
            packageName = pkg,
            label = data["label"].orEmpty(),
            signingCertSha256 = data["cert"].orEmpty(),
        )
    }

    override fun loadRecentSelections(limit: Int): List<TargetAppSelection> =
        recent.take(limit.coerceAtLeast(0))

    override fun clearSelection() {
        data.clear()
        recent.clear()
    }

    fun debugRawEntries(): Map<String, String> = data.toMap()

    companion object {
        private const val MAX_RECENT = 8
    }
}
