package com.miplay.sender.capture

/**
 * 严格单选 App：只接受已保存包名 → 每次开始重新解析 UID。
 * 卸载/不可见/共享 UID/跨用户/签名身份变化一律 fail-closed，绝不回退全系统捕获。
 */
class CaptureTargetResolver(
    private val lookup: PackageIdentityLookup,
) {
    fun resolveForCapture(
        savedPackage: String?,
        expectedSigningCertSha256: String? = null,
    ): CaptureTargetResult {
        val pkg = savedPackage?.trim().orEmpty()
        if (pkg.isEmpty()) {
            return CaptureTargetResult.Fail(
                CaptureFailReason.NO_SELECTION,
                "请先选择要投送的 App。未选择时不会开始捕获，也不会回退到全系统音频。",
            )
        }
        val id = lookup.lookup(pkg)
            ?: return CaptureTargetResult.Fail(
                CaptureFailReason.NOT_FOUND,
                "找不到或对本用户不可见：$pkg。可能已卸载/被隐藏，请重新选择。不会回退全系统捕获。",
            )
        if (!id.sameUser) {
            return CaptureTargetResult.Fail(
                CaptureFailReason.CROSS_USER,
                "目标 App 不在当前用户资料中（例如工作资料）。本发送端仅支持同用户，已停止。",
            )
        }
        val shared = !id.sharedUserId.isNullOrBlank() || id.packagesSharingUid.distinct().size > 1
        if (shared) {
            return CaptureTargetResult.Fail(
                CaptureFailReason.SHARED_UID,
                "目标 App 使用共享 UID，无法保证包级音频隔离，已拒绝捕获。请选择独立 UID 的应用。",
            )
        }
        if (expectedSigningCertSha256 != null &&
            expectedSigningCertSha256.isNotBlank() &&
            !expectedSigningCertSha256.equals(id.signingCertSha256, ignoreCase = true)
        ) {
            return CaptureTargetResult.Fail(
                CaptureFailReason.IDENTITY_CHANGED,
                "目标 App 签名身份已变化（可能已卸载重装）。请重新选择后再开始，不会沿用旧授权或全系统捕获。",
            )
        }
        return CaptureTargetResult.Ok(
            packageName = id.packageName,
            uid = id.uid,
            signingCertSha256 = id.signingCertSha256,
        )
    }
}
