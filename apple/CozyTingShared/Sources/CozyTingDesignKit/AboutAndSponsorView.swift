import CozyTingCoreKit
import SwiftUI

/// Cross-platform Style B About & Voluntary Sponsor view (`design/UI_SPEC.md` §3.4).
///
/// Strictly zero payment SDKs, zero pop-up nagging, and zero telemetry.
public struct CozyAboutView: View {
    public let appVersion: String
    public let language: AppLanguage
    public let palette: DesignTokens.ThemePalette
    public let updateStatusText: String
    public let hasUpdateAvailable: Bool
    public let isCheckingUpdates: Bool
    public let betaChannelEnabled: Bool
    public let sponsorEntryEnabled: Bool
    public let weChatQRImage: Image?
    public let alipayQRImage: Image?
    public let onCheckUpdates: () -> Void
    public let onToggleBetaChannel: (Bool) -> Void
    public let onOpenReleases: () -> Void
    public let onSaveWeChatQR: (() -> Void)?
    public let onSaveAlipayQR: (() -> Void)?
    public let onClose: (() -> Void)?

    @State private var showSponsorSheet: Bool = false
    @State private var sponsorToast: String?

    public init(
        appVersion: String,
        language: AppLanguage,
        palette: DesignTokens.ThemePalette,
        updateStatusText: String,
        hasUpdateAvailable: Bool,
        isCheckingUpdates: Bool,
        betaChannelEnabled: Bool,
        sponsorEntryEnabled: Bool,
        weChatQRImage: Image? = nil,
        alipayQRImage: Image? = nil,
        onCheckUpdates: @escaping () -> Void,
        onToggleBetaChannel: @escaping (Bool) -> Void,
        onOpenReleases: @escaping () -> Void,
        onSaveWeChatQR: (() -> Void)? = nil,
        onSaveAlipayQR: (() -> Void)? = nil,
        onClose: (() -> Void)? = nil
    ) {
        self.appVersion = appVersion
        self.language = language
        self.palette = palette
        self.updateStatusText = updateStatusText
        self.hasUpdateAvailable = hasUpdateAvailable
        self.isCheckingUpdates = isCheckingUpdates
        self.betaChannelEnabled = betaChannelEnabled
        self.sponsorEntryEnabled = sponsorEntryEnabled
        self.weChatQRImage = weChatQRImage
        self.alipayQRImage = alipayQRImage
        self.onCheckUpdates = onCheckUpdates
        self.onToggleBetaChannel = onToggleBetaChannel
        self.onOpenReleases = onOpenReleases
        self.onSaveWeChatQR = onSaveWeChatQR
        self.onSaveAlipayQR = onSaveAlipayQR
        self.onClose = onClose
    }

    private func t(_ key: L10nKey) -> String {
        CozyTingL10n.text(key, language: language)
    }

    public var body: some View {
        VStack(spacing: DesignTokens.Spacing.lg) {
            if let onClose {
                HStack {
                    Text(t(.aboutTitle))
                        .font(.system(size: DesignTokens.Typography.cardTitle, weight: .bold))
                        .foregroundStyle(palette.textPrimary)
                    Spacer()
                    Button(action: onClose) {
                        CozyTingIconView(.close, size: 13, weight: .semibold)
                            .foregroundStyle(palette.textSecondary)
                            .frame(width: 26, height: 26)
                            .background(palette.controlSurface, in: Circle())
                    }
                    .buttonStyle(.plain)
                }
            }

            // Centered F2 Brand Header
            VStack(spacing: 8) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(DesignTokens.Brand.tileDark)
                        .frame(width: 64, height: 64)
                    CozyHouseBrandShape()
                        .fill(DesignTokens.Brand.coral, style: FillStyle(eoFill: true))
                        .frame(width: 42, height: 42)
                }

                Text(BrandStrings.appDisplayName(for: language))
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(palette.textPrimary)

                Text("v\(UpdateChannel.stripBuildSuffixes(appVersion))")
                    .font(.system(size: DesignTokens.Typography.caption, design: .monospaced))
                    .foregroundStyle(palette.textSecondary)
            }
            .padding(.top, 4)

