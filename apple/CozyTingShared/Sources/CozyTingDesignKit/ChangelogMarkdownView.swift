import CozyTingCoreKit
import SwiftUI

/// Semantic section kind matching `Keep a Changelog` headings in L1 `clean_release_notes`.
public enum ChangelogSectionKind: String, Sendable, Equatable {
    case added
    case changed
    case fixed
    case removed
    case security
    case other
}

/// Structured block parsed from cleaned Markdown release notes (zero raw markdown leaks).
public enum ChangelogBlock: Sendable, Equatable {
    case callout(String)
    case section(kind: ChangelogSectionKind, rawTitle: String)
    case bullet(level: Int, text: String)
    case paragraph(String)
}

/// Zero-dependency parser turning L1 `clean_release_notes` into structured visual blocks.
public enum ChangelogMarkdownParser {
    public static func parse(_ raw: String) -> [ChangelogBlock] {
        var blocks: [ChangelogBlock] = []
        let lines = raw.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { continue }

            // 1. Blockquote callout (`> ...`)
            if trimmed.hasPrefix(">") {
                let text = trimmed.dropFirst().trimmingCharacters(in: .whitespaces)
                if !text.isEmpty {
                    blocks.append(.callout(text))
                }
                continue
            }

            // 2. Section Heading (`### Added`, `## Fixed`, etc.)
            if trimmed.hasPrefix("#") {
                let heading = trimmed.drop(while: { $0 == "#" }).trimmingCharacters(in: .whitespaces)
                if !heading.isEmpty {
                    blocks.append(.section(kind: classifySection(heading), rawTitle: heading))
                }
                continue
            }

            // 3. Nested bullets preserving leading indentation (`- `, `* `)
            let leadingSpaces = line.prefix(while: { $0 == " " || $0 == "\t" })
                .reduce(0) { $0 + ($1 == "\t" ? 2 : 1) }
            if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") {
                let body = String(trimmed.dropFirst(2)).trimmingCharacters(in: .whitespaces)
                let level = min(leadingSpaces / 2, 3)
                if !body.isEmpty {
                    blocks.append(.bullet(level: level, text: body))
                }
                continue
            }

            // 4. Plain paragraph fallback
            blocks.append(.paragraph(trimmed))
        }

        return blocks
    }

    private static func classifySection(_ rawTitle: String) -> ChangelogSectionKind {
        let lower = rawTitle.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if lower.contains("added") || lower.contains("新增") || lower.contains("追加") { return .added }
        if lower.contains("changed") || lower.contains("变更") || lower.contains("优化") || lower.contains("変更") { return .changed }
        if lower.contains("fixed") || lower.contains("修复") || lower.contains("修正") { return .fixed }
        if lower.contains("removed") || lower.contains("移除") || lower.contains("削除") { return .removed }
        if lower.contains("security") || lower.contains("安全") || lower.contains("セキュリティ") { return .security }
        return .other
    }
}

/// Cross-platform Style B structured Markdown changelog renderer (`design/UI_SPEC.md` §3.4).
public struct CozyChangelogMarkdownView: View {
    public let markdown: String
    public let language: AppLanguage
    public let palette: DesignTokens.ThemePalette

    public init(
        markdown: String,
        language: AppLanguage,
        palette: DesignTokens.ThemePalette
    ) {
        self.markdown = markdown
        self.language = language
        self.palette = palette
    }

    public var body: some View {
        let blocks = ChangelogMarkdownParser.parse(markdown)
        VStack(alignment: .leading, spacing: 7) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                switch block {
                case .callout(let text):
                    HStack(alignment: .top, spacing: 8) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(palette.accent)
                            .frame(width: 3)
                        inlineMarkdownText(text)
                            .font(.system(size: DesignTokens.Typography.caption))
                            .foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.accentSoftBg.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))

                case let .section(kind, rawTitle):
                    let (label, color) = sectionBadge(kind: kind, fallback: rawTitle)
                    Text(label)
                        .font(.system(size: 10.5, weight: .bold))
                        .foregroundStyle(color)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2.5)
                        .background(color.opacity(0.16), in: Capsule())
                        .padding(.top, 4)

                case let .bullet(level, text):
                    if level == 0 {
                        HStack(alignment: .top, spacing: 6) {
                            Circle()
                                .fill(palette.accentBright)
                                .frame(width: 5, height: 5)
                                .padding(.top, 5.5)
                            inlineMarkdownText(text)
                                .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                                .foregroundStyle(palette.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    } else {
                        HStack(alignment: .top, spacing: 8) {
                            Rectangle()
                                .fill(palette.panelBorder)
                                .frame(width: 1.5)
                                .padding(.leading, CGFloat(level) * 10)
                            inlineMarkdownText(text)
                                .font(.system(size: DesignTokens.Typography.micro))
                                .foregroundStyle(palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }

                case .paragraph(let text):
                    inlineMarkdownText(text)
                        .font(.system(size: DesignTokens.Typography.caption))
                        .foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func inlineMarkdownText(_ source: String) -> Text {
        if let attr = try? AttributedString(
            markdown: source,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        ) {
            return Text(attr)
        }
        return Text(source)
    }

    private func sectionBadge(kind: ChangelogSectionKind, fallback: String) -> (String, Color) {
        switch kind {
        case .added:
            return (CozyTingL10n.text(.updateSecAdded, language: language), palette.statusSuccess)
        case .changed:
            return (CozyTingL10n.text(.updateSecChanged, language: language), palette.accentBright)
        case .fixed:
            return (CozyTingL10n.text(.updateSecFixed, language: language), palette.statusWarning)
        case .removed:
            return (CozyTingL10n.text(.updateSecRemoved, language: language), palette.statusError)
        case .security:
            return (CozyTingL10n.text(.updateSecSecurity, language: language), DesignTokens.Brand.coral)
        case .other:
            return (fallback, palette.textSecondary)
        }
    }
}
