package com.cozyting.app.capture

/**
 * 严格单选 App：只接受已保存包名 → 每次开始重新解析 UID。
 * 卸载/不可见/跨用户/系统特权共享 UID/未授权多包共享 UID/签名身份变化一律 fail-closed，绝不回退全系统捕获。
 *
 * 共享 UID 三层策略：
 * 1. 系统/特权 UID（appId < 10000 或 `android.uid.*`）：始终硬拒绝；
 * 2. 仅 Manifest 历史声明 `sharedUserId`、但本机实际该 UID 下只有目标单包（`packagesSharingUid <= 1`）：等价独立 UID，直接放行；
 * 3. 本机实际有 ≥2 个普通应用共享同一 UID：默认 Fail-Closed 拒绝，仅在用户对精确共享包集合快照显式授权后放行；
 *    若后续新增/变更共享包导致集合不匹配，自动失效并重新拒绝。
 */
class CaptureTargetResolver(
    private val lookup: PackageIdentityLookup,
) {
    fun resolveForCapture(
        savedPackage: String?,
        expectedSigningCertSha256: String? = null,
        approvedSharedUidPackages: Set<String>? = null,
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
                targetPackage = pkg,
            )
        if (!id.sameUser) {
            return CaptureTargetResult.Fail(
                CaptureFailReason.CROSS_USER,
                "目标 App 不在当前用户资料中（例如工作资料）。本发送端仅支持同用户，已停止。",
                targetPackage = id.packageName,
            )
        }
        val sharingPackages = id.packagesSharingUid
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(id.packageName) }
            .distinct()
            .sorted()
        if (isSystemOrPrivilegedUid(id.uid, id.sharedUserId)) {
            return CaptureTargetResult.Fail(
                CaptureFailReason.SYSTEM_SHARED_UID,
                "目标 App 使用系统级共享 UID，可能混入系统或通话音频，已拒绝捕获。请选择普通独立应用。",
                targetPackage = id.packageName,
                sharedPackages = sharingPackages,
            )
        }
        if (sharingPackages.size > 1) {
            val approvedSet = approvedSharedUidPackages
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
            if (approvedSet == null || approvedSet != sharingPackages.toSet()) {
                return CaptureTargetResult.Fail(
                    CaptureFailReason.SHARED_UID,
                    "目标 App 与其他应用共享同一 UID，需在应用列表中重新选择并确认共享音频通道后方可捕获。",
                    targetPackage = id.packageName,
                    sharedPackages = sharingPackages,
                )
            }
        }
        if (expectedSigningCertSha256 != null &&
            expectedSigningCertSha256.isNotBlank() &&
            !expectedSigningCertSha256.equals(id.signingCertSha256, ignoreCase = true)
        ) {
            return CaptureTargetResult.Fail(
                CaptureFailReason.IDENTITY_CHANGED,
                "目标 App 签名身份已变化（可能已卸载重装）。请重新选择后再开始，不会沿用旧授权或全系统捕获。",
                targetPackage = id.packageName,
            )
        }
        return CaptureTargetResult.Ok(
            packageName = id.packageName,
            uid = id.uid,
            signingCertSha256 = id.signingCertSha256,
        )
    }

    companion object {
        private const val PER_USER_RANGE = 100_000
        private const val FIRST_APPLICATION_UID = 10_000

        internal fun isSystemOrPrivilegedUid(uid: Int, sharedUserId: String?): Boolean {
            val appId = if (uid >= 0) uid % PER_USER_RANGE else -1
            if (appId in 0 until FIRST_APPLICATION_UID) return true
            val normalizedShared = sharedUserId?.trim().orEmpty()
            return normalizedShared.startsWith("android.uid.", ignoreCase = true)
        }
    }
}
