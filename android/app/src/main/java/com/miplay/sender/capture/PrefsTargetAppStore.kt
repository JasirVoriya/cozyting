package com.miplay.sender.capture

import android.content.Context

/** SharedPreferences：只存包名/标签/签名摘要。 */
class PrefsTargetAppStore(context: Context) : TargetAppStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun saveSelection(selection: TargetAppSelection) {
        prefs.edit()
            .putString(KEY_PACKAGE, selection.packageName)
            .putString(KEY_LABEL, selection.label)
            .putString(KEY_CERT, selection.signingCertSha256)
            .remove(KEY_UID_FORBIDDEN) // 确保从不持久化 UID
            .apply()
    }

    override fun loadSelection(): TargetAppSelection? {
        val pkg = prefs.getString(KEY_PACKAGE, null)?.takeIf { it.isNotBlank() } ?: return null
        return TargetAppSelection(
            packageName = pkg,
            label = prefs.getString(KEY_LABEL, "").orEmpty(),
            signingCertSha256 = prefs.getString(KEY_CERT, "").orEmpty(),
        )
    }

    override fun clearSelection() {
        prefs.edit().remove(KEY_PACKAGE).remove(KEY_LABEL).remove(KEY_CERT).remove(KEY_UID_FORBIDDEN).apply()
    }

    companion object {
        private const val PREFS = "miplay_target_app"
        private const val KEY_PACKAGE = "package"
        private const val KEY_LABEL = "label"
        private const val KEY_CERT = "cert"
        private const val KEY_UID_FORBIDDEN = "uid"
    }
}
