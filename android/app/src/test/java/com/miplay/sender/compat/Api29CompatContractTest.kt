package com.miplay.sender.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TDD：API29（Android 10）最低兼容契约。
 * 这些断言在实现前应失败或锁定目标行为；实现后保持绿色。
 */
class Api29CompatContractTest {

    @Test
    fun minSdk_is29_not34() {
        val gradle = File("../build.gradle.kts").takeIf { it.isFile }
            ?: File("build.gradle.kts").takeIf { it.isFile }
            ?: File("app/build.gradle.kts").takeIf { it.isFile }
            ?: File("../../app/build.gradle.kts")
        // 单元测试工作目录通常为 android/app；优先读本模块脚本
        val text = sequenceOf(
            File("build.gradle.kts"),
            File("../app/build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("找不到 app/build.gradle.kts")
        assertTrue("minSdk must be 29", Regex("""minSdk\s*=\s*29""").containsMatchIn(text))
        assertFalse("minSdk must not stay 34", Regex("""minSdk\s*=\s*34""").containsMatchIn(text))
        assertTrue("targetSdk stays 36", Regex("""targetSdk\s*=\s*36""").containsMatchIn(text))
    }

    @Test
    fun blurCompat_doesNotReferenceRenderEffectInPublicEntry() {
        // 入口类字节码不得直接依赖 RenderEffect（避免 API29 类加载/校验炸）
        val entry = BlurCompat::class.java
        val path = entry.name.replace('.', '/') + ".class"
        val bytes = requireNotNull(entry.classLoader).getResourceAsStream(path)!!.readBytes()
        val asString = bytes.toString(Charsets.ISO_8859_1)
        assertFalse(
            "BlurCompat.class must not hard-reference android/graphics/RenderEffect",
            asString.contains("android/graphics/RenderEffect"),
        )
        assertEquals(29, BlurCompat.MIN_SDK_DOCUMENTED)
        assertTrue(BlurCompat.supportsRenderEffect(31))
        assertFalse(BlurCompat.supportsRenderEffect(29))
        assertFalse(BlurCompat.supportsRenderEffect(30))
    }

    @Test
    fun windowInsetsCompat_helperDocumentsApi30Gate() {
        assertEquals(30, WindowInsetsApplyCompat.TYPE_API)
        assertTrue(WindowInsetsApplyCompat.canUsePlatformTypes(30))
        assertFalse(WindowInsetsApplyCompat.canUsePlatformTypes(29))
    }

    @Test
    fun notificationPermission_onlyRequestedFromApi33() {
        assertFalse(NotificationPermissionCompat.shouldRequest(29))
        assertFalse(NotificationPermissionCompat.shouldRequest(32))
        assertTrue(NotificationPermissionCompat.shouldRequest(33))
        assertTrue(NotificationPermissionCompat.shouldRequest(36))
    }

    @Test
    fun coldStartPermissions_doNotIncludeAudioCapture() {
        val api29 = NotificationPermissionCompat.runtimePermissionsForColdStart(29).toList()
        val api33 = NotificationPermissionCompat.runtimePermissionsForColdStart(33).toList()

        assertFalse(api29.contains(android.Manifest.permission.RECORD_AUDIO))
        assertFalse(api33.contains(android.Manifest.permission.RECORD_AUDIO))
        assertTrue(api29.isEmpty())
        assertEquals(listOf(NotificationPermissionCompat.POST_NOTIFICATIONS), api33)
    }

    @Test
    fun packageSigningLookup_usesApi28SafeFlagsNotApi33OnlyOverload() {
        assertTrue(PackageSigningCompat.usesLegacyGetPackageInfoFlags)
        assertEquals(28, PackageSigningCompat.SIGNING_CERTS_SINCE)
    }

    @Test
    fun captureSelectionLayers_areDistinct() {
        val msg = CaptureSelectionLayers.explainMismatch(
            appPickerPackage = "com.luna.music",
            appPickerUid = 10379,
            systemDialogHint = "MediaProjection 测试音源A",
        )
        assertTrue(msg.contains("com.luna.music"))
        assertTrue(msg.contains("10379"))
        assertTrue(msg.contains("不会") || msg.contains("不能") || msg.contains("并未"))
        assertFalse(msg.contains("已自动切换 UID"))
    }

    @Test
    fun acceptanceVolumePolicy_defaultsMuteWithoutRestoring() {
        assertEquals(0, AcceptanceAudioDefaults.speakerPremutePercent(isAcceptance = true))
        assertEquals(30, AcceptanceAudioDefaults.speakerPremutePercent(isAcceptance = false))
        assertFalse(AcceptanceAudioDefaults.restoreLocalVolumeIndex(isAcceptance = true))
        assertTrue(AcceptanceAudioDefaults.restoreLocalVolumeIndex(isAcceptance = false))
    }

    @Test
    fun castServiceMuteInit_stillLateinit() {
        val f = com.miplay.sender.CastService::class.java.getDeclaredField("localMute")
        assertFalse(java.lang.reflect.Modifier.isFinal(f.modifiers))
    }
}
