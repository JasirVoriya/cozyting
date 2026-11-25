package com.cozyting.app.capture

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

    fun hasInitializedFavoriteSources(): Boolean =
        prefs.getBoolean(KEY_FAVORITES_INITIALIZED, false)

    fun loadFavoriteSources(): List<FavoriteAudioSource> {
        val raw = prefs.getString(KEY_FAVORITES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val parsed = mutableListOf<FavoriteAudioSource>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pkg = obj.optString(KEY_PACKAGE).trim()
                if (pkg.isBlank() || parsed.any { it.packageName == pkg }) continue
                parsed += FavoriteAudioSource(
                    packageName = pkg,
                    autoDetectEnabled = obj.optBoolean(KEY_AUTO_DETECT, true),
                )
            }
        }
        return parsed
    }

    fun saveFavoriteSources(sources: List<FavoriteAudioSource>) {
        val dedup = mutableListOf<FavoriteAudioSource>()
        sources.forEach { item ->
            val pkg = item.packageName.trim()
            if (pkg.isNotBlank() && dedup.none { it.packageName == pkg }) {
                dedup += FavoriteAudioSource(pkg, item.autoDetectEnabled)
            }
        }
        val arr = JSONArray()
        dedup.forEach { item ->
            arr.put(
                JSONObject()
                    .put(KEY_PACKAGE, item.packageName)
                    .put(KEY_AUTO_DETECT, item.autoDetectEnabled),
            )
        }
        prefs.edit()
            .putString(KEY_FAVORITES, arr.toString())
            .putBoolean(KEY_FAVORITES_INITIALIZED, true)
            .apply()
    }

    /** 首次尚未自定义常用音源名单时，自动按本机已安装的内置音乐/播客 App 初始化（默认开启自动识别）。 */
    fun ensureFavoriteSourcesInitialized(
        installedApps: List<LaunchableApp>,
        selfPackage: String? = null,
    ): List<FavoriteAudioSource> {
        if (hasInitializedFavoriteSources()) return loadFavoriteSources()
        val defaults = LaunchableAppCatalog.defaultFavoriteSources(installedApps, selfPackage = selfPackage)
        saveFavoriteSources(defaults)
        return defaults
    }

    fun addFavoriteSource(packageName: String, autoDetectEnabled: Boolean = true): List<FavoriteAudioSource> {
        val pkg = packageName.trim()
        val current = loadFavoriteSources().toMutableList()
        if (pkg.isNotBlank() && current.none { it.packageName == pkg }) {
            current.add(FavoriteAudioSource(pkg, autoDetectEnabled))
            saveFavoriteSources(current)
        }
        return current
    }

    fun removeFavoriteSource(packageName: String): List<FavoriteAudioSource> {
        val pkg = packageName.trim()
        val updated = loadFavoriteSources().filterNot { it.packageName == pkg }
        saveFavoriteSources(updated)
        return updated
    }

    fun setFavoriteAutoDetect(packageName: String, enabled: Boolean): List<FavoriteAudioSource> {
        val pkg = packageName.trim()
        val updated = loadFavoriteSources().map {
            if (it.packageName == pkg) it.copy(autoDetectEnabled = enabled) else it
        }
        saveFavoriteSources(updated)
        return updated
    }

    fun reorderFavoriteSources(fromPackage: String, toPackage: String): List<FavoriteAudioSource> {
        val current = loadFavoriteSources()
        val updated = LaunchableAppCatalog.reorderFavoriteSources(current, fromPackage, toPackage)
        if (updated != current) {
            saveFavoriteSources(updated)
        }
        return updated
    }

    override fun saveSharedUidConsent(packageName: String, sharedPackages: Collection<String>) {
        val pkg = packageName.trim()
        val normalized = sharedPackages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (pkg.isEmpty() || normalized.size <= 1) return
        val mapObj = loadConsentMapObject()
        val arr = JSONArray()
        normalized.forEach { arr.put(it) }
        mapObj.put(pkg, arr)
        prefs.edit()
            .putString(KEY_SHARED_UID_CONSENTS, mapObj.toString())
            .remove(KEY_UID_FORBIDDEN)
            .apply()
    }

    override fun loadSharedUidConsent(packageName: String): Set<String>? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null
        val arr = loadConsentMapObject().optJSONArray(pkg) ?: return null
        val set = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optString(i).trim()
            if (item.isNotEmpty()) set += item
        }
        return set.takeIf { it.size > 1 }
    }

    override fun clearSharedUidConsent(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val mapObj = loadConsentMapObject()
        if (!mapObj.has(pkg)) return
        mapObj.remove(pkg)
        prefs.edit()
            .putString(KEY_SHARED_UID_CONSENTS, mapObj.toString())
            .apply()
    }

    private fun loadConsentMapObject(): JSONObject {
        val raw = prefs.getString(KEY_SHARED_UID_CONSENTS, null).orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    companion object {
        private const val PREFS = "cozyting_target_app"
        private const val KEY_PACKAGE = "package"
        private const val KEY_LABEL = "label"
        private const val KEY_CERT = "cert"
        private const val KEY_RECENT = "recent_selections"
        private const val KEY_FAVORITES = "favorite_audio_sources"
        private const val KEY_FAVORITES_INITIALIZED = "favorite_audio_sources_initialized"
        private const val KEY_AUTO_DETECT = "auto_detect"
        private const val KEY_SHARED_UID_CONSENTS = "shared_uid_consents"
        private const val KEY_UID_FORBIDDEN = "uid"
        private const val MAX_RECENT = 8
    }
}
