package com.miplay.sender.capture

/** 测试与进程内默认实现：只存包名/标签/签名摘要，不存 UID。 */
class InMemoryTargetAppStore : TargetAppStore {
    private val data = linkedMapOf<String, String>()

    override fun saveSelection(selection: TargetAppSelection) {
        data.clear()
        data["package"] = selection.packageName
        data["label"] = selection.label
        data["cert"] = selection.signingCertSha256
    }

    override fun loadSelection(): TargetAppSelection? {
        val pkg = data["package"] ?: return null
        return TargetAppSelection(
            packageName = pkg,
            label = data["label"].orEmpty(),
            signingCertSha256 = data["cert"].orEmpty(),
        )
    }

    override fun clearSelection() {
        data.clear()
    }

    fun debugRawEntries(): Map<String, String> = data.toMap()
}
