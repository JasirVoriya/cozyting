package com.miplay.sender.update

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用私有缓存目录内的流式 APK 下载器（零外部存储权限，强制 HTTPS + SHA-256 + 签名校验）。
 */
object ApkUpdateDownloader {

    sealed class DownloadResult {
        data class Verified(val apkFile: File) : DownloadResult()
        data class Failed(val reasonZh: String) : DownloadResult()
        data object Cancelled : DownloadResult()
    }

    fun updatesDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /** 清理残留历史安装包，避免占用缓存空间。 */
    fun cleanupOldApks(context: Context, keepFileName: String? = null) {
        val dir = File(context.cacheDir, "updates")
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != keepFileName) {
                runCatching { f.delete() }
            }
        }
    }

    fun downloadAndVerify(
        context: Context,
        decision: UpdateDecision,
        cancelled: AtomicBoolean,
        onProgress: (ApkDownloadProgress) -> Unit,
    ): DownloadResult {
        if (!decision.installable || !UpdateConfig.isAllowedHttpsUrl(decision.downloadUrl)) {
            return DownloadResult.Failed("当前版本未提供可应用内直装的已签名 APK，请使用浏览器前往发布页下载")
        }

        // 1. 解析权威 SHA-256（优先内联摘要；命中 API 兜底源时再拉取 SHA256SUMS.txt）
        val expectedSha256 = resolveExpectedSha256(decision, onProgress)
            ?: return DownloadResult.Failed(
                "未获取到该安装包的官方 SHA-256 校验值，出于安全考虑已禁止应用内直装，请改用浏览器核验下载",
            )
        if (cancelled.get()) return DownloadResult.Cancelled

        val safeName = sanitizeApkFileName(decision.assetName.ifBlank { "miaocast-${decision.latestTag}.apk" })
        val dir = updatesDir(context)
        val targetFile = File(dir, safeName)
        val tmpFile = File(dir, "$safeName.part")

        // 若缓存中已有且 SHA-256 与签名完全通过，直接复用避免重复下载
        if (targetFile.isFile) {
            onProgress(ApkDownloadProgress(targetFile.length(), targetFile.length(), ApkDownloadProgress.Stage.VERIFYING))
            if (ApkIntegrityVerifier.verifyApkForUpgrade(context, targetFile, expectedSha256) is ApkIntegrityVerifier.VerifyResult.Ok) {
                cleanupOldApks(context, keepFileName = safeName)
                return DownloadResult.Verified(targetFile)
            }
            runCatching { targetFile.delete() }
        }

        cleanupOldApks(context)
        runCatching { tmpFile.delete() }

        val conn = try {
            UpdateHttpClient.openHttpsWithRedirects(
                initialUrl = decision.downloadUrl,
                accept = "application/vnd.android.package-archive, application/octet-stream, */*",
                readTimeoutMs = UpdateConfig.DOWNLOAD_READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return DownloadResult.Failed("连接下载服务器失败：${e.message ?: "网络异常"}")
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                return DownloadResult.Failed("安装包下载失败（HTTP $code），可改用浏览器下载")
            }
            val contentLength = conn.contentLengthLong.takeIf { it > 0L } ?: decision.assetSizeBytes
            if (contentLength > UpdateConfig.MAX_APK_BYTES) {
                return DownloadResult.Failed("安装包体积超出安全限制，已中止下载")
            }

            var totalRead = 0L
            var lastReportedBytes = -1L
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    while (true) {
                        if (cancelled.get()) {
                            runCatching { tmpFile.delete() }
                            return DownloadResult.Cancelled
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        totalRead += n
                        if (totalRead > UpdateConfig.MAX_APK_BYTES) {
                            throw IOException("安装包体积超出安全限制")
                        }
                        output.write(buf, 0, n)
                        if (lastReportedBytes < 0L || totalRead - lastReportedBytes >= 64 * 1024L || totalRead == contentLength) {
                            lastReportedBytes = totalRead
                            onProgress(
                                ApkDownloadProgress(
                                    bytesRead = totalRead,
                                    totalBytes = contentLength,
                                    stage = ApkDownloadProgress.Stage.DOWNLOADING,
                                ),
                            )
                        }
                    }
                    output.flush()
                }
            }

            if (cancelled.get()) {
                runCatching { tmpFile.delete() }
                return DownloadResult.Cancelled
            }

            onProgress(
                ApkDownloadProgress(
                    bytesRead = totalRead,
                    totalBytes = if (contentLength > 0L) contentLength else totalRead,
                    stage = ApkDownloadProgress.Stage.VERIFYING,
                ),
            )
            when (val verify = ApkIntegrityVerifier.verifyApkForUpgrade(context, tmpFile, expectedSha256)) {
                is ApkIntegrityVerifier.VerifyResult.Ok -> {
                    if (targetFile.exists()) runCatching { targetFile.delete() }
                    if (!tmpFile.renameTo(targetFile)) {
                        tmpFile.copyTo(targetFile, overwrite = true)
                        runCatching { tmpFile.delete() }
                    }
                    return DownloadResult.Verified(targetFile)
                }
                is ApkIntegrityVerifier.VerifyResult.Failed -> {
                    runCatching { tmpFile.delete() }
                    return DownloadResult.Failed(verify.reasonZh)
                }
            }
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            if (cancelled.get()) return DownloadResult.Cancelled
            return DownloadResult.Failed("下载中断：${e.message ?: "网络异常"}，可重试或改用浏览器下载")
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveExpectedSha256(
        decision: UpdateDecision,
        onProgress: (ApkDownloadProgress) -> Unit,
    ): String? {
        if (ApkIntegrityVerifier.isValidSha256Hex(decision.sha256)) {
            return decision.sha256.lowercase()
        }
        if (!UpdateConfig.isAllowedHttpsUrl(decision.sha256SumsUrl) || decision.assetName.isBlank()) {
            return null
        }
        onProgress(ApkDownloadProgress(0L, 0L, ApkDownloadProgress.Stage.FETCHING_CHECKSUM))
        val res = runCatching {
            UpdateHttpClient.getText(
                url = decision.sha256SumsUrl,
                accept = "text/plain, */*",
                maxBytes = 64 * 1024,
            )
        }.getOrNull() ?: return null
        return if (res is UpdateHttpClient.FetchResult.Ok) {
            ApkIntegrityVerifier.extractSha256ForAsset(res.body, decision.assetName)
        } else {
            null
        }
    }

    internal fun sanitizeApkFileName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        val withExt = if (cleaned.endsWith(".apk", ignoreCase = true)) cleaned else "$cleaned.apk"
        return withExt.take(96)
    }
}
