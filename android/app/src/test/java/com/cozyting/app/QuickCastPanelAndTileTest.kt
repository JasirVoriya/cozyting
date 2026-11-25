package com.cozyting.app

import com.cozyting.app.compat.QuickSettingsTileCompat
import com.cozyting.app.ui.AppLanguage
import com.cozyting.app.ui.AppLocaleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickCastPanelAndTileTest {

    @Test
    fun quickSettingsTileCompat_gatesRequestAddTileToApi33Plus() {
        assertFalse(QuickSettingsTileCompat.canRequestAddTile(29))
        assertFalse(QuickSettingsTileCompat.canRequestAddTile(32))
        assertTrue(QuickSettingsTileCompat.canRequestAddTile(33))
        assertTrue(QuickSettingsTileCompat.canRequestAddTile(36))
    }

    @Test
    fun quickSettingsTileCompat_gatesWindowBlurToApi31Plus() {
        assertFalse(QuickSettingsTileCompat.supportsWindowBlurBehind(29))
        assertFalse(QuickSettingsTileCompat.supportsWindowBlurBehind(30))
        assertTrue(QuickSettingsTileCompat.supportsWindowBlurBehind(31))
        assertTrue(QuickSettingsTileCompat.supportsWindowBlurBehind(36))
    }

    @Test
    fun quickTileAndPanelStringsLocalizedInZhEnJa() {
        val app = RuntimeEnvironment.getApplication()
        val labels = mutableSetOf<String>()
        val actions = mutableSetOf<String>()
        val settingsTitles = mutableSetOf<String>()

        for (lang in listOf(AppLanguage.ZH_CN, AppLanguage.EN, AppLanguage.JA)) {
            AppLocaleManager.saveSelectedLanguage(app, lang)
            val ctx = AppLocaleManager.localizedContext(app)
            assertTrue(ctx.getString(R.string.qs_tile_label).isNotBlank())
            assertTrue(ctx.getString(R.string.qs_tile_sub_idle).isNotBlank())
            assertTrue(ctx.getString(R.string.qs_tile_sub_casting_fmt, 2, 40).contains("40%"))
            assertTrue(ctx.getString(R.string.qs_tile_sub_paused_fmt, 2).contains("2"))
            assertTrue(ctx.getString(R.string.quick_panel_open_app).isNotBlank())
            assertTrue(ctx.getString(R.string.quick_panel_done).isNotBlank())
            assertTrue(ctx.getString(R.string.settings_qs_tile_guide_msg).isNotBlank())

            labels += ctx.getString(R.string.qs_tile_label)
            actions += ctx.getString(R.string.cast_action_devices_volume)
            settingsTitles += ctx.getString(R.string.settings_qs_tile_title)
        }
        assertEquals(3, actions.size)
        assertEquals(3, settingsTitles.size)
        assertTrue(labels.contains("窝爱听"))
        assertTrue(labels.contains("CozyTing"))
        AppLocaleManager.saveSelectedLanguage(app, AppLanguage.SYSTEM)
    }

    @Test
    fun quickCastPanelActivity_rendersWithoutCrashInPreviewMode() {
        val intent = QuickCastPanelActivity.createLaunchIntent(RuntimeEnvironment.getApplication())
            .putExtra("ui_preview", true)
        val controller = Robolectric.buildActivity(QuickCastPanelActivity::class.java, intent)
            .setup()
        val activity = controller.get()
        assertNotNull(activity.window.decorView)
        val openAppMatches = ArrayList<android.view.View>()
        activity.window.decorView.findViewsWithText(
            openAppMatches,
            activity.getString(R.string.quick_panel_open_app),
            android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION,
        )
        assertEquals(1, openAppMatches.size)
        controller.pause().stop().destroy()
    }

    @Test
    fun settingsActivity_rendersQuickTileEntryWithoutCrash() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity.window.decorView)
        controller.pause().stop().destroy()
    }
}
