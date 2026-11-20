package com.miplay.sender.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences：只存包名/标签/签名摘要，以及最近使用历史（绝不持久化 UID）。 */
class PrefsTargetAppStore(context: Context) : TargetAppStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun saveSelection(selection: TargetAppSelection) {
        val updatedRecent = buildList {
            add(selection)
            loadRecentSelections(MAX_RECENT).forEach { item ->
                if (item.packageName != selection.packageName) add(item)
            }
        }.take(MAX_RECENT)
        val arr = JSONArray()
        updatedRecent.forEach { item ->
            arr.put(
                JSONObject()
                    .put(KEY_PACKAGE, item.packageName)
                    .put(KEY_LABEL, item.label)
                    .put(KEY_CERT, item.signingCertSha256),
            )
        }
        prefs.edit()
            .putString(KEY_PACKAGE, selection.packageName)
            .putString(KEY_LABEL, selection.label)
            .putString(KEY_CERT, selection.signingCertSha256)
            .putString(KEY_RECENT, arr.toString())
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

    override fun loadRecentSelections(limit: Int): List<TargetAppSelection> {
        val cap = limit.coerceAtLeast(0)
        if (cap == 0) return emptyList()
        val raw = prefs.getString(KEY_RECENT, null).orEmpty()
        val parsed = mutableListOf<TargetAppSelection>()
        if (raw.isNotBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val pkg = obj.optString(KEY_PACKAGE).trim()
                    if (pkg.isBlank() || parsed.any { it.packageName == pkg }) continue
                    parsed += TargetAppSelection(
                        packageName = pkg,
                        label = obj.optString(KEY_LABEL, pkg),
                        signingCertSha256 = obj.optString(KEY_CERT, ""),
                    )
                }
            }
        }
        if (parsed.isEmpty()) {
            loadSelection()?.let { parsed += it }
        }
        return parsed.take(cap)
    }

    override fun clearSelection() {
        prefs.edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_LABEL)
            .remove(KEY_CERT)
            .remove(KEY_RECENT)
            .remove(KEY_UID_FORBIDDEN)
            .apply()
    }

    companion object {
        private const val PREFS = "miplay_target_app"
        private const val KEY_PACKAGE = "package"
        private const val KEY_LABEL = "label"
        private const val KEY_CERT = "cert"
        private const val KEY_RECENT = "recent_selections"
        private const val KEY_UID_FORBIDDEN = "uid"
        private const val MAX_RECENT = 8
    }
}
