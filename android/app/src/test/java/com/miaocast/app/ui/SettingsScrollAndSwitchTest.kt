package com.miaocast.app.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.miaocast.app.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
        val muteRow = findSwitchRowByTitle(scroll, "投送时手机静音")
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
        val guardRow = findSwitchRowByTitle(scroll, "高音量保护")
        assertNotNull(guardRow)
        guardRow!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layoutView(activity.window.decorView, 1080, 1600)

        val scrollAfterGuard = findFirst(activity.window.decorView, ScrollView::class.java)
        assertSame("renderUi 应复用同一 ScrollView 实例", scroll, scrollAfterGuard)
        val newMaxScroll = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        val expectedScroll = targetScrollY.coerceIn(0, newMaxScroll)
        assertEquals("点击「高音量保护」重建内容后不应自动滚回顶部", expectedScroll, scroll.scrollY)

        // 3) 点击「更新通道」中的未选中项（会连续触发两次 renderUi）：仍保持当前滚动位置
        val targetScroll2 = minOf(280, newMaxScroll)
        scroll.scrollTo(0, targetScroll2)
        val previewChannelBtn = findTextViewByText(scroll, "预览版 (RC)")
        assertNotNull(previewChannelBtn)
        previewChannelBtn!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layoutView(activity.window.decorView, 1080, 1600)

        val maxAfterChannel = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        assertEquals(
            "连续触发 renderUi 时也应保持滚动位置不回顶",
            targetScroll2.coerceIn(0, maxAfterChannel),
            scroll.scrollY,
        )

        controller.pause().stop().destroy()
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
