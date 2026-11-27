import CozyTingCoreKit
import SwiftUI

/// Non-blocking single-line update banner displayed below the top bar (`design/UI_SPEC.md` §2.1).
public struct CozyUpdateBannerView: View {
    public let latestTag: String
    public let language: AppLanguage
    public let palette: DesignTokens.ThemePalette
    public let onView: () -> Void
    public let onDismiss: () -> Void

    public init(
        latestTag: String,
        language: AppLanguage,
        palette: DesignTokens.ThemePalette,
        onView: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.latestTag = latestTag
        self.language = language
        self.palette = palette
        self.onView = onView
        self.onDismiss = onDismiss
    }

    public var body: some View {
        HStack(spacing: 8) {
            CozyTingIconView(.update, size: 13, weight: .semibold)
                .foregroundStyle(palette.accentBright)

            Text(CozyTingL10n.format(.updateBannerFound, language: language, latestTag))
                .font(.system(size: DesignTokens.Typography.micro, weight: .semibold))
                .foregroundStyle(palette.textPrimary)
                .lineLimit(1)

            Spacer(minLength: 4)

            Button(action: onView) {
                Text(CozyTingL10n.text(.updateBannerView, language: language))
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(palette.accent, in: Capsule())
            }
            .buttonStyle(.plain)

            Button(action: onDismiss) {
                CozyTingIconView(.close, size: 10, weight: .semibold)
                    .foregroundStyle(palette.textTertiary)
                    .frame(width: 20, height: 20)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(palette.accentSoftBg, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .strokeBorder(palette.accent.opacity(0.35), lineWidth: 1)
        )
    }
}

/// Cross-platform Style B Update Detail modal (`design/UI_SPEC.md` §3.4).
///
/// Hard-clamps the changelog scroll area to `maxHeight: 260` so action buttons remain visible.
public struct CozyUpdateDetailSheetView: View {
    public let decision: UpdateDecision
    public let language: AppLanguage
    public let palette: DesignTokens.ThemePalette
    public let onOpenReleasePage: (URL) -> Void
    public let onOpenDownloadedDMG: (URL) -> Void
    public let onSkipVersion: () -> Void
    public let onDismiss: () -> Void

    @State private var downloadProgress: Double?
    @State private var isVerifying: Bool = false
    @State private var verifiedDMGURL: URL?
    @State private var downloadError: String?

    public init(
        decision: UpdateDecision,
        language: AppLanguage,
        palette: DesignTokens.ThemePalette,
        onOpenReleasePage: @escaping (URL) -> Void,
        onOpenDownloadedDMG: @escaping (URL) -> Void,
        onSkipVersion: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.decision = decision
        self.language = language
        self.palette = palette
        self.onOpenReleasePage = onOpenReleasePage
        self.onOpenDownloadedDMG = onOpenDownloadedDMG
        self.onSkipVersion = onSkipVersion
        self.onDismiss = onDismiss
    }

    private func t(_ key: L10nKey) -> String {
        CozyTingL10n.text(key, language: language)
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            // Header
            HStack(spacing: 8) {
                Text(t(.updateDialogTitle))
                    .font(.system(size: DesignTokens.Typography.pageTitle, weight: .bold))
                    .foregroundStyle(palette.textPrimary)

                Text(decision.latestTag)
                    .font(.system(size: 11.5, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(palette.accent, in: Capsule())

                if decision.isPrerelease {
                    Text(t(.updateDialogPreviewBadge))
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(palette.statusWarning)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(palette.statusWarning.opacity(0.16), in: Capsule())
                }

                Spacer()

                Button(action: onDismiss) {
                    CozyTingIconView(.close, size: 13, weight: .semibold)
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 26, height: 26)
                        .background(palette.controlSurface, in: Circle())
                }
                .buttonStyle(.plain)
            }

            if decision.status == .unsupportedOs {
                Text(t(.updateDialogUnsupportedOS))
                    .font(.system(size: DesignTokens.Typography.caption))
                    .foregroundStyle(palette.statusWarning)
            }

            // Hard-clamped scrollable changelog (maxHeight: 260 per UI_SPEC §3.4)
            ScrollView {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                    if !decision.changelogEntries.isEmpty {
                        ForEach(decision.changelogEntries, id: \.tag) { entry in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text(entry.tag)
                                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                                        .foregroundStyle(palette.textPrimary)
                                    Spacer()
                                    if entry.publishedAt.count >= 10 {
                                        Text(String(entry.publishedAt.prefix(10)))
                                            .font(.system(size: 10.5, design: .monospaced))
                                            .foregroundStyle(palette.textTertiary)
                                    }
                                }
                                CozyChangelogMarkdownView(
                                    markdown: entry.notes,
                                    language: language,
                                    palette: palette
                                )
                            }
                            .padding(10)
                            .background(palette.rowIdleBg, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    } else {
                        CozyChangelogMarkdownView(
                            markdown: decision.releaseNotes,
                            language: language,
                            palette: palette
                        )
                        .padding(10)
                        .background(palette.rowIdleBg, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
            }
            .frame(maxHeight: 260)

            // Download progress / error status
            if let progress = downloadProgress {
                VStack(alignment: .leading, spacing: 4) {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(palette.trackIdle).frame(height: 4)
                            Capsule()
                                .fill(palette.accent)
                                .frame(width: max(4, geo.size.width * min(max(progress, 0), 1)), height: 4)
                        }
                    }
                    .frame(height: 6)
                    Text(isVerifying ? t(.updateDialogVerifying) : "\(Int((progress * 100).rounded()))%")
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(palette.textSecondary)
                }
            }

            if let downloadError {
                Text(downloadError)
                    .font(.system(size: DesignTokens.Typography.micro))
                    .foregroundStyle(palette.statusError)
            }

            // Primary Action
            if let verifiedDMGURL {
                Button {
                    onOpenDownloadedDMG(verifiedDMGURL)
                } label: {
                    Text(t(.updateDialogVerifiedOpen))
                        .font(.system(size: DesignTokens.Typography.body, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(palette.statusSuccess, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
                .buttonStyle(.plain)
            } else if decision.installable && !decision.downloadUrl.isEmpty && decision.status != .unsupportedOs {
                Button { startDownload() } label: {
                    Text(t(.updateDialogDownloadDMG))
                        .font(.system(size: DesignTokens.Typography.body, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(palette.accent, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(downloadProgress != nil)
            }

            // Secondary Actions
            HStack(spacing: DesignTokens.Spacing.sm) {
                if let relURL = URL(string: decision.releaseUrl), UpdateConfig.isAllowedHTTPSURL(relURL) {
                    Button {
                        onOpenReleasePage(relURL)
                    } label: {
                        Text(t(.updateDialogOpenRelease))
                            .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                            .foregroundStyle(palette.accentBright)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(palette.accentSoftBg, in: RoundedRectangle(cornerRadius: 9))
                    }
                    .buttonStyle(.plain)
                }

                Spacer()

                Button(action: onSkipVersion) {
                    Text(t(.updateDialogSkipVersion))
                        .font(.system(size: DesignTokens.Typography.caption))
                        .foregroundStyle(palette.textTertiary)
                }
                .buttonStyle(.plain)

                Button(action: onDismiss) {
                    Text(t(.updateDialogRemindLater))
                        .font(.system(size: DesignTokens.Typography.caption, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(palette.controlSurface, in: RoundedRectangle(cornerRadius: 9))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DesignTokens.Spacing.lg)
        .frame(width: 440)
        .background(palette.panelBg)
    }

    private func startDownload() {
        downloadError = nil
        downloadProgress = 0.01
        isVerifying = false

        Task {
            do {
                let url = try await DMGDownloader.downloadAndVerify(
                    downloadURLString: decision.downloadUrl,
                    assetName: decision.assetName,
                    expectedSHA256: decision.sha256,
                    sha256SumsURLString: decision.sha256SumsUrl,
                    onProgress: { frac in
                        Task { @MainActor in
                            downloadProgress = frac
                            if frac >= 0.99 {
                                isVerifying = true
                            }
                        }
                    }
                )
                await MainActor.run {
                    downloadProgress = nil
                    isVerifying = false
                    verifiedDMGURL = url
                    onOpenDownloadedDMG(url)
                }
            } catch {
                await MainActor.run {
                    downloadProgress = nil
                    isVerifying = false
                    downloadError = String(describing: error)
                }
            }
        }
    }
}