            // 4-Row Style B Card
            VStack(spacing: 0) {
                // Row 1: Check for Updates
                Button(action: onCheckUpdates) {
                    HStack {
                        Text(t(.aboutCheckUpdates))
                            .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                            .foregroundStyle(palette.textPrimary)
                        Spacer()
                        Text(updateStatusText)
                            .font(.system(size: DesignTokens.Typography.micro, weight: hasUpdateAvailable ? .semibold : .regular))
                            .foregroundStyle(hasUpdateAvailable ? Color.white : palette.textSecondary)
                            .padding(.horizontal, hasUpdateAvailable ? 8 : 0)
                            .padding(.vertical, hasUpdateAvailable ? 3 : 0)
                            .background(
                                hasUpdateAvailable ? palette.accent : Color.clear,
                                in: Capsule()
                            )
                        CozyTingIconView(.chevronRight, size: 11, weight: .semibold)
                            .foregroundStyle(palette.textTertiary)
                    }
                    .padding(DesignTokens.Spacing.md)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(isCheckingUpdates)

                Divider().opacity(0.25)

                // Row 2: Beta Channel Switch
                HStack(spacing: DesignTokens.Spacing.sm) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t(.aboutBetaUpdatesTitle))
                            .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                            .foregroundStyle(palette.textPrimary)
                        Text(t(.aboutBetaUpdatesSub))
                            .font(.system(size: DesignTokens.Typography.micro))
                            .foregroundStyle(palette.textSecondary)
                    }
                    Spacer()
                    Toggle("", isOn: Binding(
                        get: { betaChannelEnabled },
                        set: { onToggleBetaChannel($0) }
                    ))
                    .labelsHidden()
                    .toggleStyle(.switch)
                }
                .padding(DesignTokens.Spacing.md)

                Divider().opacity(0.25)

                // Row 3: Releases & Changelog
                Button(action: onOpenReleases) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.aboutReleasesTitle))
                                .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                                .foregroundStyle(palette.textPrimary)
                            Text(t(.aboutReleasesSub))
                                .font(.system(size: DesignTokens.Typography.micro))
                                .foregroundStyle(palette.textSecondary)
                        }
                        Spacer()
                        CozyTingIconView(.chevronRight, size: 11, weight: .semibold)
                            .foregroundStyle(palette.textTertiary)
                    }
                    .padding(DesignTokens.Spacing.md)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if sponsorEntryEnabled {
                    Divider().opacity(0.25)

                    // Row 4: Voluntary Sponsor Support
                    Button { showSponsorSheet = true } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t(.aboutSponsorTitle))
                                    .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                                    .foregroundStyle(palette.textPrimary)
                                Text(t(.aboutSponsorSub))
                                    .font(.system(size: DesignTokens.Typography.micro))
                                    .foregroundStyle(palette.textSecondary)
                            }
                            Spacer()
                            CozyTingIconView(.chevronRight, size: 11, weight: .semibold)
                                .foregroundStyle(palette.textTertiary)
                        }
                        .padding(DesignTokens.Spacing.md)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .background(
                RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                    .fill(palette.rowIdleBg)
            )
            .overlay(
                RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                    .strokeBorder(palette.rowIdleBorder, lineWidth: 1)
            )

            Text(t(.aboutFooterNote))
                .font(.system(size: DesignTokens.Typography.micro))
                .foregroundStyle(palette.textTertiary)
        }
        .padding(DesignTokens.Spacing.lg)
        .frame(width: 400)
        .background(palette.panelBg)
        .sheet(isPresented: $showSponsorSheet) {
            sponsorSheetView
        }
    }

    private var sponsorSheetView: some View {
        VStack(spacing: DesignTokens.Spacing.md) {
            HStack {
                Text(t(.sponsorDialogTitle))
                    .font(.system(size: DesignTokens.Typography.cardTitle, weight: .bold))
                    .foregroundStyle(palette.textPrimary)
                Spacer()
                Button { showSponsorSheet = false } label: {
                    CozyTingIconView(.close, size: 13, weight: .semibold)
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 26, height: 26)
                        .background(palette.controlSurface, in: Circle())
                }
                .buttonStyle(.plain)
            }

            Text(t(.sponsorDialogDesc))
                .font(.system(size: DesignTokens.Typography.caption))
                .foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: DesignTokens.Spacing.md) {
                qrCard(
                    title: t(.sponsorWeChatLabel),
                    image: weChatQRImage,
                    onSave: onSaveWeChatQR
                )
                qrCard(
                    title: t(.sponsorAlipayLabel),
                    image: alipayQRImage,
                    onSave: onSaveAlipayQR
                )
            }

            if let sponsorToast {
                Text(sponsorToast)
                    .font(.system(size: DesignTokens.Typography.micro, weight: .medium))
                    .foregroundStyle(palette.statusSuccess)
            }
        }
        .padding(DesignTokens.Spacing.lg)
        .frame(width: 400)
        .background(palette.panelBg)
    }

    @ViewBuilder
    private func qrCard(title: String, image: Image?, onSave: (() -> Void)?) -> some View {
        VStack(spacing: 8) {
            if let image {
                image
                    .resizable()
                    .interpolation(.high)
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 148, height: 148)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(DesignTokens.Brand.tileLight)
                    .frame(width: 148, height: 148)
                    .overlay(
                        VStack(spacing: 6) {
                            CozyHouseBrandShape()
                                .fill(DesignTokens.Brand.coral, style: FillStyle(eoFill: true))
                                .frame(width: 32, height: 32)
                            Text(title)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(DesignTokens.Brand.catInk)
                        }
                    )
            }

            Text(title)
                .font(.system(size: DesignTokens.Typography.micro, weight: .semibold))
                .foregroundStyle(palette.textPrimary)

            if let onSave {
                Button {
                    onSave()
                    sponsorToast = t(.sponsorSavedToast)
                } label: {
                    Text(t(.sponsorSaveQR))
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(palette.accentBright)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(palette.accentSoftBg, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity)
        .background(palette.rowIdleBg, in: RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous)
                .strokeBorder(palette.rowIdleBorder, lineWidth: 1)
        )
    }
}
