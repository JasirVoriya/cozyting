package com.miaocast.app.ui

import com.miaocast.app.R
import com.miaocast.app.capture.CaptureScopeMode
import com.miaocast.app.capture.shell.WirelessDebugOnboardingState
import com.miaocast.app.cast.CastNotificationFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLocaleAndI18nContractTest {

    @Test
    fun appLanguageRoundTripsAndWrapsContextForZhEnJa() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId(null))
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromId("zh-CN"))
        assertEquals(AppLanguage.EN, AppLanguage.fromId("en"))
        assertEquals(AppLanguage.JA, AppLanguage.fromId("ja"))

        AppLocaleManager.saveSelectedLanguage(app, AppLanguage.ZH_CN)
        val zhCtx = AppLocaleManager.wrapContext(app)
        assertEquals("妙投", zhCtx.getString(R.string.app_brand_name))
        assertEquals("设置", zhCtx.getString(R.string.title_settings))

        AppLocaleManager.saveSelectedLanguage(app, AppLanguage.EN)
        val enCtx = AppLocaleManager.wrapContext(app)
        assertEquals("MiaoCast", enCtx.getString(R.string.app_brand_name))
        assertEquals("Settings", enCtx.getString(R.string.title_settings))

        AppLocaleManager.saveSelectedLanguage(app, AppLanguage.JA)
        val jaCtx = AppLocaleManager.wrapContext(app)
        assertEquals("MiaoCast", jaCtx.getString(R.string.app_brand_name))
        assertEquals("設定", jaCtx.getString(R.string.title_settings))

        // 恢复默认避免影响后续单测
        AppLocaleManager.saveSelectedLanguage(app, AppLanguage.SYSTEM)
    }

    @Test
    fun notificationFormatterAndOnboardingStateLocalizeAcrossAllThreeLanguages() {
        val app = RuntimeEnvironment.getApplication()
        val langs = listOf(AppLanguage.ZH_CN, AppLanguage.EN, AppLanguage.JA)
        val titles = mutableSetOf<String>()
        val headlines = mutableSetOf<String>()
        for (lang in langs) {
            AppLocaleManager.saveSelectedLanguage(app, lang)
            val ctx = AppLocaleManager.localizedContext(app)
            val copy = CastNotificationFormatter.format(
                source = "app",
                scopeMode = CaptureScopeMode.SINGLE_APP,
                targetLabel = "Music",
                targetPackage = "com.example.music",
                paused = false,
                statsJson = """{"receivers":[{"ip":"192.0.2.10","ui_state":"streaming"},{"ip":"192.0.2.11","ui_state":"streaming"}]}""",
                deviceNamesByIp = mapOf("192.0.2.10" to "Speaker A", "192.0.2.11" to "Speaker B"),
                strings = CastNotificationFormatter.Strings.fromContext(ctx),
            )
            assertTrue(copy.title.contains("Music"))
            titles += copy.title

            val onboard = WirelessDebugOnboardingState.evaluate(
                sdkInt = 34,
                notificationsGranted = true,
                developerOptionsEnabled = true,
                paired = false,
                connectionVerified = false,
                strings = WirelessDebugOnboardingState.Strings.fromContext(ctx),
            )
            assertEquals(5, onboard.steps.size)
            headlines += onboard.headlineZh
        }
        assertEquals("Expected distinct localized titles for zh/en/ja", 3, titles.size)
        assertEquals("Expected distinct localized onboarding headlines for zh/en/ja", 3, headlines.size)
        assertNotEquals(AppLanguage.ZH_CN.nativeLabel, AppLanguage.JA.nativeLabel)
        AppLocaleManager.saveSelectedLanguage(app, AppLanguage.SYSTEM)
    }
}
