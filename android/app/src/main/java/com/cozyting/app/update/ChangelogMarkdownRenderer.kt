package com.cozyting.app.update

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.cozyting.app.R
import com.cozyting.app.ui.UiComponents
import com.cozyting.app.ui.UiPalette

/**
 * 更新弹窗轻量零依赖 Markdown 渲染器（Keep a Changelog / GitHub Release 格式）：
 * - `> ...` 渲染为左侧带语义色条的导语块；
 * - `##` / `### Added|Changed|Fixed|Removed|Security` 映射为三语语义分组徽标；
 * - `- ` / `* ` 要点按语义色圆点 + 父级加粗提亮 + 子级左侧细竖线缩进分层呈现；
 *   兼容历史已被压平缩进的清单（父条为 `**标题**：` 且后续 `- ` 非加粗开头时自动归入子级）；
 * - 行内支持 `**加粗**`、`` `code` `` 等宽高亮与 `[文本](url)` 链接清洗。
 */
object ChangelogMarkdownRenderer {

    enum class Category {
        ADDED,
        CHANGED,
        FIXED,
        REMOVED,
        SECURITY,
        OTHER,
    }

    sealed class Block {
        data class Callout(val text: String) : Block()
        data class Section(val category: Category, val rawTitle: String) : Block()
        data class BulletItem(
            val title: String,
            val children: List<String> = emptyList(),
            val isParentHeader: Boolean = false,
        ) : Block()
        data class Paragraph(val text: String) : Block()
    }

