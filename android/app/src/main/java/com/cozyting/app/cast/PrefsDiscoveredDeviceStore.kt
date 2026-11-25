package com.cozyting.app.cast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地持久化常驻音箱/电视列表，支持打开 App 0 秒直接展示历史可用设备。
 */
class PrefsDiscoveredDeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadRawJson(): String = prefs.getString(KEY_DEVICES, null).orEmpty()

    fun loadDevices(): List<CastRouteSelection.Device> {
        val raw = loadRawJson()
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<CastRouteSelection.Device>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name", id).trim()
                if (id.isBlank()) continue
                val kind = when (obj.optString("kind").uppercase()) {
                    "TV" -> CastRouteSelection.Kind.TV
                    else -> CastRouteSelection.Kind.SPEAKER
                }
                val proto = when (obj.optString("protocol").uppercase()) {
                    "MIPLAY", "" -> CastProtocol.MIPLAY
                    else -> CastProtocol.MIPLAY
                }
                val nameFromDeviceInfo = obj.optBoolean("name_from_device_info", false)
                val roomName = obj.optString("room_name", "").trim()
                val roomInfoQueried = if (obj.has("room_info_queried")) {
                    obj.optBoolean("room_info_queried", false)
                } else {
                    roomName.isNotEmpty()
                }
                if (list.none { it.id == id }) {
                    list += CastRouteSelection.Device(
                        id = id,
                        name = name.ifBlank { id },
                        kind = kind,
                        castable = true,
                        selected = false,
                        protocol = proto,
                        nameFromDeviceInfo = nameFromDeviceInfo,
                        roomName = roomName,
                        roomInfoQueried = roomInfoQueried,
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
                    .put("protocol", (d.protocol ?: CastProtocol.MIPLAY).name)
                    .put("name_from_device_info", d.nameFromDeviceInfo)
                    .put("room_name", d.roomName)
                    .put("room_info_queried", d.roomInfoQueried),
            )
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    /** 读取上次选中的可投音箱/电视 ID 列表（有序，供冷启动置顶排序与一键恢复组合使用）。 */
    fun loadLastSelectedSpeakerIds(): List<String> {
        val raw = prefs.getString(KEY_LAST_SELECTED_SPEAKERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val id = arr.optString(i).trim()
                if (id.isNotEmpty() && id !in out) {
                    out += id
                }
            }
        }
        return out.take(MAX_DEVICES)
    }

    /** 保存上次选中的可投音箱/电视 ID 列表（去重保序）。 */
    fun saveLastSelectedSpeakerIds(ids: Collection<String>) {
        val clean = LinkedHashSet<String>()
        ids.forEach { raw ->
            val id = raw.trim()
            if (id.isNotEmpty() && id != "this") {
                clean += id
            }
        }
        val arr = JSONArray()
        clean.take(MAX_DEVICES).forEach { arr.put(it) }
        prefs.edit().putString(KEY_LAST_SELECTED_SPEAKERS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "cozyting_discovered_devices"
        private const val KEY_DEVICES = "devices_json"
        private const val KEY_LAST_SELECTED_SPEAKERS = "last_selected_speakers_json"
        private const val MAX_DEVICES = 16
    }
}
