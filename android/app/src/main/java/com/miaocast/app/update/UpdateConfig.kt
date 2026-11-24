package com.miaocast.app.update

import java.net.URI

/**
 * 妙投（MiaoCast）公开分发仓库与更新检测只读只走 HTTPS 的配置常量。
 *
 * - 源码仓保持私有；所有公开更新清单、Release 说明与已签名 APK 均托管于公开分发仓
 *   `JasirVoriya/miaocast-releases`，客户端零 Token、零遥测、匿名只读。
 */
object UpdateConfig {
    const val PUBLIC_RELEASE_REPO = "JasirVoriya/miaocast-releases"

    /** 主源：公开分发仓 main 根目录静态 JSON（Fastly CDN 分发，无 60 次/小时 REST API 限流，支持 ETag 304）。 */
    const val MANIFEST_PRIMARY_URL =
        "https://raw.githubusercontent.com/JasirVoriya/miaocast-releases/main/update.json"

    /** 兜底源：公开分发仓 GitHub Releases 列表 API（返回最近 30 版，支持跨版本区间日志聚合）。 */
    const val RELEASES_FALLBACK_API_URL =
        "https://api.github.com/repos/JasirVoriya/miaocast-releases/releases?per_page=30"

    /** 浏览器打开的公开 Releases 主页。 */
    const val PUBLIC_RELEASES_WEB_URL =
        "https://github.com/JasirVoriya/miaocast-releases/releases"

    /** 自动静默检查最小间隔：24 小时。 */
    const val AUTO_CHECK_COOLDOWN_MS = 24L * 60L * 60L * 1000L

    /** 纯静态 User-Agent（不携带任何设备型号、系统版本、序列号或局域网信息）。 */
    const val USER_AGENT = "MiaoCast-Updater"

    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 12_000
    const val DOWNLOAD_READ_TIMEOUT_MS = 25_000
    const val MAX_REDIRECTS = 5
    const val MAX_MANIFEST_BYTES = 512 * 1024 // 512 KB 上限，防异常响应 OOM
    const val MAX_APK_BYTES = 128L * 1024L * 1024L // 128 MB 安全上限

    /**
     * 强制 HTTPS 校验：尽管 Manifest 为局域网音箱保留 `usesCleartextTraffic="true"`，
     * 更新元数据与 APK 下载在代码层严格拒绝 `http://` 明文连接。
     */
    fun isAllowedHttpsUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            val uri = URI(trimmed)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
