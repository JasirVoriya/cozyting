package com.miaocast.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import com.miaocast.app.AppPickerActivity
import com.miaocast.app.CastService
import com.miaocast.app.MainActivity
import com.miaocast.app.SettingsActivity
import com.miaocast.app.capture.FavoriteAudioSource
import com.miaocast.app.capture.LaunchableApp
import com.miaocast.app.capture.shell.PairingFloatingOverlay
import com.miaocast.app.capture.shell.WirelessDebugGuideActivity
import com.miaocast.app.capture.shell.WirelessDebugOnboardingState
import com.miaocast.app.cast.CastProtocol
import com.miaocast.app.cast.CastRouteSelection
import com.miaocast.app.cast.CastRouteSelection.Kind
import com.miaocast.app.cast.NowPlayingInfo
import com.miaocast.app.update.AppUpdateManager
import com.miaocast.app.update.UpdateCheckState
import com.miaocast.app.update.UpdateDialogController
import com.miaocast.app.update.UpdateHttpClient
import android.app.Dialog
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * JVM 本地 UI 截图预览（基于 Robolectric Native Graphics / Skia，无需 APK 或真机）。
 *
 * 运行后输出 PNG 到 `miaocast.uiPreviewDir`（默认 `build/ui-previews/`）：
 * - `00_overview_dark_vs_light.png`（主界面深/浅并排对比）
 * - `01_main_dark_casting.png`（深色 · 正在播放 + 多音箱投送）
 * - `02_main_light_casting.png`（浅色 · 正在播放 + 多音箱投送）
 * - `03_main_dark_no_media.png`（深色 · 无媒体会话折叠态 + 本机播放）
 * - `04_settings_dark.png`（独立设置页 · 深色）
 * - `05_settings_light.png`（独立设置页 · 浅色）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class UiPreviewRenderTest {

    private lateinit var outDir: File
    private lateinit var previewLang: AppLanguage

    private data class PreviewFixtures(
        val thisDeviceName: String,
        val speaker2Name: String,
        val speaker2Room: String,
        val speaker1Room: String,
        val tvRoom: String,
        val noMediaSpeakerName: String,
        val appMusic: String,
        val appPodcast: String,
        val appHifi: String,
        val appVideo: String,
        val appReader: String,
        val trackTitle: String,
        val trackArtist: String,
        val castingStatus: String,
        val noMediaReason: String,
        val noMediaStatus: String,
        val bannerStatus: String,
        val updateNotesV97: String,
        val updateNotesV96: String,
        val wirelessReason: String,
    )

    private fun fixturesFor(lang: AppLanguage): PreviewFixtures = when (lang) {
        AppLanguage.EN -> PreviewFixtures(
            thisDeviceName = "This device",
            speaker2Name = "XiaoAi Speaker Pro-0002",
            speaker2Room = "Living room",
            speaker1Room = "Study",
            tvRoom = "Living room",
            noMediaSpeakerName = "Living Room Speaker-0001",
            appMusic = "Cloud Music",
            appPodcast = "Podcast FM",
            appHifi = "Hi-Res Player",
            appVideo = "Video Player",
            appReader = "Audiobooks",
            trackTitle = "Night Voyager",
            trackArtist = "Starlight Ensemble · Lossless LAN",
            castingStatus = "Multi-room synced · 48kHz AAC · Phone muted",
            noMediaReason = "Enable Notification Access to display Now Playing and controls",
            noMediaStatus = "Tap any speaker to start direct LAN casting",
            bannerStatus = "Playing on phone · Tap a speaker below to cast",
            updateNotesV97 = "- MiaoCast rebrand & Wireless Debugging silent capture",
            updateNotesV96 = "- Local LAN read-only miName & room name parsing",
            wirelessReason = "Built-in Wireless Debugging is not paired yet. Follow the 5-step guide to enable auto-pairing (notification fallback included).",
        )
        AppLanguage.JA -> PreviewFixtures(
            thisDeviceName = "本機再生",
            speaker2Name = "XiaoAi Speaker Pro-0002",
            speaker2Room = "リビング",
            speaker1Room = "書斎",
            tvRoom = "リビング",
            noMediaSpeakerName = "リビングスピーカー-0001",
            appMusic = "クラウド音楽",
            appPodcast = "ポッドキャスト",
            appHifi = "ハイレゾ再生",
            appVideo = "動画プレイヤー",
            appReader = "オーディオブック",
            trackTitle = "夜航星 (Night Voyager)",
            trackArtist = "星海アンサンブル · LAN ロスレス",
            castingStatus = "マルチ同期キャスト中 · 48kHz AAC · 端末ミュート",
            noMediaReason = "「通知へのアクセス」を許可して再生中の曲と操作を表示",
            noMediaStatus = "スピーカーをタップして LAN 直接キャストを開始",
            bannerStatus = "本機再生中 · 下のスピーカーをタップして開始",
            updateNotesV97 = "- MiaoCast リブランドとワイヤレスデバッグ収集に対応",
            updateNotesV96 = "- LAN 読み取り専用 miName・部屋名取得に対応",
            wirelessReason = "内蔵ワイヤレスデバッグの初回ペアリングが未完了です。5 ステップガイドで自動ペアリングを行ってください。",
        )
        else -> PreviewFixtures(
            thisDeviceName = "本机播放",
            speaker2Name = "小爱音箱Pro-0002",
            speaker2Room = "客厅",
            speaker1Room = "书房",
            tvRoom = "Living room",
            noMediaSpeakerName = "客厅小爱音箱-0001",
            appMusic = "云音乐",
            appPodcast = "播客电台",
            appHifi = "无损播放器",
            appVideo = "视频客户端",
            appReader = "听书阅读",
            trackTitle = "夜航星（Night Voyager）",
            trackArtist = "星海乐团 · 局域网无损音轨",
            castingStatus = "多音箱同步投送中 · 48kHz AAC · 本机已静音",
            noMediaReason = "开启「通知使用权」以显示正在播放并启用播控",
            noMediaStatus = "点击任意音箱开始局域网直连投送",
            bannerStatus = "本机出声 · 点击下方音箱开始投送",
            updateNotesV97 = "- 妙投品牌升级与无线调试无感采集",
            updateNotesV96 = "- 局域网只读 miName 与房间名解析",
            wirelessReason = "内置无线调试尚未完成首次配对，请按 5 步向导开启无障碍自动配对并打开无线调试（也支持通知栏长按输入兜底）",
        )
    }

    private fun resolvePreviewLanguage(): AppLanguage {
        val raw = System.getProperty("miaocast.uiPreviewLocale")?.trim()?.lowercase().orEmpty()
        return when (raw) {
            "en", "en-us", "en_us" -> AppLanguage.EN
            "ja", "ja-jp", "ja_jp" -> AppLanguage.JA
            else -> AppLanguage.ZH_CN
        }
    }

    @Before
    fun setUp() {
        val dirPath = System.getProperty("miaocast.uiPreviewDir")
            ?.takeIf { it.isNotBlank() }
            ?: "build/ui-previews"
        outDir = File(dirPath).apply { mkdirs() }
        previewLang = resolvePreviewLanguage()
        val app = RuntimeEnvironment.getApplication()
        AppLocaleManager.saveSelectedLanguage(app, previewLang)
        UiIconCache.clearForTest()
        CastService.running.set(false)
        CastService.lastFailMessage = ""
        CastService.lastStats = ""
        seedMockAppIcons()
    }

    @After
    fun tearDown() {
        AppUpdateManager.resetForTest()
        CastService.running.set(false)
        CastService.lastFailMessage = ""
        CastService.lastStats = ""
        UiIconCache.clearForTest()
    }

    @Test
    fun renderMainAndSettingsPreviews() {
        val darkMain = renderMainCasting(isDark = true)
        savePng(darkMain, "01_main_dark_casting.png")

        val lightMain = renderMainCasting(isDark = false)
        savePng(lightMain, "02_main_light_casting.png")

        val overview = composeSideBySide(darkMain, lightMain)
        savePng(overview, "00_overview_dark_vs_light.png")

        val noMedia = renderMainNoMediaDark()
        savePng(noMedia, "03_main_dark_no_media.png")

        val darkSettings = renderSettings(isDark = true)
        savePng(darkSettings, "04_settings_dark.png")

        val lightSettings = renderSettings(isDark = false)
        savePng(lightSettings, "05_settings_light.png")

        val darkPicker = renderAppPicker(isDark = true)
        savePng(darkPicker, "06_app_picker_dark.png")

        val lightPicker = renderAppPicker(isDark = false)
        savePng(lightPicker, "07_app_picker_light.png")

        val pickerOverview = composeSideBySide(darkPicker, lightPicker)
        savePng(pickerOverview, "08_app_picker_dark_vs_light.png")

        val darkGuide = renderWirelessGuide(isDark = true)
        savePng(darkGuide, "09_wireless_guide_dark.png")

        val lightGuide = renderWirelessGuide(isDark = false)
        savePng(lightGuide, "10_wireless_guide_light.png")

        val guideOverview = composeSideBySide(darkGuide, lightGuide)
        savePng(guideOverview, "11_wireless_guide_dark_vs_light.png")

        val updateSettingsOverview = composeSideBySide(
            renderSettingsUpdateBottom(isDark = true),
            renderSettingsUpdateBottom(isDark = false),
        )
        savePng(updateSettingsOverview, "12_settings_update_card_dark_vs_light.png")

        val updateBannerOverview = composeSideBySide(
            renderMainWithUpdateBanner(isDark = true),
            renderMainWithUpdateBanner(isDark = false),
        )
        savePng(updateBannerOverview, "13_main_update_banner_dark_vs_light.png")

        val settingsTopOverview = composeSideBySide(darkSettings, lightSettings)
        savePng(settingsTopOverview, "14_settings_top_dark_vs_light.png")

        val latencyDialogOverview = composeSideBySide(
            renderLatencyDetailsDialogOverlay(isDark = true),
            renderLatencyDetailsDialogOverlay(isDark = false),
        )
        savePng(latencyDialogOverview, "15_latency_dialog_dark_vs_light.png")

        val updateDialogOverview = composeSideBySide(
            renderUpdateDialogOverlay(isDark = true),
            renderUpdateDialogOverlay(isDark = false),
        )
        savePng(updateDialogOverview, "16_update_dialog_dark_vs_light.png")

        val wirelessPrecheckDialogOverview = composeSideBySide(
            renderWirelessPrecheckDialogOverlay(isDark = true),
            renderWirelessPrecheckDialogOverlay(isDark = false),
        )
        savePng(wirelessPrecheckDialogOverview, "17_wireless_precheck_dialog_dark_vs_light.png")

        val floatingKeypadOverview = composeSideBySide(
            renderWirelessGuideWithFloatingKeypad(isDark = true),
            renderWirelessGuideWithFloatingKeypad(isDark = false),
        )
        savePng(floatingKeypadOverview, "18_floating_pairing_keypad_dark_vs_light.png")

        println("[UiPreviewRenderTest] Saved 19 preview PNGs to: ${outDir.absolutePath}")
    }

    private fun renderMainCasting(isDark: Boolean): Bitmap {
        val fx = fixturesFor(previewLang)
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()

        val devices = listOf(
            CastRouteSelection.Device(
                id = "this",
                name = fx.thisDeviceName,
                kind = Kind.THIS_DEVICE,
                castable = true,
                selected = false,
            ),
            CastRouteSelection.Device(
                id = "192.0.2.10",
                name = "Sound Pro-0001",
                kind = Kind.SPEAKER,
                selected = true,
                castable = true,
                protocol = CastProtocol.MIPLAY,
                roomName = "",
            ),
            CastRouteSelection.Device(
                id = "192.0.2.11",
                name = fx.speaker2Name,
                kind = Kind.SPEAKER,
                selected = true,
                castable = true,
                protocol = CastProtocol.MIPLAY,
                roomName = fx.speaker2Room,
            ),
            CastRouteSelection.Device(
                id = "192.0.2.12",
                name = "REDMI G Pro 27U",
                kind = Kind.TV,
                selected = false,
                castable = true,
                protocol = CastProtocol.MIPLAY,
                roomName = fx.tvRoom,
            ),
        )

        val stats = JSONObject().apply {
            put(
                "receivers",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("ip", "192.0.2.10")
                            .put("ui_state", "streaming")
                            .put("display_latency_ms", 551),
                    )
                    put(
                        JSONObject()
                            .put("ip", "192.0.2.11")
                            .put("ui_state", "streaming")
                            .put("display_latency_ms", 551),
                    )
                },
            )
        }

        val mockApps = listOf(
            LaunchableApp("com.example.music", fx.appMusic, hasMainLauncher = true),
            LaunchableApp("com.example.podcast", fx.appPodcast, hasMainLauncher = true),
            LaunchableApp("com.example.hifi", fx.appHifi, hasMainLauncher = true),
        )

        val nowPlaying = NowPlayingInfo(
            title = fx.trackTitle,
            artist = fx.trackArtist,
            durationMs = 248_000L,
            positionMs = 94_000L,
            isPlaying = true,
            artwork = createMockAlbumArt(),
            sourcePackage = "com.example.music",
            available = true,
            placeholderReasonZh = "",
        )

        val mockFavorites = listOf(
            FavoriteAudioSource("com.example.music", autoDetectEnabled = true),
            FavoriteAudioSource("com.example.podcast", autoDetectEnabled = false),
            FavoriteAudioSource("com.example.hifi", autoDetectEnabled = true),
        )

        activity.applyUiPreviewStateForTest(
            isDark = isDark,
            previewNowPlaying = nowPlaying,
            previewDevices = devices,
            previewSelectedSpeakerIds = setOf("192.0.2.10", "192.0.2.11"),
            previewLaunchableApps = mockApps,
            previewFavoriteSources = mockFavorites,
            previewSelectedPackage = "com.example.music",
            previewSelectedLabel = fx.appMusic,
            previewAutoDetected = true,
            previewMasterVolume = 58,
            previewSpeakerVolumes = mapOf(
                "192.0.2.10" to 62,
                "192.0.2.11" to 48,
            ),
            previewCasting = true,
            previewStats = stats,
            previewStatusText = fx.castingStatus,
        )

        val bitmap = captureActivityBitmap(activity)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderMainNoMediaDark(): Bitmap {
        val fx = fixturesFor(previewLang)
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()

        val devices = listOf(
            CastRouteSelection.Device(
                id = "this",
                name = fx.thisDeviceName,
                kind = Kind.THIS_DEVICE,
                castable = true,
                selected = true,
            ),
            CastRouteSelection.Device(
                id = "192.0.2.10",
                name = fx.noMediaSpeakerName,
                kind = Kind.SPEAKER,
                selected = false,
                castable = true,
                protocol = CastProtocol.MIPLAY,
            ),
        )

        activity.applyUiPreviewStateForTest(
            isDark = true,
            previewNowPlaying = NowPlayingInfo.unavailable(fx.noMediaReason),
            previewDevices = devices,
            previewLaunchableApps = listOf(
                LaunchableApp("com.example.music", fx.appMusic, hasMainLauncher = true),
                LaunchableApp("com.example.podcast", fx.appPodcast, hasMainLauncher = true),
            ),
            previewSelectedPackage = "",
            previewSelectedLabel = "",
            previewMasterVolume = 40,
            previewCasting = false,
            previewStats = null,
            previewStatusText = fx.noMediaStatus,
        )

        val bitmap = captureActivityBitmap(activity)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderSettings(isDark: Boolean): Bitmap {
        val ctx = RuntimeEnvironment.getApplication()
        PrefsCastSettingsStore(ctx).themeMode = if (isDark) ThemeMode.DARK else ThemeMode.LIGHT

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val bitmap = captureActivityBitmap(activity)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun seedMockUpdateAvailable() {
        val fx = fixturesFor(previewLang)
        val ctx = RuntimeEnvironment.getApplication()
        AppUpdateManager.resetForTest()
        AppUpdateManager.currentVersionProvider = { "0.9.5-release" }
        AppUpdateManager.isAcceptanceProvider = { false }
        AppUpdateManager.evaluator = AppUpdateManager.Evaluator { _, _, _, _, _, _, _ ->
            """
            {
              "status": "update_available",
              "current_version": "0.9.5",
              "latest_tag": "v0.9.7",
              "latest_version": "0.9.7",
              "is_prerelease": false,
              "published_at": "2026-11-22T08:00:00Z",
              "release_notes": "v0.9.7\n${fx.updateNotesV97}\n\nv0.9.6\n${fx.updateNotesV96}",
              "changelog_entries": [
                {"tag":"v0.9.7","version":"0.9.7","prerelease":false,"published_at":"2026-11-22T08:00:00Z","notes":"${fx.updateNotesV97}"},
                {"tag":"v0.9.6","version":"0.9.6","prerelease":false,"published_at":"2026-11-21T14:00:00Z","notes":"${fx.updateNotesV96}"}
              ],
              "versions_behind": 2,
              "changelog_truncated": false,
              "release_url": "https://github.com/JasirVoriya/miaocast-releases/releases/tag/v0.9.7",
              "asset_name": "miaocast-v0.9.7-arm64-v8a.apk",
              "download_url": "https://github.com/JasirVoriya/miaocast-releases/releases/download/v0.9.7/miaocast-v0.9.7-arm64-v8a.apk",
              "asset_size_bytes": 6890123,
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "sha256sums_url": "",
              "installable": true,
              "error": ""
            }
            """.trimIndent()
        }
        AppUpdateManager.transport = AppUpdateManager.Transport { _, _ ->
            UpdateHttpClient.FetchResult.Ok("{\"schema_version\":1}", null)
        }
        AppUpdateManager.checkBlocking(ctx, manual = false)
        AppUpdateManager.reevaluateFromCache(ctx, manual = false)
    }

    private fun renderSettingsUpdateBottom(isDark: Boolean): Bitmap {
        val ctx = RuntimeEnvironment.getApplication()
        PrefsCastSettingsStore(ctx).themeMode = if (isDark) ThemeMode.DARK else ThemeMode.LIGHT
        seedMockUpdateAvailable()

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val contentRoot = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val scroll = contentRoot.getChildAt(0) as? ScrollView
        // 先测量布局再滚至底部展示卡片 4「关于与更新」
        val dm = activity.resources.displayMetrics
        val wSpec = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        activity.window.decorView.measure(wSpec, hSpec)
        activity.window.decorView.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val childHeight = scroll?.getChildAt(0)?.height ?: 0
        scroll?.scrollTo(0, (childHeight - dm.heightPixels).coerceAtLeast(0))
        val bitmap = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        activity.window.decorView.draw(Canvas(bitmap))

        controller.pause().stop().destroy()
        AppUpdateManager.resetForTest()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderMainWithUpdateBanner(isDark: Boolean): Bitmap {
        val fx = fixturesFor(previewLang)
        seedMockUpdateAvailable()
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()
        val devices = listOf(
            CastRouteSelection.Device("this", fx.thisDeviceName, Kind.THIS_DEVICE, true, selected = true),
            CastRouteSelection.Device("192.0.2.10", fx.speaker2Name, Kind.SPEAKER, castable = true, selected = false, protocol = CastProtocol.MIPLAY, roomName = fx.speaker2Room),
        )
        activity.applyUiPreviewStateForTest(
            isDark = isDark,
            previewNowPlaying = NowPlayingInfo.unavailable(fx.noMediaReason),
            previewDevices = devices,
            previewLaunchableApps = listOf(LaunchableApp("com.example.music", fx.appMusic, hasMainLauncher = true)),
            previewSelectedPackage = "com.example.music",
            previewSelectedLabel = fx.appMusic,
            previewMasterVolume = 40,
            previewCasting = false,
            previewStats = null,
            previewStatusText = fx.bannerStatus,
        )
        val bitmap = captureActivityBitmap(activity)
        controller.pause().stop().destroy()
        AppUpdateManager.resetForTest()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderAppPicker(isDark: Boolean): Bitmap {
        val fx = fixturesFor(previewLang)
        val controller = Robolectric.buildActivity(
            AppPickerActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()
        val apps = listOf(
            LaunchableApp("com.example.music", fx.appMusic, hasMainLauncher = true),
            LaunchableApp("com.example.podcast", fx.appPodcast, hasMainLauncher = true),
            LaunchableApp("com.example.hifi", fx.appHifi, hasMainLauncher = true),
            LaunchableApp("com.example.video", fx.appVideo, hasMainLauncher = true),
            LaunchableApp("com.example.reader", fx.appReader, hasMainLauncher = true),
        )
        val favorites = listOf(
            FavoriteAudioSource("com.example.music", autoDetectEnabled = true),
            FavoriteAudioSource("com.example.podcast", autoDetectEnabled = false),
            FavoriteAudioSource("com.example.hifi", autoDetectEnabled = true),
        )
        activity.applyUiPreviewStateForTest(
            isDark = isDark,
            previewApps = apps,
            previewFavorites = favorites,
            previewSelectedPackage = "com.example.music",
            previewExpandOthers = true,
        )
        val bitmap = captureActivityBitmap(activity)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderWirelessGuide(isDark: Boolean): Bitmap {
        val controller = Robolectric.buildActivity(WirelessDebugGuideActivity::class.java).setup()
        val activity = controller.get()
        val state = WirelessDebugOnboardingState.evaluate(
            sdkInt = 34,
            notificationsGranted = true,
            developerOptionsEnabled = true,
            accessibilityEnabled = false,
            paired = false,
            connectionVerified = false,
            strings = WirelessDebugOnboardingState.Strings.fromContext(activity),
        )
        activity.applyUiPreviewStateForTest(
            isDark = isDark,
            state = state,
            expandTips = false,
        )
        val bitmap = captureActivityBitmap(activity)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderWirelessPrecheckDialogOverlay(isDark: Boolean): Bitmap {
        val fx = fixturesFor(previewLang)
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()
        seedCastingStateOnMain(activity, isDark = isDark)
        val dialog = activity.openWirelessPrecheckDialogForTest(fx.wirelessReason)
        val bitmap = captureActivityWithDialogOverlay(activity, dialog, maxWidthFraction = 0.94f)
        runCatching { dialog.dismiss() }
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderWirelessGuideWithFloatingKeypad(isDark: Boolean): Bitmap {
        val base = renderWirelessGuide(isDark = isDark)
        val ctx = AppLocaleManager.localizedContext(RuntimeEnvironment.getApplication())
        val overlayCard = PairingFloatingOverlay.buildOverlayCardForTest(
            context = ctx,
            isDark = isDark,
            expanded = true,
            detectedPort = 39481,
            pinText = "482",
        )
        val canvas = Canvas(base)
        canvas.drawColor(Color.argb(110, 4, 7, 12))
        val maxW = (base.width * 0.94f).toInt()
        val maxH = (base.height * 0.60f).toInt()
        overlayCard.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST),
        )
        val ow = overlayCard.measuredWidth
        val oh = overlayCard.measuredHeight
        overlayCard.layout(0, 0, ow, oh)
        val dx = (base.width - ow) / 2f
        val dy = UiComponents.dp(ctx, 42).toFloat()
        canvas.save()
        canvas.translate(dx, dy)
        overlayCard.draw(canvas)
        canvas.restore()
        return base
    }

    private fun captureActivityBitmap(activity: Activity): Bitmap {
        val root = activity.findViewById<View>(android.R.id.content)
        val dm = activity.resources.displayMetrics
        val widthPx = dm.widthPixels.coerceAtLeast(1080)
        val heightPx = dm.heightPixels.coerceAtLeast(2200)
        val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        root.measure(wSpec, hSpec)
        root.layout(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        return bitmap
    }

    private fun captureActivityWithDialogOverlay(
        activity: Activity,
        dialog: Dialog,
        maxWidthFraction: Float = 0.92f,
    ): Bitmap {
        val base = captureActivityBitmap(activity)
        val canvas = Canvas(base)
        canvas.drawColor(Color.argb(145, 4, 7, 12))
        val dialogRoot = dialog.window?.findViewById<View>(android.R.id.content) ?: return base
        val dialogMaxW = (base.width * maxWidthFraction).toInt()
        val dialogMaxH = (base.height * 0.86f).toInt()
        dialogRoot.measure(
            View.MeasureSpec.makeMeasureSpec(dialogMaxW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dialogMaxH, View.MeasureSpec.AT_MOST),
        )
        val dw = dialogRoot.measuredWidth
        val dh = dialogRoot.measuredHeight
        dialogRoot.layout(0, 0, dw, dh)
        val dx = (base.width - dw) / 2f
        val dy = (base.height - dh) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        dialogRoot.draw(canvas)
        canvas.restore()
        return base
    }

    private fun renderLatencyDetailsDialogOverlay(isDark: Boolean): Bitmap {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()
        seedCastingStateOnMain(activity, isDark = isDark)
        val dialog = activity.openLatencyDetailsDialogForTest()
        val bitmap = captureActivityWithDialogOverlay(activity, dialog, maxWidthFraction = 0.90f)
        runCatching { dialog.dismiss() }
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderUpdateDialogOverlay(isDark: Boolean): Bitmap {
        seedMockUpdateAvailable()
        val decision = (AppUpdateManager.state as UpdateCheckState.Ready).decision
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()
        seedCastingStateOnMain(activity, isDark = isDark)
        val dialog = UpdateDialogController.show(activity, UiPalette.forMode(isDark), decision)
        val bitmap = captureActivityWithDialogOverlay(activity, dialog, maxWidthFraction = 0.96f)
        runCatching { dialog.dismiss() }
        controller.pause().stop().destroy()
        AppUpdateManager.resetForTest()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun seedCastingStateOnMain(activity: MainActivity, isDark: Boolean) {
        val fx = fixturesFor(previewLang)
        val devices = listOf(
            CastRouteSelection.Device("this", fx.thisDeviceName, Kind.THIS_DEVICE, true, selected = false),
            CastRouteSelection.Device("192.0.2.10", "Sound Pro-0001", Kind.SPEAKER, selected = true, castable = true, protocol = CastProtocol.MIPLAY, roomName = fx.speaker1Room),
            CastRouteSelection.Device("192.0.2.11", fx.speaker2Name, Kind.SPEAKER, selected = true, castable = true, protocol = CastProtocol.MIPLAY, roomName = fx.speaker2Room),
            CastRouteSelection.Device("192.0.2.12", "REDMI G Pro 27U", Kind.TV, selected = false, castable = true, protocol = CastProtocol.MIPLAY, roomName = fx.tvRoom),
        )
        val stats = JSONObject().apply {
            put(
                "receivers",
                JSONArray().apply {
                    put(JSONObject().put("ip", "192.0.2.10").put("ui_state", "streaming").put("display_latency_ms", 551))
                    put(JSONObject().put("ip", "192.0.2.11").put("ui_state", "streaming").put("display_latency_ms", 551))
                },
            )
        }
        val mockApps = listOf(
            LaunchableApp("com.example.music", fx.appMusic, hasMainLauncher = true),
            LaunchableApp("com.example.podcast", fx.appPodcast, hasMainLauncher = true),
            LaunchableApp("com.example.hifi", fx.appHifi, hasMainLauncher = true),
        )
        val nowPlaying = NowPlayingInfo(
            title = fx.trackTitle,
            artist = fx.trackArtist,
            durationMs = 248_000L,
            positionMs = 94_000L,
            isPlaying = true,
            artwork = createMockAlbumArt(),
            sourcePackage = "com.example.music",
            available = true,
            placeholderReasonZh = "",
        )
        activity.applyUiPreviewStateForTest(
            isDark = isDark,
            previewNowPlaying = nowPlaying,
            previewDevices = devices,
            previewSelectedSpeakerIds = setOf("192.0.2.10", "192.0.2.11"),
            previewLaunchableApps = mockApps,
            previewFavoriteSources = listOf(
                FavoriteAudioSource("com.example.music", autoDetectEnabled = true),
                FavoriteAudioSource("com.example.podcast", autoDetectEnabled = false),
                FavoriteAudioSource("com.example.hifi", autoDetectEnabled = true),
            ),
            previewSelectedPackage = "com.example.music",
            previewSelectedLabel = fx.appMusic,
            previewAutoDetected = true,
            previewMasterVolume = 58,
            previewSpeakerVolumes = mapOf("192.0.2.10" to 62, "192.0.2.11" to 48),
            previewCasting = true,
            previewStats = stats,
            previewStatusText = fx.castingStatus,
        )
    }

    private fun seedMockAppIcons() {
        val res = RuntimeEnvironment.getApplication().resources
        UiIconCache.putForTest(
            "com.example.music",
            BitmapDrawable(res, createMockAppIcon(Color.parseColor("#E11D48"), Color.parseColor("#FB7185"))),
        )
        UiIconCache.putForTest(
            "com.example.podcast",
            BitmapDrawable(res, createMockAppIcon(Color.parseColor("#7C3AED"), Color.parseColor("#A78BFA"))),
        )
        UiIconCache.putForTest(
            "com.example.hifi",
            BitmapDrawable(res, createMockAppIcon(Color.parseColor("#0284C7"), Color.parseColor("#38BDF8"))),
        )
        UiIconCache.putForTest(
            "com.example.video",
            BitmapDrawable(res, createMockAppIcon(Color.parseColor("#EA580C"), Color.parseColor("#FB923C"))),
        )
        UiIconCache.putForTest(
            "com.example.reader",
            BitmapDrawable(res, createMockAppIcon(Color.parseColor("#059669"), Color.parseColor("#34D399"))),
        )
    }

    private fun createMockAppIcon(c1: Int, c2: Int): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        // 简约双音符几何剪影
        c.drawCircle(size * 0.38f, size * 0.65f, size * 0.11f, fg)
        c.drawCircle(size * 0.64f, size * 0.58f, size * 0.11f, fg)
        c.drawRect(size * 0.44f, size * 0.30f, size * 0.50f, size * 0.65f, fg)
        c.drawRect(size * 0.70f, size * 0.24f, size * 0.76f, size * 0.58f, fg)
        c.drawRect(size * 0.44f, size * 0.24f, size * 0.76f, size * 0.33f, fg)
        return bmp
    }

    private fun createMockAlbumArt(): Bitmap {
        val size = 360
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                size.toFloat(),
                size.toFloat(),
                intArrayOf(
                    Color.parseColor("#1E3A8A"),
                    Color.parseColor("#2563EB"),
                    Color.parseColor("#0EA5E9"),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        c.drawCircle(size * 0.5f, size * 0.5f, size * 0.32f, ring)
        c.drawCircle(size * 0.5f, size * 0.5f, size * 0.18f, ring)

        val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 254, 240, 138)
        }
        c.drawCircle(size * 0.5f, size * 0.5f, size * 0.08f, sun)

        val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 255, 255, 255)
        }
        c.drawRoundRect(RectF(36f, size * 0.74f, size - 36f, size * 0.80f), 12f, 12f, wave)
        return bmp
    }

    private fun composeSideBySide(left: Bitmap, right: Bitmap): Bitmap {
        val pad = 48
        val gap = 40
        val w = pad * 2 + left.width + gap + right.width
        val h = pad * 2 + maxOf(left.height, right.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.parseColor("#1E2430"))
        c.drawBitmap(left, pad.toFloat(), pad.toFloat(), null)
        c.drawBitmap(right, (pad + left.width + gap).toFloat(), pad.toFloat(), null)
        return out
    }

    private fun savePng(bitmap: Bitmap, name: String) {
        val file = File(outDir, name)
        FileOutputStream(file).use { out ->
            assertTrue("PNG compress failed for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        assertTrue("Generated PNG missing or empty: ${file.absolutePath}", file.exists() && file.length() > 1024)
    }
}
