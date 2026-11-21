package com.miplay.sender.ui

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
import com.miplay.sender.AppPickerActivity
import com.miplay.sender.CastService
import com.miplay.sender.MainActivity
import com.miplay.sender.SettingsActivity
import com.miplay.sender.capture.FavoriteAudioSource
import com.miplay.sender.capture.LaunchableApp
import com.miplay.sender.capture.shell.WirelessDebugGuideActivity
import com.miplay.sender.capture.shell.WirelessDebugOnboardingState
import com.miplay.sender.cast.CastProtocol
import com.miplay.sender.cast.CastRouteSelection
import com.miplay.sender.cast.CastRouteSelection.Kind
import com.miplay.sender.cast.NowPlayingInfo
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
 * 运行后输出 PNG 到 `miplay.uiPreviewDir`（默认 `build/ui-previews/`）：
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

    @Before
    fun setUp() {
        val dirPath = System.getProperty("miplay.uiPreviewDir")
            ?.takeIf { it.isNotBlank() }
            ?: "build/ui-previews"
        outDir = File(dirPath).apply { mkdirs() }
        UiIconCache.clearForTest()
        CastService.running.set(false)
        CastService.lastFailMessage = ""
        CastService.lastStats = ""
        seedMockAppIcons()
    }

    @After
    fun tearDown() {
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

        println("[UiPreviewRenderTest] Saved 12 preview PNGs to: ${outDir.absolutePath}")
    }

    private fun renderMainCasting(isDark: Boolean): Bitmap {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()

        val devices = listOf(
            CastRouteSelection.Device(
                id = "this",
                name = "本机播放",
                kind = Kind.THIS_DEVICE,
                castable = true,
                selected = false,
            ),
            CastRouteSelection.Device(
                id = "192.0.2.10",
                name = "客厅小爱音箱-0001",
                kind = Kind.SPEAKER,
                selected = true,
                castable = true,
                protocol = CastProtocol.MIPLAY,
            ),
            CastRouteSelection.Device(
                id = "192.0.2.11",
                name = "卧室音箱 Pro-0002",
                kind = Kind.SPEAKER,
                selected = true,
                castable = true,
                protocol = CastProtocol.MIPLAY,
            ),
            CastRouteSelection.Device(
                id = "192.0.2.12",
                name = "客厅电视-0003",
                kind = Kind.TV,
                selected = false,
                castable = true,
                protocol = CastProtocol.MIPLAY,
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
                            .put("display_latency_ms", 28),
                    )
                    put(
                        JSONObject()
                            .put("ip", "192.0.2.11")
                            .put("ui_state", "streaming")
                            .put("display_latency_ms", 34),
                    )
                },
            )
        }

        val mockApps = listOf(
            LaunchableApp("com.example.music", "云音乐", hasMainLauncher = true),
            LaunchableApp("com.example.podcast", "播客电台", hasMainLauncher = true),
            LaunchableApp("com.example.hifi", "无损播放器", hasMainLauncher = true),
        )

        val nowPlaying = NowPlayingInfo(
            title = "夜航星（Night Voyager）",
            artist = "星海乐团 · 局域网无损音轨",
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
            previewSelectedLabel = "云音乐",
            previewAutoDetected = true,
            previewMasterVolume = 58,
            previewSpeakerVolumes = mapOf(
                "192.0.2.10" to 62,
                "192.0.2.11" to 48,
            ),
            previewCasting = true,
            previewStats = stats,
            previewStatusText = "多音箱同步投送中 · 48kHz AAC · 本机已静音",
        )

        val bitmap = captureActivityBitmap(activity)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return bitmap
    }

    private fun renderMainNoMediaDark(): Bitmap {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()

        val devices = listOf(
            CastRouteSelection.Device(
                id = "this",
                name = "本机播放",
                kind = Kind.THIS_DEVICE,
                castable = true,
                selected = true,
            ),
            CastRouteSelection.Device(
                id = "192.0.2.10",
                name = "客厅小爱音箱-0001",
                kind = Kind.SPEAKER,
                selected = false,
                castable = true,
                protocol = CastProtocol.MIPLAY,
            ),
        )

        activity.applyUiPreviewStateForTest(
            isDark = true,
            previewNowPlaying = NowPlayingInfo.unavailable("开启「通知使用权」以显示正在播放并启用播控"),
            previewDevices = devices,
            previewLaunchableApps = listOf(
                LaunchableApp("com.example.music", "云音乐", hasMainLauncher = true),
                LaunchableApp("com.example.podcast", "播客电台", hasMainLauncher = true),
            ),
            previewSelectedPackage = "",
            previewSelectedLabel = "",
            previewMasterVolume = 40,
            previewCasting = false,
            previewStats = null,
            previewStatusText = "点击任意音箱开始局域网直连投送",
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

    private fun renderAppPicker(isDark: Boolean): Bitmap {
        val controller = Robolectric.buildActivity(
            AppPickerActivity::class.java,
            Intent().putExtra("ui_preview", true),
        ).setup()
        val activity = controller.get()
        val apps = listOf(
            LaunchableApp("com.example.music", "云音乐", hasMainLauncher = true),
            LaunchableApp("com.example.podcast", "播客电台", hasMainLauncher = true),
            LaunchableApp("com.example.hifi", "无损播放器", hasMainLauncher = true),
            LaunchableApp("com.example.video", "视频客户端", hasMainLauncher = true),
            LaunchableApp("com.example.reader", "听书阅读", hasMainLauncher = true),
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
            paired = false,
            connectionVerified = false,
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
