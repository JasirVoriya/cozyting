package com.cozyting.app.diag

import org.json.JSONArray
import org.json.JSONObject

/**
 * 纯 Kotlin 日志行结构化解析器（Android 与纯 JVM 单测共用）。
 *
 * 将 `runtime.log` / `core.log` / `cast_ops.log` 的原始单行文本拆解为：
 * - 日期与时间（`yyyy-MM-dd` / `HH:mm:ss.SSS`）
 * - 级别（`I` / `W` / `E` / `D` / `V`）与来源标签（`CozyTing` / `CozyTingCore` / `CastOps` 等）
 * - 事件主干、结构化 `key=value` 参数对（支持含空格的值，如 `name=Sound Pro-1900`）
 * - 超长嵌入 JSON 负载（自动提取并支持按需缩进格式化）
 */
object LogEntryParser {

    data class JsonSummary(
        val byteCount: Int,
        val itemCount: Int?,
        val isArray: Boolean,
    )

    data class LogEntry(
        val id: Int,
        val raw: String,
        val date: String?,
        val time: String?,
        val level: Char,
        val tag: String,
        val isCore: Boolean,
        val isOps: Boolean,
        val eventTitle: String,
        val kvPairs: List<Pair<String, String>>,
        val plainBody: String?,
        val jsonRaw: String?,
        val jsonSummary: JsonSummary?,
    )

    private val STD_LINE_REGEX = Regex(
        """^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEA])/([^:\s]+):\s*(.*)$""",
    )
    private val OPS_TS_REGEX = Regex(
        """^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\s+(.*)$""",
    )
    private val KV_KEY_REGEX = Regex("""(?:^|\s)([A-Za-z_][A-Za-z0-9_.-]*)=+""")
    private const val MIN_JSON_COLLAPSE_LEN = 48

    fun parseLines(lines: List<String>, defaultIsOps: Boolean = false): List<LogEntry> {
        if (lines.isEmpty()) return emptyList()
        val result = ArrayList<LogEntry>(lines.size)
        for ((index, raw) in lines.withIndex()) {
            result.add(parseLine(index, raw, defaultIsOps))
        }
        return result
    }

    fun parseLine(id: Int, raw: String, defaultIsOps: Boolean = false): LogEntry {
        val trimmed = raw.trimEnd()
        val stdMatch = STD_LINE_REGEX.matchEntire(trimmed)
        if (stdMatch != null) {
            val date = stdMatch.groupValues[1]
            val time = stdMatch.groupValues[2]
            val level = stdMatch.groupValues[3].firstOrNull() ?: 'I'
            val tag = stdMatch.groupValues[4]
            val payload = stdMatch.groupValues[5]
            return buildEntry(
                id = id,
                raw = raw,
                date = date,
                time = time,
                level = level,
                tag = tag,
                isCore = tag == "CozyTingCore",
                isOps = defaultIsOps || tag.equals("CastOps", ignoreCase = true),
                payload = payload,
            )
        }

        val opsMatch = OPS_TS_REGEX.matchEntire(trimmed)
        if (opsMatch != null) {
            val date = opsMatch.groupValues[1]
            val time = opsMatch.groupValues[2]
            val payload = opsMatch.groupValues[3]
            val inferredLevel = when {
                payload.contains("ERROR", ignoreCase = true) || payload.contains("FAIL", ignoreCase = true) -> 'E'
                payload.contains("WARN", ignoreCase = true) -> 'W'
                else -> 'I'
            }
            return buildEntry(
                id = id,
                raw = raw,
                date = date,
                time = time,
                level = inferredLevel,
                tag = if (defaultIsOps) "CastOps" else "Log",
                isCore = false,
                isOps = defaultIsOps,
                payload = payload,
            )
        }

        return buildEntry(
            id = id,
            raw = raw,
            date = null,
            time = null,
            level = 'I',
            tag = if (defaultIsOps) "CastOps" else "",
            isCore = false,
            isOps = defaultIsOps,
            payload = trimmed,
        )
    }

    fun prettyFormatJson(rawJson: String): String {
        val s = rawJson.trim()
        return runCatching {
            when {
                s.startsWith("{") -> JSONObject(s).toString(2)
                s.startsWith("[") -> JSONArray(s).toString(2)
                else -> s
            }
        }.getOrDefault(s)
    }

