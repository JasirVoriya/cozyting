package com.miplay.sender.cast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地持久化常驻音箱/电视列表，支持打开 App 0 秒直接展示历史可用设备。
 */
class PrefsDiscoveredDeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadDevices(): List<CastRouteSelection.Device> {
        val raw = prefs.getString(KEY_DEVICES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<CastRouteSelection.Device>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name", id).trim()
                if (id.isBlank()) continue
                val kind = when (obj.optString("kind")) {
                    "TV" -> CastRouteSelection.Kind.TV
                    else -> CastRouteSelection.Kind.SPEAKER
                }
                val proto = when (obj.optString("protocol")) {
                    "MIPLAY", "" -> CastProtocol.MIPLAY
                    else -> CastProtocol.MIPLAY
                }
                if (list.none { it.id == id }) {
                    list += CastRouteSelection.Device(
                        id = id,
                        name = name.ifBlank { id },
                        kind = kind,
                        castable = true,
                        selected = false,
                        protocol = proto,
                    )
                }
            }
        }
        return list
    }

    fun saveDevices(devices: List<CastRouteSelection.Device>) {
        val cacheable = CastRouteSelection.extractCacheableDevices(devices, MAX_DEVICES)
        val arr = JSONArray()
        cacheable.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("kind", d.kind.name)
                    .put("protocol", (d.protocol ?: CastProtocol.MIPLAY).name),
            )
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "miplay_discovered_devices"
        private const val KEY_DEVICES = "devices_json"
        private const val MAX_DEVICES = 16
    }
}
