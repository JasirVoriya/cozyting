package com.miaocast.app

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
        assertEquals(1000.0, com.miaocast.app.capture.PcmProbe.FREQ_A_HZ, 0.0)
        assertEquals(2000.0, com.miaocast.app.capture.PcmProbe.FREQ_B_HZ, 0.0)
    }

    @Test
    fun buildConfig_acceptanceFlag_matchesApplicationId() {
        // standard：IS_ACCEPTANCE=false / com.miaocast.app*
        // acceptance：IS_ACCEPTANCE=true / com.miaocast.app.acceptance*
        if (BuildConfig.IS_ACCEPTANCE) {
            assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.miaocast.app.acceptance"))
        } else {
            assertFalse(BuildConfig.APPLICATION_ID.startsWith("com.miaocast.app.acceptance"))
            assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.miaocast.app"))
        }
    }

    @Test
    fun buildGradle_doesNotContainHardcodedSigningPasswords() {
        val gradleFile = repoFile("build.gradle.kts").let {
            if (it.exists() && it.readText().contains("signingConfigs")) it else repoFile("app/build.gradle.kts")
        }
        assertTrue("app/build.gradle.kts missing: ${gradleFile.absolutePath}", gradleFile.exists())
        val text = gradleFile.readText()
        assertFalse(
            "Hardcoded signing password literal found in build.gradle.kts",
            Regex("""(storePassword|keyPassword)\s*=\s*"[^"]+"""").containsMatchIn(text),
        )
        assertFalse(
            "Release buildType must not append .release applicationIdSuffix to official com.miaocast.app package",
            Regex("""applicationIdSuffix\s*=\s*"\.release"""").containsMatchIn(text),
        )
        assertTrue(
            "build.gradle.kts should define release signingConfig",
            text.contains("""create("release")"""),
        )
    }
}
