package com.cozyting.app.update

import com.cozyting.app.compat.ApkInstallerCompat
import com.cozyting.app.ui.PrefsCastSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppUpdateManagerTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val sampleDecisionJson = """
        {
          "status": "update_available",
          "current_version": "0.9.4",
          "latest_tag": "v0.9.6",
          "latest_version": "0.9.6",
          "is_prerelease": false,
          "published_at": "2026-11-21T14:18:00Z",
          "release_notes": "v0.9.6\n- miName 解析\n\nv0.9.5\n- 常用音源管理",
          "changelog_entries": [
            {"tag":"v0.9.6","version":"0.9.6","prerelease":false,"published_at":"2026-11-21T14:18:00Z","notes":"- miName 解析"},
            {"tag":"v0.9.5","version":"0.9.5","prerelease":false,"published_at":"2026-11-21T13:08:00Z","notes":"- 常用音源管理"}
          ],
          "versions_behind": 2,
          "changelog_truncated": false,
          "release_url": "https://github.com/JasirVoriya/cozyting-releases/releases/tag/v0.9.6",
          "asset_name": "cozyting-v0.9.6-arm64-v8a.apk",
          "download_url": "https://github.com/JasirVoriya/cozyting-releases/releases/download/v0.9.6/cozyting-v0.9.6-arm64-v8a.apk",
          "asset_size_bytes": 6710886,
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "sha256sums_url": "",
          "installable": true,
          "error": ""
        }
    """.trimIndent()

    @Before
    fun setUp() {
        AppUpdateManager.resetForTest()
        val settings = PrefsCastSettingsStore(ctx)
        settings.autoCheckUpdates = true
        settings.updateChannelRaw = "stable"
        settings.ignoredUpdateTag = ""
        settings.lastUpdateCheckMs = 0L
        settings.updateEtag = ""
        settings.cachedUpdateManifestJson = ""
    }

    @After
    fun tearDown() {
        AppUpdateManager.resetForTest()
    }

    @Test
    fun primaryEtag304ReusesCacheAndFallsBackToReleasesApiWhenPrimaryFails() {
        val calls = mutableListOf<Pair<String, String?>>()
        AppUpdateManager.currentVersionProvider = { "0.9.4-release" }
        AppUpdateManager.isAcceptanceProvider = { false }
        AppUpdateManager.clockMs = { 1_000_000L }
        AppUpdateManager.evaluator = AppUpdateManager.Evaluator { _, _, _, _, _, ignored, _ ->
            if (ignored == "v0.9.6") {
                sampleDecisionJson.replace("\"update_available\"", "\"ignored\"")
            } else {
                sampleDecisionJson
            }
        }

        // 1) 主源 200 返回并保存 ETag + 缓存
        AppUpdateManager.transport = AppUpdateManager.Transport { url, etag ->
            calls += url to etag
            UpdateHttpClient.FetchResult.Ok("{\"schema_version\":1}", "\"etag-1\"")
        }
        val first = AppUpdateManager.checkBlocking(ctx, manual = false)
        assertTrue(first is UpdateCheckState.Ready)
        assertEquals(1, calls.size)
        assertEquals(UpdateConfig.MANIFEST_PRIMARY_URL, calls[0].first)
        assertNull(calls[0].second)
        val settings = PrefsCastSettingsStore(ctx)
        assertEquals("\"etag-1\"", settings.updateEtag)
        assertEquals("{\"schema_version\":1}", settings.cachedUpdateManifestJson)

        // 2) 第二次带 If-None-Match 收到 304，直接复用缓存
        AppUpdateManager.transport = AppUpdateManager.Transport { url, etag ->
            calls += url to etag
            UpdateHttpClient.FetchResult.NotModified
        }
        val second = AppUpdateManager.checkBlocking(ctx, manual = false)
        assertTrue(second is UpdateCheckState.Ready && second.fromCache)
        assertEquals("\"etag-1\"", calls[1].second)

        // 3) 主源失败时自动回退公开仓 Releases API
        AppUpdateManager.transport = AppUpdateManager.Transport { url, etag ->
            calls += url to etag
            if (url == UpdateConfig.MANIFEST_PRIMARY_URL) {
                UpdateHttpClient.FetchResult.HttpError(503, "HTTP 503")
            } else {
                UpdateHttpClient.FetchResult.Ok("[{\"tag_name\":\"v0.9.6\"}]", null)
            }
        }
        val third = AppUpdateManager.checkBlocking(ctx, manual = true)
        assertTrue(third is UpdateCheckState.Ready)
        assertEquals(UpdateConfig.RELEASES_FALLBACK_API_URL, calls.last().first)
    }

    @Test
    fun bannerHiddenWhileCastingOrAcceptanceOrDismissedOrIgnored() {
        AppUpdateManager.currentVersionProvider = { "0.9.4-release" }
        AppUpdateManager.isAcceptanceProvider = { false }
        AppUpdateManager.evaluator = AppUpdateManager.Evaluator { _, _, _, _, _, ignored, _ ->
            if (ignored == "v0.9.6") {
                sampleDecisionJson.replace("\"update_available\"", "\"ignored\"")
            } else {
                sampleDecisionJson
            }
        }
        AppUpdateManager.transport = AppUpdateManager.Transport { _, _ ->
            UpdateHttpClient.FetchResult.Ok("{\"schema_version\":1}", null)
        }

        // 先加载就绪状态
        AppUpdateManager.checkBlocking(ctx, manual = false)
        AppUpdateManager.reevaluateFromCache(ctx, manual = false)
        assertTrue(AppUpdateManager.shouldShowUpdateBadge())
        assertNotNull(AppUpdateManager.activeBannerDecision(isCastingNow = false))
        // 投送中绝不显示更新横幅
        assertNull(AppUpdateManager.activeBannerDecision(isCastingNow = true))

        // 手动关闭横幅后本次会话隐藏，但设置红点仍保留
        AppUpdateManager.dismissBannerForTag("v0.9.6")
        assertNull(AppUpdateManager.activeBannerDecision(isCastingNow = false))
        assertTrue(AppUpdateManager.shouldShowUpdateBadge())

        // 跳过此版本后红点也消失
        AppUpdateManager.ignoreVersion(ctx, "v0.9.6")
        assertFalse(AppUpdateManager.shouldShowUpdateBadge())

        // 验收渠道权威隔离：FileProvider authority 包含包名
        assertEquals(
            "com.cozyting.app.acceptance.update.fileprovider",
            ApkInstallerCompat.fileProviderAuthority("com.cozyting.app.acceptance"),
        )
    }
}
