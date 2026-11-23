package com.miaocast.app.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 零第三方依赖的只读 HTTPS 客户端：
 * - 强制仅允许 `https://`，拒绝任何 `http://` 请求及 HTTPS -> HTTP 降级重定向；
 * - 零遥测 Header（仅 `Accept`、`If-None-Match` 与固定静态 `User-Agent`）；
 * - 支持 `ETag` 304 复用与有界响应体读取。
 */
object UpdateHttpClient {

    sealed class FetchResult {
        data class Ok(val body: String, val etag: String?) : FetchResult()
        data object NotModified : FetchResult()
        data class HttpError(val code: Int, val message: String) : FetchResult()
    }

    fun getText(
        url: String,
        etag: String? = null,
        accept: String = "application/json, application/vnd.github+json",
        maxBytes: Int = UpdateConfig.MAX_MANIFEST_BYTES,
    ): FetchResult {
        val conn = openHttpsWithRedirects(
            initialUrl = url,
            etag = etag,
            accept = accept,
            readTimeoutMs = UpdateConfig.READ_TIMEOUT_MS,
        )
        return try {
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> FetchResult.NotModified
                in 200..299 -> {
                    val body = readBoundedString(conn.inputStream, maxBytes)
                    val newEtag = conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    FetchResult.Ok(body, newEtag)
                }
                else -> FetchResult.HttpError(code, "HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 打开 HTTPS 连接并手动安全跟随 3xx 重定向：每一跳重定向目标都必须仍为 `https://`，
     * 防止中间人或异常响应通过 302 降级到明文 HTTP。
     */
    fun openHttpsWithRedirects(
        initialUrl: String,
        etag: String? = null,
        accept: String = "*/*",
        readTimeoutMs: Int = UpdateConfig.READ_TIMEOUT_MS,
    ): HttpsURLConnection {
        var current = initialUrl.trim()
        var redirects = 0
        while (true) {
            if (!UpdateConfig.isAllowedHttpsUrl(current)) {
                throw SecurityException("拒绝非 HTTPS 更新请求：$current")
            }
            val rawConn = URL(current).openConnection()
            val conn = rawConn as? HttpsURLConnection
                ?: throw SecurityException("拒绝非 HTTPS 连接：$current")
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
            conn.setRequestProperty("Accept", accept)
            if (redirects == 0 && !etag.isNullOrBlank()) {
                conn.setRequestProperty("If-None-Match", etag)
            }

            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")?.trim().orEmpty()
                conn.disconnect()
                if (location.isEmpty()) {
                    throw IOException("重定向缺少 Location 头")
                }
                redirects += 1
                if (redirects > UpdateConfig.MAX_REDIRECTS) {
                    throw IOException("重定向次数过多（>$redirects）")
                }
                current = URI(current).resolve(location).toString()
                continue
            }
            return conn
        }
    }

    private fun readBoundedString(input: InputStream, maxBytes: Int): String {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw IOException("响应体超过安全上限（${maxBytes / 1024} KB）")
                }
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
