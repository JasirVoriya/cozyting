package com.miplay.sender.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkIntegrityVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun httpsPolicyRejectsPlainHttpAndInvalidUrls() {
        assertTrue(UpdateConfig.isAllowedHttpsUrl(UpdateConfig.MANIFEST_PRIMARY_URL))
        assertTrue(UpdateConfig.isAllowedHttpsUrl(UpdateConfig.RELEASES_FALLBACK_API_URL))
        assertFalse(UpdateConfig.isAllowedHttpsUrl("http://jasirvoriya.github.io/miaocast-releases/update.json"))
        assertFalse(UpdateConfig.isAllowedHttpsUrl("ftp://example.com/app.apk"))
        assertFalse(UpdateConfig.isAllowedHttpsUrl("file:///tmp/app.apk"))
        assertFalse(UpdateConfig.isAllowedHttpsUrl(""))
    }

    @Test
    fun sha256SumsExtractionAndFileVerificationWork() {
        val f = tmp.newFile("miplay-sender-v0.9.8-arm64-v8a.apk")
        f.writeBytes("miaocast-test-apk-payload".toByteArray())
        val realHash = ApkIntegrityVerifier.computeFileSha256Hex(f)
        assertTrue(ApkIntegrityVerifier.isValidSha256Hex(realHash))

        val sumsTxt = """
            0000000000000000000000000000000000000000000000000000000000000000  miplay-cli-v0.9.8-linux-x86_64.tar.gz
            $realHash  miplay-sender-v0.9.8-arm64-v8a.apk
        """.trimIndent()
        assertEquals(
            realHash,
            ApkIntegrityVerifier.extractSha256ForAsset(sumsTxt, "miplay-sender-v0.9.8-arm64-v8a.apk"),
        )
        assertNull(ApkIntegrityVerifier.extractSha256ForAsset(sumsTxt, "missing.apk"))

        assertTrue(ApkIntegrityVerifier.verifySha256(f, realHash) is ApkIntegrityVerifier.VerifyResult.Ok)
        assertTrue(ApkIntegrityVerifier.verifySha256(f, "f".repeat(64)) is ApkIntegrityVerifier.VerifyResult.Failed)
        assertTrue(ApkIntegrityVerifier.verifySha256(f, "") is ApkIntegrityVerifier.VerifyResult.Failed)
    }

    @Test
    fun updateChannelAndVersionSuffixNormalizationMatchContract() {
        assertEquals("0.9.7-rc.6", UpdateChannel.stripBuildSuffixes("0.9.7-rc.6-release"))
        assertEquals("0.9.7-rc.6", UpdateChannel.stripBuildSuffixes("v0.9.7-rc.6-acceptance"))
        assertEquals("0.9.6", UpdateChannel.stripBuildSuffixes("0.9.6-release"))
        assertEquals("0.9.6", UpdateChannel.stripBuildSuffixes("0.9.6-acceptance-release"))
        assertEquals(UpdateChannel.PRERELEASE, UpdateChannel.defaultForCurrentVersion("0.9.7-rc.6-release"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultForCurrentVersion("0.9.6-release"))
    }
}
