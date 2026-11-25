package com.cozyting.app.update

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.cozyting.app.BuildConfig
import com.cozyting.app.R
import com.cozyting.app.ui.UiComponents
import com.cozyting.app.ui.UiPalette
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class UpdateDialogControllerTest {

    private fun buildDecision(notes: String): UpdateDecision = UpdateDecision(
        status = UpdateDecisionStatus.UPDATE_AVAILABLE,
        currentVersion = "0.9.7-rc.8",
        latestTag = "v0.9.7-rc.10",
        latestVersion = "0.9.7-rc.10",
        isPrerelease = true,
        publishedAt = "2026-11-24T08:00:00Z",
        releaseNotes = notes,
        changelogEntries = listOf(
            ChangelogEntry(
                tag = "v0.9.7-rc.10",
                version = "0.9.7-rc.10",
                publishedAt = "2026-11-24T08:00:00Z",
                prerelease = true,
                notes = notes,
            ),
        ),
        versionsBehind = 1,
        changelogTruncated = false,
        releaseUrl = "https://github.com/JasirVoriya/cozyting/releases/tag/v0.9.7-rc.10",
        assetName = "cozyting-v0.9.7-rc.10-arm64-v8a.apk",
        downloadUrl = "https://github.com/JasirVoriya/cozyting/releases/download/v0.9.7-rc.10/cozyting-v0.9.7-rc.10-arm64-v8a.apk",
        assetSizeBytes = 2_600_000L,
        sha256 = "0".repeat(64),
        sha256SumsUrl = "https://github.com/JasirVoriya/cozyting/releases/download/v0.9.7-rc.10/SHA256SUMS.txt",
        installable = true,
        error = "",
    )

    private fun findAllViews(root: View, out: MutableList<View> = mutableListOf()): List<View> {
        out += root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findAllViews(root.getChildAt(i), out)
            }
        }
        return out
    }

    @Test
    fun longReleaseNotesClampScrollViewInMeasureAndKeepActionButtonsVisible() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val longNotes = (1..40).joinToString("\n") { idx ->
            "- Line $idx: Detailed multi-language release changelog entry covering Android, Rust L1, and LAN protocol updates."
        }
        val dialog = UpdateDialogController.show(activity, UiPalette.forMode(isDark = true), buildDecision(longNotes))
        val decor = requireNotNull(dialog.window?.decorView)
        val widthPx = activity.resources.displayMetrics.widthPixels
        val heightPx = activity.resources.displayMetrics.heightPixels

        decor.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)

        val allViews = findAllViews(decor)
        val scroll = allViews.filterIsInstance<ScrollView>().firstOrNull()
        assertNotNull("Expected ScrollView for release notes", scroll)
        val maxAllowedPx = UiComponents.dp(activity, 260)
        assertTrue(
            "Expected ScrollView height (${scroll!!.measuredHeight}) <= $maxAllowedPx on first measure pass",
            scroll.measuredHeight <= maxAllowedPx,
        )

        val texts = allViews.filterIsInstance<TextView>()
        if (!BuildConfig.IS_ACCEPTANCE) {
            val downloadBtn = texts.firstOrNull {
                it.text?.toString() == activity.getString(R.string.update_dialog_btn_download_install)
            }
            assertNotNull("Expected primary download button", downloadBtn)
            assertTrue("Download button must have positive height", downloadBtn!!.height > 0)
            assertTrue(
                "Dialog total height (${decor.measuredHeight}) must fit inside screen ($heightPx)",
                decor.measuredHeight < heightPx,
            )
        }
        val remindLater = texts.firstOrNull {
            it.text?.toString() == activity.getString(R.string.update_dialog_remind_later)
        }
        assertNotNull("Expected footer remind-later action", remindLater)
        assertTrue("Footer remind-later action must have positive height", remindLater!!.height > 0)

        dialog.dismiss()
    }

    @Test
    fun shortReleaseNotesWrapNaturallyBelowMaxHeight() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = UpdateDialogController.show(activity, UiPalette.forMode(isDark = false), buildDecision("Short single-line note."))
        val decor = requireNotNull(dialog.window?.decorView)
        val widthPx = activity.resources.displayMetrics.widthPixels
        val heightPx = activity.resources.displayMetrics.heightPixels

        decor.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)

        val scroll = findAllViews(decor).filterIsInstance<ScrollView>().first()
        val maxAllowedPx = UiComponents.dp(activity, 260)
        assertTrue("Short notes ScrollView (${scroll.measuredHeight}) should wrap below $maxAllowedPx", scroll.measuredHeight < maxAllowedPx)
        dialog.dismiss()
    }

    @Test
    fun structuredMarkdownRendersWithoutRawSyntaxTokens() {
        val raw = """
            > 预发版（Pre-release）：补齐常用操作记忆。

            ### Added
            - **总音量与单音箱独立音量持久化**：
              - 记住总音量与单音箱独立音量。
              - 开启高音量保护时自动钳制回上限。
            - **更新横幅 `×` 持久化**：
            - 点击 `×` 后按 `tag` 持久化，详见 [说明](https://example.com/docs)。

            ### Fixed
            - **默认配对名称**：更正为 `cozyting@android`。
        """.trimIndent()

        val blocks = ChangelogMarkdownRenderer.parse(raw)
        assertTrue("Expected callout first", blocks.first() is ChangelogMarkdownRenderer.Block.Callout)
        val sections = blocks.filterIsInstance<ChangelogMarkdownRenderer.Block.Section>()
        assertTrue("Expected Added and Fixed sections", sections.size == 2)
        val bullets = blocks.filterIsInstance<ChangelogMarkdownRenderer.Block.BulletItem>()
        assertTrue("Expected 3 top-level bullet items (explicit + legacy flat + inline)", bullets.size == 3)
        assertTrue("Expected 2 explicit children", bullets[0].children.size == 2)
        assertTrue("Expected 1 legacy flat child", bullets[1].children.size == 1)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = UpdateDialogController.show(activity, UiPalette.forMode(isDark = true), buildDecision(raw))
        val decor = requireNotNull(dialog.window?.decorView)
        val allText = findAllViews(decor).filterIsInstance<TextView>().joinToString("\n") { it.text?.toString().orEmpty() }
        assertTrue("Should render localized Added label", allText.contains(activity.getString(R.string.update_changelog_sec_added)))
        assertTrue("Should render localized Fixed label", allText.contains(activity.getString(R.string.update_changelog_sec_fixed)))
        assertTrue("Should strip raw ### syntax", !allText.contains("### "))
        assertTrue("Should strip raw ** syntax", !allText.contains("**"))
        assertTrue("Should strip raw backtick syntax", !allText.contains("`"))
        assertTrue("Should strip raw markdown link URL", !allText.contains("https://example.com/docs"))
        dialog.dismiss()
    }
}