    private val HEADING_REGEX = Regex("^#{1,6}\\s+(.+)$")
    private val BULLET_REGEX = Regex("^([ \\t]*)(?:[-*•])\\s+(.+)$")
    private val BOLD_HEADER_ONLY_REGEX = Regex("^\\*\\*([^*]+)\\*\\*\\s*[：:]?\\s*$")
    private val INLINE_TOKEN_REGEX = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\[([^\\]]+)\\]\\((https?://[^)]+)\\))")

    fun parse(raw: String): List<Block> {
        val lines = raw.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "---" || trimmed == "***") {
                i++
                continue
            }

            // 1. Blockquote (> ...)
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (!t.startsWith(">")) break
                    val body = t.removePrefix(">").trim()
                    if (body.isNotEmpty()) quoteLines += body
                    i++
                }
                if (quoteLines.isNotEmpty()) {
                    blocks += Block.Callout(quoteLines.joinToString("\n"))
                }
                continue
            }

            // 2. Section Heading (## / ### ...)
            val headingMatch = HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                val title = headingMatch.groupValues[1].trim()
                blocks += Block.Section(classifyCategory(title), title)
                i++
                continue
            }

            // 3. Bullet Item (- / * / •) with optional nested children
            val bulletMatch = BULLET_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                val baseIndent = indentWidth(bulletMatch.groupValues[1])
                val rawBody = bulletMatch.groupValues[2].trim()
                val headerOnlyMatch = BOLD_HEADER_ONLY_REGEX.matchEntire(rawBody)
                val children = mutableListOf<String>()
                i++

                while (i < lines.size) {
                    val nextLine = lines[i]
                    val nextTrimmed = nextLine.trim()
                    if (nextTrimmed.isEmpty()) break
                    val nextBullet = BULLET_REGEX.matchEntire(nextLine)
                    if (nextBullet != null) {
                        val nextIndent = indentWidth(nextBullet.groupValues[1])
                        val nextBody = nextBullet.groupValues[2].trim()
                        val isExplicitChild = nextIndent > baseIndent
                        val isLegacyFlatChild = headerOnlyMatch != null &&
                            nextIndent == baseIndent &&
                            !nextBody.startsWith("**")
                        if (isExplicitChild || isLegacyFlatChild) {
                            children += nextBody
                            i++
                            continue
                        }
                    }
                    break
                }

                if (headerOnlyMatch != null && children.isNotEmpty()) {
                    val cleanTitle = headerOnlyMatch.groupValues[1].trim().trimEnd('：', ':')
                    blocks += Block.BulletItem(
                        title = cleanTitle,
                        children = children,
                        isParentHeader = true,
                    )
                } else {
                    val cleanedTitle = if (children.isNotEmpty()) {
                        rawBody.trimEnd('：', ':')
                    } else {
                        rawBody
                    }
                    blocks += Block.BulletItem(
                        title = cleanedTitle,
                        children = children,
                        isParentHeader = children.isNotEmpty(),
                    )
                }
                continue
            }

            // 4. Plain paragraph
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val t = cur.trim()
                if (t.isEmpty() || t == "---" || t == "***" || t.startsWith(">") ||
                    HEADING_REGEX.matches(t) || BULLET_REGEX.matches(cur)
                ) {
                    break
                }
                paraLines += t
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks += Block.Paragraph(paraLines.joinToString("\n"))
            }
        }
        return blocks
    }

    fun classifyCategory(rawTitle: String): Category {
        val norm = rawTitle.trim().lowercase()
        return when {
            norm == "added" || norm == "new" || norm == "features" || norm == "新增" || norm == "追加" -> Category.ADDED
            norm == "changed" || norm == "improved" || norm == "improvements" || norm == "优化" || norm == "变更" || norm == "优化与变更" || norm == "変更" -> Category.CHANGED
            norm == "fixed" || norm == "bug fixes" || norm == "fixes" || norm == "修复" || norm == "问题修复" || norm == "修正" -> Category.FIXED
            norm == "removed" || norm == "deprecated" || norm == "移除" || norm == "削除" -> Category.REMOVED
            norm == "security" || norm == "安全" || norm == "セキュリティ" -> Category.SECURITY
            else -> Category.OTHER
        }
    }

    fun renderInto(
        context: Context,
        container: LinearLayout,
        rawNotes: String,
        palette: UiPalette,
        isPrerelease: Boolean = false,
    ) {
        val blocks = parse(rawNotes)
        if (blocks.isEmpty()) return

        val dp: (Int) -> Int = { UiComponents.dp(context, it) }
        val dpf: (Float) -> Float = { UiComponents.dpf(context, it) }
        var activeCategory = Category.OTHER

        blocks.forEachIndexed { idx, block ->
            when (block) {
                is Block.Callout -> {
                    val barColor = if (isPrerelease) palette.statusWarning else palette.accentBright
                    val calloutBg = Color.argb(
                        if (palette.isDark) 22 else 15,
                        Color.red(palette.accentBright),
                        Color.green(palette.accentBright),
                        Color.blue(palette.accentBright),
                    )
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        background = UiComponents.roundedDrawable(calloutBg, dpf(9f))
                        setPadding(dp(9), dp(7), dp(10), dp(7))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (idx > 0) topMargin = dp(6)
                            bottomMargin = dp(8)
                        }
                    }
                    row.addView(
                        View(context).apply {
                            background = UiComponents.roundedDrawable(barColor, dpf(2f))
                            layoutParams = LinearLayout.LayoutParams(
                                dp(3).coerceAtLeast(3),
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ).apply { marginEnd = dp(8) }
                        },
                    )
                    row.addView(
                        TextView(context).apply {
                            text = formatInline(block.text, palette)
                            textSize = 12f
                            setTextColor(withAlpha(palette.textPrimary, 232))
                            setLineSpacing(dpf(2.5f), 1f)
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                        },
                    )
                    container.addView(row)
                }

                is Block.Section -> {
                    activeCategory = block.category
                    val label = localizedSectionLabel(context, block)
                    val (fg, bg) = categoryColors(block.category, palette)
                    val pillWrap = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (idx > 0) topMargin = dp(8)
                            bottomMargin = dp(6)
                        }
                    }
                    pillWrap.addView(
                        TextView(context).apply {
                            text = label
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(fg)
                            background = UiComponents.roundedDrawable(bg, dpf(6f))
                            setPadding(dp(8), dp(2), dp(8), dp(2))
                        },
                    )
                    container.addView(pillWrap)
                }

                is Block.BulletItem -> {
                    val (dotColor, _) = categoryColors(activeCategory, palette)
                    val itemRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.TOP
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = if (block.children.isNotEmpty()) dp(4) else dp(6)
                        }
                    }
                    val dotSize = dp(5).coerceAtLeast(4)
                    itemRow.addView(
                        View(context).apply {
                            background = UiComponents.roundedDrawable(dotColor, dotSize / 2f)
                            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                                topMargin = dp(7)
                                marginEnd = dp(8)
                            }
                        },
                    )
                    itemRow.addView(
                        TextView(context).apply {
                            text = formatInline(block.title, palette)
                            textSize = 12.5f
                            if (block.isParentHeader) {
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(palette.textPrimary)
                            } else {
                                setTextColor(palette.textSecondary)
                            }
                            setLineSpacing(dpf(2.5f), 1f)
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                        },
                    )
                    container.addView(itemRow)

                    if (block.children.isNotEmpty()) {
                        val subWrap = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                marginStart = dp(2)
                                bottomMargin = dp(8)
                            }
                        }
                        subWrap.addView(
                            View(context).apply {
                                setBackgroundColor(palette.panelBorder)
                                layoutParams = LinearLayout.LayoutParams(
                                    dp(1).coerceAtLeast(2),
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ).apply {
                                    topMargin = dp(2)
                                    bottomMargin = dp(2)
                                    marginEnd = dp(10)
                                }
                            },
                        )
                        val subList = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                        }
                        block.children.forEachIndexed { childIdx, child ->
                            subList.addView(
                                TextView(context).apply {
                                    text = formatInline(child, palette)
                                    textSize = 12f
                                    setTextColor(palette.textSecondary)
                                    setLineSpacing(dpf(2.5f), 1f)
                                    if (childIdx > 0) {
                                        setPadding(0, dp(4), 0, 0)
                                    }
                                },
                            )
                        }
                        subWrap.addView(subList)
                        container.addView(subWrap)
                    }
                }

                is Block.Paragraph -> {
                    container.addView(
                        TextView(context).apply {
                            text = formatInline(block.text, palette)
                            textSize = 12.5f
                            setTextColor(palette.textSecondary)
                            setLineSpacing(dpf(2.5f), 1f)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                if (idx > 0) topMargin = dp(4)
                                bottomMargin = dp(4)
                            }
                        },
                    )
                }
            }
        }
    }

    fun formatInline(raw: String, palette: UiPalette): CharSequence {
        val sb = SpannableStringBuilder()
        var cursor = 0
        for (match in INLINE_TOKEN_REGEX.findAll(raw)) {
            if (match.range.first > cursor) {
                sb.append(raw.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("`") && token.endsWith("`") && token.length >= 2 -> {
                    val code = token.substring(1, token.length - 1)
                    val start = sb.length
                    sb.append('\u2009').append(code).append('\u2009')
                    val end = sb.length
                    sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.90f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(palette.controlSurface), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(palette.textPrimary), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                token.startsWith("**") && token.endsWith("**") && token.length >= 4 -> {
                    val bold = token.substring(2, token.length - 2)
                    val start = sb.length
                    sb.append(bold)
                    val end = sb.length
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(palette.textPrimary), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                token.startsWith("[") -> {
                    val label = match.groupValues[2]
                    val start = sb.length
                    sb.append(label)
                    val end = sb.length
                    sb.setSpan(ForegroundColorSpan(palette.accentBright), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> sb.append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            sb.append(raw.substring(cursor))
        }
        return sb
    }

    private fun localizedSectionLabel(context: Context, section: Block.Section): String = when (section.category) {
        Category.ADDED -> context.getString(R.string.update_changelog_sec_added)
        Category.CHANGED -> context.getString(R.string.update_changelog_sec_changed)
        Category.FIXED -> context.getString(R.string.update_changelog_sec_fixed)
        Category.REMOVED -> context.getString(R.string.update_changelog_sec_removed)
        Category.SECURITY -> context.getString(R.string.update_changelog_sec_security)
        Category.OTHER -> section.rawTitle
    }

    private fun categoryColors(category: Category, palette: UiPalette): Pair<Int, Int> {
        val alpha = if (palette.isDark) 38 else 28
        return when (category) {
            Category.ADDED -> palette.statusSuccess to withAlpha(palette.statusSuccess, alpha)
            Category.CHANGED -> palette.accentBright to palette.accentSoftBg
            Category.FIXED -> palette.statusWarning to withAlpha(palette.statusWarning, alpha)
            Category.REMOVED, Category.SECURITY -> palette.statusError to withAlpha(palette.statusError, alpha)
            Category.OTHER -> palette.accentBright to palette.accentSoftBg
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun indentWidth(prefix: String): Int {
        var width = 0
        for (ch in prefix) {
            width += if (ch == '\t') 4 else 1
        }
        return width
    }
}
