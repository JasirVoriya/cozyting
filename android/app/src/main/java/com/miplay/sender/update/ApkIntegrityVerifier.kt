package com.miplay.sender.update

import android.content.Context
import android.content.pm.PackageManager
import com.miplay.sender.compat.PackageSigningCompat
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * APK 完整性与身份三重校验（Fail-Closed）：
 * 1. 强制校验下载文件的 SHA-256 与官方清单 / `SHA256SUMS.txt` 完全匹配；
 * 2. 校验安装包 `packageName` 与当前已装应用一致（避免跨包/验收包误覆盖）；
 * 3. 校验安装包签名证书 SHA-256 与当前已装应用签名一致，提前拦截签名不匹配导致的静默安装失败。
 */
object ApkIntegrityVerifier {

    sealed class VerifyResult {
        data object Ok : VerifyResult()
        data class Failed(val reasonZh: String) : VerifyResult()
    }

    fun isValidSha256Hex(raw: String): Boolean {
        val s = raw.trim().lowercase()
        return s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
    }

    /** 解析 `SHA256SUMS.txt` 文本中指定文件名的 64 位小写 SHA-256 摘要。 */
    fun extractSha256ForAsset(sha256SumsText: String, assetName: String): String? {
        val target = assetName.trim()
        if (target.isEmpty()) return null
        for (line in sha256SumsText.replace("\r\n", "\n").lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val digest = parts[0].lowercase()
            val name = parts.last().removePrefix("*")
            if (name == target && isValidSha256Hex(digest)) {
                return digest
            }
        }
        return null
    }

    fun computeFileSha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return bytesToHex(md.digest())
    }

    fun verifySha256(file: File, expectedSha256: String): VerifyResult {
        if (!file.isFile || file.length() <= 0L) {
            return VerifyResult.Failed("安装包文件缺失或为空")
        }
        val expected = expectedSha256.trim().lowercase()
        if (!isValidSha256Hex(expected)) {
            return VerifyResult.Failed("缺少有效的官方 SHA-256 校验值，已拒绝应用内安装（请改用浏览器核验下载）")
        }
        val actual = runCatching { computeFileSha256Hex(file) }.getOrElse {
            return VerifyResult.Failed("计算安装包校验摘要失败：${it.message ?: "I/O 错误"}")
        }
        return if (constantTimeEquals(actual, expected)) {
            VerifyResult.Ok
        } else {
            VerifyResult.Failed("安装包 SHA-256 校验不匹配，文件可能已损坏或被篡改，已自动删除")
        }
    }

    /**
     * 完整三重校验：SHA-256 + 包名 + 签名证书 SHA-256。
     * 注意：若当前运行的是 `acceptance` 验收包（`com.miplay.sender.acceptance`），
     * 官方正式 APK 包名为 `com.miplay.sender`，会在此拦截并提示改用浏览器独立安装。
     */
    fun verifyApkForUpgrade(
        context: Context,
        apkFile: File,
        expectedSha256: String,
    ): VerifyResult {
        val shaRes = verifySha256(apkFile, expectedSha256)
        if (shaRes is VerifyResult.Failed) return shaRes

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archiveInfo = runCatching {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
            ?: return VerifyResult.Failed("无法解析下载的 APK 安装包，文件可能不完整")

        val installedPkg = context.packageName
        if (archiveInfo.packageName != installedPkg) {
            return VerifyResult.Failed(
                "安装包包名（${archiveInfo.packageName}）与当前应用（$installedPkg）不一致，请改用浏览器手动安装",
            )
        }

        val installedInfo = runCatching {
            PackageSigningCompat.packageInfoForSigning(pm, installedPkg)
        }.getOrNull()
        val installedDigests = installedInfo?.let { signerDigests(it) }.orEmpty()
        val archiveDigests = signerDigests(archiveInfo)

        if (archiveDigests.isEmpty()) {
            return VerifyResult.Failed("下载的 APK 未包含有效签名证书，已拒绝安装")
        }
        if (installedDigests.isNotEmpty() && installedDigests != archiveDigests) {
            return VerifyResult.Failed(
                "安装包签名证书与当前已装版本不一致（例如 Debug 与正式版不可直接覆盖），请卸载旧版后通过浏览器安装",
            )
        }
        return VerifyResult.Ok
    }

    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signers = PackageSigningCompat.apkContentsSigners(info) ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return signers.map { sig ->
            md.reset()
            bytesToHex(md.digest(sig.toByteArray()))
        }.toSet()
    }

    private fun bytesToHex(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
