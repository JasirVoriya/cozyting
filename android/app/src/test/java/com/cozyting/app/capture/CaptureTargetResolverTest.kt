package com.cozyting.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 严格单选 App UID：保存包名、开始时重解析；卸载/身份变化/共享 UID/跨用户 fail-closed，
 * 绝不能回退全系统捕获。
 */
class CaptureTargetResolverTest {

    private val lookup = FakePackageLookup()
    private val resolver = CaptureTargetResolver(lookup)

    @Test
    fun emptySelection_failsClosed() {
        val r = resolver.resolveForCapture(savedPackage = null)
        assertTrue(r is CaptureTargetResult.Fail)
        assertEquals(CaptureFailReason.NO_SELECTION, (r as CaptureTargetResult.Fail).reason)
    }

    @Test
    fun blankPackage_failsClosed() {
        val r = resolver.resolveForCapture(savedPackage = "  ")
        assertTrue(r is CaptureTargetResult.Fail)
        assertEquals(CaptureFailReason.NO_SELECTION, (r as CaptureTargetResult.Fail).reason)
    }

    @Test
    fun missingPackage_failsClosed_notSystemWide() {
        lookup.clear()
        val r = resolver.resolveForCapture(savedPackage = "com.example.gone")
        assertTrue(r is CaptureTargetResult.Fail)
        assertEquals(CaptureFailReason.NOT_FOUND, (r as CaptureTargetResult.Fail).reason)
        assertTrue(r.messageZh.contains("卸载") || r.messageZh.contains("找不到") || r.messageZh.contains("不可见"))
    }

    @Test
    fun happyPath_resolvesUidFromPackage() {
        lookup.put(
            PackageIdentity(
                packageName = "com.example.player.a",
                uid = 10123,
                sharedUserId = null,
                packagesSharingUid = listOf("com.example.player.a"),
                sameUser = true,
                signingCertSha256 = "aaa",
            ),
        )
        val r = resolver.resolveForCapture(savedPackage = "com.example.player.a")
        val ok = r as CaptureTargetResult.Ok
        assertEquals("com.example.player.a", ok.packageName)
        assertEquals(10123, ok.uid)
    }

    @Test
    fun sharedUserId_rejected() {
        lookup.put(
            PackageIdentity(
                packageName = "com.example.shared",
                uid = 10100,
                sharedUserId = "android.uid.shared",
                packagesSharingUid = listOf("com.example.shared", "com.example.other"),
                sameUser = true,
                signingCertSha256 = "bbb",
            ),
        )
        val r = resolver.resolveForCapture(savedPackage = "com.example.shared")
        assertTrue(r is CaptureTargetResult.Fail)
        assertEquals(CaptureFailReason.SHARED_UID, (r as CaptureTargetResult.Fail).reason)
    }

    @Test
    fun multiplePackagesSameUid_rejectedEvenWithoutSharedUserId() {
        lookup.put(
            PackageIdentity(
                packageName = "com.example.a",
                uid = 10555,
                sharedUserId = null,
                packagesSharingUid = listOf("com.example.a", "com.example.b"),
                sameUser = true,
                signingCertSha256 = "ccc",
            ),
        )
        val r = resolver.resolveForCapture(savedPackage = "com.example.a")
        assertTrue(r is CaptureTargetResult.Fail)
        assertEquals(CaptureFailReason.SHARED_UID, (r as CaptureTargetResult.Fail).reason)
    }

    @Test
    fun crossUserPackage_rejected() {
        lookup.put(
            PackageIdentity(
                packageName = "com.example.work",
                uid = 10100123,
                sharedUserId = null,
                packagesSharingUid = listOf("com.example.work"),
                sameUser = false,
                signingCertSha256 = "ddd",
            ),
        )
        val r = resolver.resolveForCapture(savedPackage = "com.example.work")
        assertTrue(r is CaptureTargetResult.Fail)
        assertEquals(CaptureFailReason.CROSS_USER, (r as CaptureTargetResult.Fail).reason)
    }

    @Test
    fun identityChangedAfterReselect_detectedByCertChange() {
        lookup.put(
            PackageIdentity(
                packageName = "com.example.player",
                uid = 10111,
                sharedUserId = null,
                packagesSharingUid = listOf("com.example.player"),
                sameUser = true,
                signingCertSha256 = "old-cert",
            ),
        )
        val first = resolver.resolveForCapture(savedPackage = "com.example.player") as CaptureTargetResult.Ok
        lookup.put(
            PackageIdentity(
                packageName = "com.example.player",
                uid = 10222, // 重装后 UID 也会变
                sharedUserId = null,
                packagesSharingUid = listOf("com.example.player"),
                sameUser = true,
                signingCertSha256 = "new-cert",
            ),
        )
        val r = resolver.resolveForCapture(
            savedPackage = "com.example.player",
            expectedSigningCertSha256 = first.signingCertSha256,
        )
        assertTrue(r is CaptureTargetResult.Fail)
        assertEquals(CaptureFailReason.IDENTITY_CHANGED, (r as CaptureTargetResult.Fail).reason)
    }

    @Test
    fun captureConfig_requiresUid_neverBlankWhitelist() {
        try {
            CaptureConfigSpec.appScoped(uid = null, usages = CaptureConfigSpec.DEFAULT_USAGES)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("UID") || e.message!!.contains("uid"))
        }
    }

    @Test
    fun captureConfig_appMode_includesExactUidAndUsages() {
        val spec = CaptureConfigSpec.appScoped(uid = 10123, usages = CaptureConfigSpec.DEFAULT_USAGES)
        assertEquals(listOf(10123), spec.matchingUids)
        assertTrue(spec.matchingUids.isNotEmpty())
        assertEquals(CaptureConfigSpec.DEFAULT_USAGES, spec.matchingUsages)
        assertTrue(spec.isStrictAppFilter)
    }

    @Test
    fun systemWideConfig_forbiddenForAppSource() {
        try {
            CaptureConfigSpec.forbidSystemWideFallback(requestedAppPackage = "com.example.a")
            fail("must not allow system-wide fallback")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("全系统") || e.message!!.contains("回退") || e.message!!.contains("fallback"))
        }
    }
}

private class FakePackageLookup : PackageIdentityLookup {
    private val map = mutableMapOf<String, PackageIdentity>()
    fun clear() = map.clear()
    fun put(id: PackageIdentity) {
        map[id.packageName] = id
    }

    override fun lookup(packageName: String): PackageIdentity? = map[packageName]
}
