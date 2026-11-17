package com.miplay.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 契约：验收变体独立包名；清单不申请 QUERY_ALL_PACKAGES；音源频率与探针一致。 */
class AcceptanceVariantContractTest {
    private fun repoFile(rel: String): File {
        val candidates = listOf(
            File(rel),
            File("../$rel"),
            File("../../$rel"),
            File("src/main/AndroidManifest.xml").absoluteFile.parentFile?.parentFile?.parentFile?.parentFile?.resolve(rel)
                ?: File(rel),
        )
        return candidates.firstOrNull { it.exists() } ?: File(rel)
    }

    @Test
    fun manifest_hasQueries_notQueryAllPackages() {
        val manifest = repoFile("src/main/AndroidManifest.xml")
        assertTrue("manifest missing: ${manifest.absolutePath}", manifest.exists())
        val text = manifest.readText()
        assertTrue(text.contains("android.intent.action.MAIN"))
        assertTrue(text.contains("android.intent.category.LAUNCHER"))
        // 注释里可能提到该权限名；禁止的是 uses-permission 声明
        assertFalse(
            Regex("""uses-permission[^>]*QUERY_ALL_PACKAGES""").containsMatchIn(text),
        )
    }

    @Test
    fun probeFrequencies_matchDocumentedTestTones() {
        assertEquals(1000.0, com.miplay.sender.capture.PcmProbe.FREQ_A_HZ, 0.0)
        assertEquals(2000.0, com.miplay.sender.capture.PcmProbe.FREQ_B_HZ, 0.0)
    }

    @Test
    fun buildConfig_acceptanceFlag_matchesApplicationId() {
        // standard：IS_ACCEPTANCE=false / com.miplay.sender*
        // acceptance：IS_ACCEPTANCE=true / com.miplay.sender.acceptance*
        if (BuildConfig.IS_ACCEPTANCE) {
            assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.miplay.sender.acceptance"))
        } else {
            assertFalse(BuildConfig.APPLICATION_ID.startsWith("com.miplay.sender.acceptance"))
            assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.miplay.sender"))
        }
    }
}
