package com.cozyting.app.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cozyting.app.R
import com.cozyting.app.SettingsActivity
import com.cozyting.app.capture.shell.PairingNotificationGuide
import com.cozyting.app.capture.shell.WirelessDebugGuideActivity
import com.cozyting.app.capture.shell.WirelessDebugOnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * 回归测试：
 * 1. `UiComponents.switchRow` / `compactSwitch` 在完成初次布局后点击切换，白点必须实际左右移动（不得卡在初态一侧）。
 * 2. `SettingsActivity` 向下滚动后点击会触发 `renderUi()` 的控件，必须复用 ScrollView 并保持滚动位置不回顶。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class SettingsScrollAndSwitchTest {

    @Test
    fun switchRowMovesKnobAcrossTrackOnClickAfterLayout() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val palette = UiPalette.forMode(isDark = true)
        var lastChecked = true
        val row = UiComponents.switchRow(
            context = activity,
            palette = palette,
            title = "投送时手机静音",
            subtitle = "测试开关圆点切换",
            initialChecked = true,
        ) { lastChecked = it }
        activity.setContentView(row)
        layoutView(activity.window.decorView, 1080, 300)

        val track = findTrackFrame(row)
        val knob = track.getChildAt(0)
        val expectedOffLeft = UiComponents.dp(activity, 3)
        val expectedOnLeft = UiComponents.dp(activity, 44) - UiComponents.dp(activity, 20) - UiComponents.dp(activity, 3)

        assertEquals("初态开启时白点应在右侧", expectedOnLeft, knob.left)

        row.performClick()
        layoutView(activity.window.decorView, 1080, 300)
        assertEquals(false, lastChecked)
        assertEquals("点击关闭后白点应移动到左侧", expectedOffLeft, knob.left)

        row.performClick()
        layoutView(activity.window.decorView, 1080, 300)
        assertEquals(true, lastChecked)
        assertEquals("再次开启后白点应移回右侧", expectedOnLeft, knob.left)

        controller.pause().stop().destroy()
    }

    @Test
    fun compactSwitchMovesKnobAcrossTrackOnClickAfterLayout() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val palette = UiPalette.forMode(isDark = false)
        var lastChecked = false
        val sw = UiComponents.compactSwitch(
            context = activity,
            palette = palette,
            initialChecked = false,
        ) { lastChecked = it }
        val host = LinearLayout(activity).apply { addView(sw) }
        activity.setContentView(host)
        layoutView(activity.window.decorView, 400, 200)

        val knob = sw.getChildAt(0)
        val expectedOffLeft = UiComponents.dp(activity, 3)
        val expectedOnLeft = UiComponents.dp(activity, 36) - UiComponents.dp(activity, 15) - UiComponents.dp(activity, 3)

        assertEquals("初态关闭时白点应在左侧", expectedOffLeft, knob.left)

        sw.performClick()
        layoutView(activity.window.decorView, 400, 200)
        assertEquals(true, lastChecked)
        assertEquals("点击开启后白点应移动到右侧", expectedOnLeft, knob.left)

        sw.performClick()
        layoutView(activity.window.decorView, 400, 200)
        assertEquals(false, lastChecked)
        assertEquals("再次关闭后白点应移回左侧", expectedOffLeft, knob.left)

        controller.pause().stop().destroy()
    }

    @Test
    fun settingsActivityPreservesScrollPositionOnRenderUiAndMovesNonRebuildingSwitches() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        layoutView(activity.window.decorView, 1080, 1600)

        val scroll = findFirst(activity.window.decorView, ScrollView::class.java)
        assertNotNull("SettingsActivity 应包含 ScrollView", scroll)
        scroll!!
        val child = scroll.getChildAt(0)
        val maxScroll = (child.height - scroll.height).coerceAtLeast(0)
        assertTrue("设置页内容高度应支持向下滚动 (maxScroll=$maxScroll)", maxScroll > 200)

        val targetScrollY = minOf(360, maxScroll)
        scroll.scrollTo(0, targetScrollY)
        assertEquals(targetScrollY, scroll.scrollY)

        // 1) 点击非 renderUi 开关（「投送时手机静音」）：圆点移动且不回顶
        val muteRow = findSwitchRowByTitle(scroll, activity.getString(R.string.settings_local_mute_title))
        assertNotNull(muteRow)
        val muteKnobBefore = findTrackFrame(muteRow!!).getChildAt(0).left
        muteRow.performClick()
        layoutView(activity.window.decorView, 1080, 1600)
        val muteKnobAfter = findTrackFrame(muteRow).getChildAt(0).left
        assertTrue(
            "点击「投送时手机静音」后白点位置应改变 ($muteKnobBefore -> $muteKnobAfter)",
            muteKnobBefore != muteKnobAfter,
        )
        assertEquals("点击普通开关后滚动位置应保持不变", targetScrollY, scroll.scrollY)

        // 2) 点击会触发 renderUi() 的「高音量保护」：复用同一 ScrollView 且不回顶
        val guardRow = findSwitchRowByTitle(scroll, activity.getString(R.string.settings_volume_guard_title))
        assertNotNull(guardRow)
        guardRow!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layoutView(activity.window.decorView, 1080, 1600)

        val scrollAfterGuard = findFirst(activity.window.decorView, ScrollView::class.java)
        assertSame("renderUi 应复用同一 ScrollView 实例", scroll, scrollAfterGuard)
        val newMaxScroll = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        val expectedScroll = targetScrollY.coerceIn(0, newMaxScroll)
        assertEquals("点击「高音量保护」重建内容后不应自动滚回顶部", expectedScroll, scroll.scrollY)

        // 3) 再次点击「高音量保护」恢复开启（连续触发 renderUi）：仍保持当前滚动位置
        val targetScroll2 = minOf(280, newMaxScroll)
        scroll.scrollTo(0, targetScroll2)
        val guardRowAgain = findSwitchRowByTitle(scroll, activity.getString(R.string.settings_volume_guard_title))
        assertNotNull(guardRowAgain)
        guardRowAgain!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layoutView(activity.window.decorView, 1080, 1600)

        val maxAfterToggle = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        assertEquals(
            "连续触发 renderUi 时也应保持滚动位置不回顶",
            targetScroll2.coerceIn(0, maxAfterToggle),
            scroll.scrollY,
        )

        // 4) 设置首页「关于与更新」收口为单入口，不再展示已移除的自动检查开关
        val allTexts = collectTexts(activity.window.decorView)
        assertTrue(
            "设置页底部应展示单入口「关于窝爱听」",
            allTexts.contains(activity.getString(R.string.title_about)),
        )
        assertTrue(
            "设置页不应再展示已移除的自动检查开关",
            !allTexts.contains(activity.getString(R.string.settings_update_auto_title)),
        )

        controller.pause().stop().destroy()
    }

    @Test
    fun aboutActivityRendersHeroAndTogglesBetaUpdateChannel() {
        val ctx = RuntimeEnvironment.getApplication()
        val settings = PrefsCastSettingsStore(ctx)
        settings.updateChannelRaw = "stable"

        val controller = Robolectric.buildActivity(com.cozyting.app.AboutActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        layoutView(activity.window.decorView, 1080, 1920)

        val allTexts = collectTexts(activity.window.decorView)
        assertTrue(allTexts.contains(activity.getString(R.string.app_brand_name)))
        assertTrue(allTexts.contains(activity.getString(R.string.about_check_updates_title)))
        assertTrue(allTexts.contains(activity.getString(R.string.about_beta_updates_title)))
        assertTrue(allTexts.contains(activity.getString(R.string.about_releases_title)))

        val betaRow = findSwitchRowByTitle(activity.window.decorView, activity.getString(R.string.about_beta_updates_title))
        assertNotNull(betaRow)
        betaRow!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("prerelease", settings.updateChannelRaw)

        val betaRowAfter = findSwitchRowByTitle(activity.window.decorView, activity.getString(R.string.about_beta_updates_title))
        assertNotNull(betaRowAfter)
        betaRowAfter!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("stable", settings.updateChannelRaw)

        controller.pause().stop().destroy()
    }

    @Test
    fun settingsCaptureEngineUsesCompactPlanBLayoutWithoutRedundantButtons() {
        val ctx = RuntimeEnvironment.getApplication()
        val keyStore = PairingNotificationGuide.keyStore(ctx)
        keyStore.getOrCreateIdentity()
        keyStore.markPaired()
        try {
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            val allTexts = collectTexts(activity.window.decorView)
            val scopeLabel = activity.getString(R.string.settings_capture_scope_title)
            val pairingLabel = activity.getString(R.string.settings_wireless_pair_title)
            val pairedBadge = activity.getString(R.string.settings_wireless_paired)
            val scopeIdx = allTexts.indexOf(scopeLabel)
            val pairingIdx = allTexts.indexOf(pairingLabel)
            assertTrue("应展示精简标签「$scopeLabel」", scopeIdx >= 0)
            assertTrue("应展示单行入口「$pairingLabel」", pairingIdx >= 0)
            assertTrue("「$scopeLabel」应位于「$pairingLabel」之前，避免策略与配对割裂", scopeIdx < pairingIdx)
            assertTrue("已配对时应显示「$pairedBadge」状态胶囊", allTexts.contains(pairedBadge))

            assertNull("不应再出现重复的「查看向导 / 重新配对」按钮", findTextViewByText(activity.window.decorView, "查看向导 / 重新配对"))
            assertNull("设置页不应再单独平铺「测试连接」按钮", findTextViewByText(activity.window.decorView, activity.getString(R.string.onboard_step5_action_test)))
            assertNull("设置页不应再平铺全宽「清除本机配对证书」按钮", findTextViewByText(activity.window.decorView, activity.getString(R.string.guide_btn_clear_cert)))

            val statusPrefix = activity.getString(R.string.settings_current_status, "")
            val statusLines = allTexts.filter { it.startsWith(statusPrefix) }
            assertEquals("投送时手机静音下方应保留一行实时状态", 1, statusLines.size)
            assertFalse("不应在实时状态下方追加重复说明行", statusLines.first().contains("\n说明："))

            controller.pause().stop().destroy()
        } finally {
            keyStore.clearPairing()
        }
    }

    @Test
    fun wirelessGuideProvidesClearPairingWithConfirmationDialog() {
        val ctx = RuntimeEnvironment.getApplication()
        val keyStore = PairingNotificationGuide.keyStore(ctx)
        keyStore.getOrCreateIdentity()
        keyStore.markPaired()
        try {
            val controller = Robolectric.buildActivity(WirelessDebugGuideActivity::class.java).setup()
            val activity = controller.get()
            activity.applyUiPreviewStateForTest(
                isDark = true,
                state = WirelessDebugOnboardingState.evaluate(
                    sdkInt = 34,
                    notificationsGranted = true,
                    developerOptionsEnabled = true,
                    accessibilityEnabled = true,
                    paired = true,
                    connectionVerified = true,
                    connectedPort = 41235,
                    strings = WirelessDebugOnboardingState.Strings.fromContext(activity),
                ),
                expandTips = false,
            )
            shadowOf(Looper.getMainLooper()).idle()

            val clearBtn = findTextViewByText(activity.window.decorView, activity.getString(R.string.guide_btn_clear_cert))
            assertNotNull("向导页在已配对状态下应提供清除本机配对证书入口", clearBtn)
            clearBtn!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("点击清除证书应弹出 Style B 二次确认弹窗", dialog)
            assertTrue("点击确认前尚未清除证书", keyStore.isPaired())

            val confirmBtn = findTextViewByText(dialog!!.window!!.decorView, activity.getString(R.string.guide_clear_dialog_confirm))
            assertNotNull("确认弹窗应包含确认清除按钮", confirmBtn)
            confirmBtn!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse("点击「确认清除」后应清除本机配对证书", keyStore.isPaired())
            controller.pause().stop().destroy()
        } finally {
            keyStore.clearPairing()
        }
    }

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) {
                val t = v.text?.toString().orEmpty()
                if (t.isNotBlank()) out.add(t)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun layoutView(view: View, widthPx: Int, heightPx: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
    }

    private fun findTrackFrame(switchRow: LinearLayout): FrameLayout {
        for (i in 0 until switchRow.childCount) {
            val child = switchRow.getChildAt(i)
            if (child is FrameLayout && child.childCount == 1) return child
        }
        error("Track FrameLayout not found in switchRow")
    }

    private fun findSwitchRowByTitle(root: View, title: String): LinearLayout? {
        if (root is LinearLayout && root.isClickable && root.childCount == 2 && root.getChildAt(1) is FrameLayout) {
            val tv = findTextViewByText(root, title)
            if (tv != null) return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findSwitchRowByTitle(root.getChildAt(i), title)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findTextViewByText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findTextViewByText(root.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun <T : View> findFirst(root: View, clazz: Class<T>): T? {
        if (clazz.isInstance(root)) return clazz.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirst(root.getChildAt(i), clazz)
                if (found != null) return found
            }
        }
        return null
    }
}
