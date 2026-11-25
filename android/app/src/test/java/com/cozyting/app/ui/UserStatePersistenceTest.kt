package com.cozyting.app.ui

import android.content.Intent
import com.cozyting.app.BuildConfig
import com.cozyting.app.CastService
import com.cozyting.app.MainActivity
import com.cozyting.app.R
import com.cozyting.app.cast.CastProtocol
import com.cozyting.app.cast.CastRouteSelection
import com.cozyting.app.cast.CastRouteSelection.Kind
import com.cozyting.app.cast.PrefsDiscoveredDeviceStore
import com.cozyting.app.update.AppUpdateManager
import com.cozyting.app.update.UpdateHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserStatePersistenceTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        CastService.running.set(false)
        CastService.lastFailMessage = ""
        CastService.lastStats = ""
        AppUpdateManager.resetForTest()
    }

    @After
    fun tearDown() {
        CastService.running.set(false)
        CastService.lastFailMessage = ""
        CastService.lastStats = ""
        AppUpdateManager.resetForTest()
        AppLocaleManager.saveSelectedLanguage(ctx, AppLanguage.SYSTEM)
    }

    @Test
    fun masterAndSpeakerVolumesPersistAndClampSafelyOnColdStart() {
        val settings = PrefsCastSettingsStore(ctx)
        assertEquals(30, settings.loadMasterVolume(defaultVolume = 30))
        assertTrue(settings.loadSpeakerVolumes().isEmpty())

        settings.saveMasterVolume(48)
        settings.saveSpeakerVolume("192.0.2.10", 42)
        settings.saveSpeakerVolume("192.0.2.11", 82)
        assertEquals(48, settings.loadMasterVolume(defaultVolume = 30))
        assertEquals(mapOf("192.0.2.10" to 42, "192.0.2.11" to 82), settings.loadSpeakerVolumes())

        // 高音量保护开启（默认上限 60%）时，冷启动恢复应钳制到安全上限
        val enabledPolicy = VolumeSafetyGuard.Policy(enabled = true, safeLimit = 60)
        assertEquals(48, VolumeSafetyGuard.clampForColdStartRestore(enabledPolicy, 48))
        assertEquals(60, VolumeSafetyGuard.clampForColdStartRestore(enabledPolicy, 82))

        // 高音量保护关闭时，原样恢复
        val disabledPolicy = VolumeSafetyGuard.Policy(enabled = false, safeLimit = 60)
        assertEquals(82, VolumeSafetyGuard.clampForColdStartRestore(disabledPolicy, 82))

        if (!BuildConfig.IS_ACCEPTANCE) {
            // 模拟重启 MainActivity（保护开启，上限 60）
            settings.volumeGuardEnabled = true
            settings.volumeGuardSafeLimit = 60
            settings.saveMasterVolume(78)
            val actProtected = Robolectric.buildActivity(
                MainActivity::class.java,
                Intent().putExtra("ui_preview", true),
            ).setup().get()
            assertEquals(60, actProtected.currentMasterVolumeForTest())
            assertEquals(42, actProtected.currentSpeakerVolumeForTest("192.0.2.10"))
            assertEquals(60, actProtected.currentSpeakerVolumeForTest("192.0.2.11"))

            // 模拟重启 MainActivity（保护关闭 -> 原样恢复 78 / 82）
            settings.volumeGuardEnabled = false
            val actUnprotected = Robolectric.buildActivity(
                MainActivity::class.java,
                Intent().putExtra("ui_preview", true),
            ).setup().get()
            assertEquals(78, actUnprotected.currentMasterVolumeForTest())
            assertEquals(42, actUnprotected.currentSpeakerVolumeForTest("192.0.2.10"))
            assertEquals(82, actUnprotected.currentSpeakerVolumeForTest("192.0.2.11"))
        }
    }

    @Test
    fun lastSelectedSpeakersPersistPrioritizeAndResolveRestorable() {
        val deviceStore = PrefsDiscoveredDeviceStore(ctx)
        assertTrue(deviceStore.loadLastSelectedSpeakerIds().isEmpty())

        deviceStore.saveLastSelectedSpeakerIds(listOf("192.0.2.20", "this", "", "192.0.2.10", "192.0.2.20"))
        assertEquals(listOf("192.0.2.20", "192.0.2.10"), deviceStore.loadLastSelectedSpeakerIds())

        val devices = listOf(
            CastRouteSelection.Device("this", "本机播放", Kind.THIS_DEVICE, castable = true),
            CastRouteSelection.Device("192.0.2.10", "小爱音箱-0001", Kind.SPEAKER, castable = true, protocol = CastProtocol.MIPLAY),
            CastRouteSelection.Device("192.0.2.15", "小爱音箱-0002", Kind.SPEAKER, castable = true, protocol = CastProtocol.MIPLAY),
            CastRouteSelection.Device("192.0.2.20", "小爱音箱-0003", Kind.SPEAKER, castable = true, protocol = CastProtocol.MIPLAY),
            CastRouteSelection.Device("192.0.2.30", "未支持设备", Kind.SPEAKER, castable = false),
        )
        val prioritized = CastRouteSelection.prioritizeDevices(devices, listOf("192.0.2.20", "192.0.2.10"))
        assertEquals(
            listOf("this", "192.0.2.20", "192.0.2.10", "192.0.2.15", "192.0.2.30"),
            prioritized.map { it.id },
        )

        val restorable = CastRouteSelection.resolveRestorableSpeakerIds(
            devices,
            listOf("192.0.2.20", "192.0.2.30", "192.0.2.99", "192.0.2.10"),
        )
        assertEquals(listOf("192.0.2.20", "192.0.2.10"), restorable)
    }

    @Test
    fun dismissedUpdateBannerTagSurvivesProcessRestartUntilNewerVersion() {
        val settings = PrefsCastSettingsStore(ctx)
        settings.updateChannelRaw = "stable"
        settings.ignoredUpdateTag = ""
        settings.dismissedUpdateBannerTag = ""
        settings.cachedUpdateManifestJson = "{\"schema_version\":1}"

        fun decisionJson(tag: String, ver: String) = """
            {
              "status": "update_available",
              "current_version": "0.9.4",
              "latest_tag": "$tag",
              "latest_version": "$ver",
              "is_prerelease": false,
              "published_at": "2026-11-25T00:00:00Z",
              "release_notes": "notes",
              "changelog_entries": [],
              "versions_behind": 1,
              "changelog_truncated": false,
              "release_url": "https://github.com/JasirVoriya/cozyting/releases/tag/$tag",
              "asset_name": "cozyting-$tag-arm64-v8a.apk",
              "download_url": "https://github.com/JasirVoriya/cozyting/releases/download/$tag/cozyting-$tag-arm64-v8a.apk",
              "asset_size_bytes": 1024,
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "sha256sums_url": "",
              "installable": true,
              "error": ""
            }
        """.trimIndent()

        var servedTag = "v0.9.6"
        var servedVer = "0.9.6"
        fun configureManager() {
            AppUpdateManager.currentVersionProvider = { "0.9.4-release" }
            AppUpdateManager.isAcceptanceProvider = { false }
            AppUpdateManager.transport = AppUpdateManager.Transport { _, _ ->
                UpdateHttpClient.FetchResult.Ok("{\"schema_version\":1}", null)
            }
            AppUpdateManager.evaluator = AppUpdateManager.Evaluator { _, _, _, _, _, _, _ ->
                decisionJson(servedTag, servedVer)
            }
        }

        configureManager()
        AppUpdateManager.reevaluateFromCache(ctx, manual = false)
        assertNotNull(AppUpdateManager.activeBannerDecision())

        // 点 × 关闭 v0.9.6 横幅
        AppUpdateManager.dismissBannerForTag(ctx, "v0.9.6")
        assertEquals("v0.9.6", settings.dismissedUpdateBannerTag)
        assertNull(AppUpdateManager.activeBannerDecision())
        assertTrue(AppUpdateManager.shouldShowUpdateBadge())

        // 模拟进程冷重启：清空内存态后重新从缓存评估，v0.9.6 横幅应继续保持隐藏
        AppUpdateManager.resetForTest()
        configureManager()
        AppUpdateManager.reevaluateFromCache(ctx, manual = false)
        assertNull("Dismissed banner must stay hidden after restart", AppUpdateManager.activeBannerDecision())
        assertTrue("Settings update dot should still show", AppUpdateManager.shouldShowUpdateBadge())

        // 新版本 v0.9.7 发布后，横幅应重新出现
        servedTag = "v0.9.7"
        servedVer = "0.9.7"
        AppUpdateManager.reevaluateFromCache(ctx, manual = false)
        val newDecision = AppUpdateManager.activeBannerDecision()
        assertNotNull("Newer release tag must show banner again", newDecision)
        assertEquals("v0.9.7", newDecision!!.latestTag)
    }

    @Test
    fun restoreLastSpeakerStringsLocalizedAcrossZhEnJa() {
        val singles = mutableSetOf<String>()
        val multis = mutableSetOf<String>()
        for (lang in listOf(AppLanguage.ZH_CN, AppLanguage.EN, AppLanguage.JA)) {
            AppLocaleManager.saveSelectedLanguage(ctx, lang)
            val loc = AppLocaleManager.localizedContext(ctx)
            singles += loc.getString(R.string.main_restore_last_speakers)
            multis += loc.getString(R.string.main_restore_last_speakers_multi, 2)
        }
        assertEquals(3, singles.size)
        assertEquals(3, multis.size)
    }
}