    private fun buildEntry(
        id: Int,
        raw: String,
        date: String?,
        time: String?,
        level: Char,
        tag: String,
        isCore: Boolean,
        isOps: Boolean,
        payload: String,
    ): LogEntry {
        val (prefixWithoutJson, jsonRaw, jsonSummary) = extractTrailingJson(payload)
        val (eventTitle, kvPairs, plainBody) = parsePayloadBody(prefixWithoutJson, hasJson = jsonRaw != null)
        return LogEntry(
            id = id,
            raw = raw,
            date = date,
            time = time,
            level = level,
            tag = tag,
            isCore = isCore,
            isOps = isOps,
            eventTitle = eventTitle,
            kvPairs = kvPairs,
            plainBody = plainBody,
            jsonRaw = jsonRaw,
            jsonSummary = jsonSummary,
        )
    }

    private data class JsonExtract(
        val prefix: String,
        val jsonRaw: String?,
        val summary: JsonSummary?,
    )

    private fun extractTrailingJson(payload: String): JsonExtract {
        val trimmed = payload.trim()
        if (trimmed.length < MIN_JSON_COLLAPSE_LEN) {
            return JsonExtract(trimmed, null, null)
        }
        val lastChar = trimmed.last()
        if (lastChar != '}' && lastChar != ']') {
            return JsonExtract(trimmed, null, null)
        }
        val expectedOpen = if (lastChar == '}') '{' else '['

        var startIdx = trimmed.indexOf(expectedOpen)
        while (startIdx >= 0) {
            val candidate = trimmed.substring(startIdx).trim()
            if (candidate.length >= MIN_JSON_COLLAPSE_LEN) {
                val summary = trySummarizeJson(candidate)
                if (summary != null) {
                    val prefix = trimmed.substring(0, startIdx).trimEnd(' ', ':', '=')
                    return JsonExtract(prefix, candidate, summary)
                }
            }
            startIdx = trimmed.indexOf(expectedOpen, startIdx + 1)
        }
        return JsonExtract(trimmed, null, null)
    }

    private fun trySummarizeJson(candidate: String): JsonSummary? {
        return runCatching {
            val bytes = candidate.toByteArray(Charsets.UTF_8).size
            when {
                candidate.startsWith("[") -> {
                    val arr = JSONArray(candidate)
                    JsonSummary(byteCount = bytes, itemCount = arr.length(), isArray = true)
                }
                candidate.startsWith("{") -> {
                    val obj = JSONObject(candidate)
                    val preferredArrayCount = listOf("devices", "items", "cacheable", "sinks", "entries")
                        .firstNotNullOfOrNull { key -> obj.optJSONArray(key)?.length() }
                    JsonSummary(
                        byteCount = bytes,
                        itemCount = preferredArrayCount ?: obj.length(),
                        isArray = false,
                    )
                }
                else -> null
            }
        }.getOrNull()
    }

    private data class BodyParts(
        val eventTitle: String,
        val kvPairs: List<Pair<String, String>>,
        val plainBody: String?,
    )

    private fun parsePayloadBody(body: String, hasJson: Boolean): BodyParts {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return BodyParts(
                eventTitle = if (hasJson) "json_payload" else "",
                kvPairs = emptyList(),
                plainBody = null,
            )
        }

        val matches = KV_KEY_REGEX.findAll(trimmed).toList()
        if (matches.isEmpty()) {
            return BodyParts(
                eventTitle = trimmed,
                kvPairs = emptyList(),
                plainBody = null,
            )
        }

        val firstKeyRange = matches.first().groups[1]!!.range
        val prefix = trimmed.substring(0, firstKeyRange.first).trim()
        val pairs = ArrayList<Pair<String, String>>(matches.size)
        for (i in matches.indices) {
            val key = matches[i].groups[1]!!.value
            val valStart = matches[i].range.last + 1
            val valEnd = if (i + 1 < matches.size) matches[i + 1].range.first else trimmed.length
            val value = trimmed.substring(valStart, valEnd).trim()
            if (value.isNotEmpty()) {
                pairs.add(key to value)
            }
        }

        if (pairs.isEmpty()) {
            return BodyParts(eventTitle = trimmed, kvPairs = emptyList(), plainBody = null)
        }

        return if (prefix.isNotEmpty() && prefix.length <= 64) {
            BodyParts(eventTitle = prefix, kvPairs = pairs, plainBody = null)
        } else if (prefix.isNotEmpty()) {
            BodyParts(eventTitle = prefix.take(64), kvPairs = pairs, plainBody = prefix)
        } else {
            val fallbackTitle = if (hasJson) "json_payload" else "${pairs.first().first}=${pairs.first().second}"
            val remaining = if (hasJson) pairs else pairs.drop(1)
            BodyParts(eventTitle = fallbackTitle, kvPairs = remaining, plainBody = null)
        }
    }
}
