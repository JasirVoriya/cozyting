package com.miplay.sender.update

import org.json.JSONObject

/**
 * 更新通道：
 * - `STABLE`：仅追踪正式版；
 * - `PRERELEASE`：同时追踪预览版（RC）与正式版，正式版发布后自动平滑升至正式版。
 */
enum class UpdateChannel(val id: String, val labelZh: String) {
    STABLE("stable", "稳定版"),
    PRERELEASE("prerelease", "预览版 (RC)"),
    ;

    companion object {
        fun fromId(raw: String?): UpdateChannel? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }

        /** 未手动配置过时：若当前安装版本本身为预发布版（如 `0.9.7-rc.6`）则默认预览版，否则稳定版。 */
        fun defaultForCurrentVersion(versionName: String): UpdateChannel =
            if (isPrereleaseVersion(versionName)) PRERELEASE else STABLE

        fun isPrereleaseVersion(versionName: String): Boolean {
            val cleaned = stripBuildSuffixes(versionName)
            return cleaned.contains('-')
        }

        /**
         * 展示与预发布判定专用：剥离前导 `v` 与 Gradle 追加的 `-release`/`-acceptance`/`-debug` 后缀，
         * 保留合法 SemVer 预发布段（如 `0.9.7-rc.6-release` -> `0.9.7-rc.6`）。
         */
        fun stripBuildSuffixes(raw: String): String {
            var s = raw.trim().removePrefix("v").removePrefix("V")
            val plusIdx = s.indexOf('+')
            if (plusIdx >= 0) s = s.substring(0, plusIdx)
            val dashIdx = s.indexOf('-')
            if (dashIdx < 0) return s
            val base = s.substring(0, dashIdx)
            val parts = s.substring(dashIdx + 1)
                .split('-')
                .filter { it.isNotEmpty() }
                .toMutableList()
            while (parts.isNotEmpty()) {
                val tail = parts.last().lowercase()
                if (tail == "release" || tail == "acceptance" || tail == "debug" || tail == "standard") {
                    parts.removeAt(parts.lastIndex)
                } else {
                    break
                }
            }
            return if (parts.isEmpty()) base else "$base-${parts.joinToString("-")}"
        }
    }
}

data class ChangelogEntry(
    val tag: String,
    val version: String,
    val prerelease: Boolean,
    val publishedAt: String,
    val notes: String,
) {
    val shortDate: String
        get() = if (publishedAt.length >= 10) publishedAt.substring(0, 10) else publishedAt
}

enum class UpdateDecisionStatus(val wire: String) {
    UP_TO_DATE("up_to_date"),
    UPDATE_AVAILABLE("update_available"),
    IGNORED("ignored"),
    UNSUPPORTED_OS("unsupported_os"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(raw: String): UpdateDecisionStatus =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) } ?: ERROR
    }
}

/** 对应 L1 Rust `miplay_update_evaluate_json` 的归一化决策结果。 */
data class UpdateDecision(
    val status: UpdateDecisionStatus,
    val currentVersion: String,
    val latestTag: String,
    val latestVersion: String,
    val isPrerelease: Boolean,
    val publishedAt: String,
    val releaseNotes: String,
    val changelogEntries: List<ChangelogEntry>,
    val versionsBehind: Int,
    val changelogTruncated: Boolean,
    val releaseUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val assetSizeBytes: Long,
    val sha256: String,
    val sha256SumsUrl: String,
    val installable: Boolean,
    val error: String,
) {
    val hasNewerVersion: Boolean
        get() = status == UpdateDecisionStatus.UPDATE_AVAILABLE ||
            status == UpdateDecisionStatus.IGNORED ||
            status == UpdateDecisionStatus.UNSUPPORTED_OS

    val shortDate: String
        get() = if (publishedAt.length >= 10) publishedAt.substring(0, 10) else publishedAt

    companion object {
        fun fromJson(rawJson: String): UpdateDecision {
            val obj = runCatching { JSONObject(rawJson) }.getOrNull()
                ?: return error("更新结果解析失败")
            val entries = mutableListOf<ChangelogEntry>()
            val arr = obj.optJSONArray("changelog_entries")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    entries += ChangelogEntry(
                        tag = item.optString("tag"),
                        version = item.optString("version"),
                        prerelease = item.optBoolean("prerelease", false),
                        publishedAt = item.optString("published_at"),
                        notes = item.optString("notes"),
                    )
                }
            }
            return UpdateDecision(
                status = UpdateDecisionStatus.fromWire(obj.optString("status", "error")),
                currentVersion = obj.optString("current_version"),
                latestTag = obj.optString("latest_tag"),
                latestVersion = obj.optString("latest_version"),
                isPrerelease = obj.optBoolean("is_prerelease", false),
                publishedAt = obj.optString("published_at"),
                releaseNotes = obj.optString("release_notes"),
                changelogEntries = entries,
                versionsBehind = obj.optInt("versions_behind", entries.size),
                changelogTruncated = obj.optBoolean("changelog_truncated", false),
                releaseUrl = obj.optString("release_url"),
                assetName = obj.optString("asset_name"),
                downloadUrl = obj.optString("download_url"),
                assetSizeBytes = obj.optLong("asset_size_bytes", 0L).coerceAtLeast(0L),
                sha256 = obj.optString("sha256").lowercase(),
                sha256SumsUrl = obj.optString("sha256sums_url"),
                installable = obj.optBoolean("installable", false),
                error = obj.optString("error"),
            )
        }

        fun error(message: String, currentVersion: String = ""): UpdateDecision = UpdateDecision(
            status = UpdateDecisionStatus.ERROR,
            currentVersion = currentVersion,
            latestTag = "",
            latestVersion = "",
            isPrerelease = false,
            publishedAt = "",
            releaseNotes = "",
            changelogEntries = emptyList(),
            versionsBehind = 0,
            changelogTruncated = false,
            releaseUrl = "",
            assetName = "",
            downloadUrl = "",
            assetSizeBytes = 0L,
            sha256 = "",
            sha256SumsUrl = "",
            installable = false,
            error = message,
        )
    }
}

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data class Checking(val manual: Boolean) : UpdateCheckState()
    data class Ready(
        val decision: UpdateDecision,
        val checkedAtMs: Long,
        val fromCache: Boolean = false,
    ) : UpdateCheckState()
    data class Failed(
        val message: String,
        val checkedAtMs: Long,
    ) : UpdateCheckState()
}

data class ApkDownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val stage: Stage,
) {
    enum class Stage {
        FETCHING_CHECKSUM,
        DOWNLOADING,
        VERIFYING,
    }

    val percent: Int
        get() = if (totalBytes > 0L) {
            ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }
}
