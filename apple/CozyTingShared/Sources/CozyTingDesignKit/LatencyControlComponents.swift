import CozyTingCoreKit
import SwiftUI

/// Style B 4-tier latency quick capsule + compact dropdown menu aligned with `design/UI_SPEC.md` §2.1.
///
/// Single source of truth: derives its visual tier strictly from persisted `delayMs` (`0` = Standard auto).
public struct CozyLatencyPillMenu: View {
    public let delayMs: Int
    public let wifiFrequencyMHz: Int32
    public let palette: DesignTokens.ThemePalette
    public let standardLabel: String
    public let balancedLabel: String
    public let lowLabel: String
    public let ultraLabel: String
    public let customLabel: String
    public let guideLabel: String
    public let onSelectDelayMs: (Int) -> Void
    public let onOpenGuide: () -> Void

    public init(
        delayMs: Int,
        wifiFrequencyMHz: Int32,
        palette: DesignTokens.ThemePalette,
        standardLabel: String = "Standard",
        balancedLabel: String = "Balanced",
        lowLabel: String = "Low",
        ultraLabel: String = "Ultra",
        customLabel: String = "Custom",
        guideLabel: String = "Latency Guide",
        onSelectDelayMs: @escaping (Int) -> Void,
        onOpenGuide: @escaping () -> Void
    ) {
        self.delayMs = delayMs
        self.wifiFrequencyMHz = wifiFrequencyMHz
        self.palette = palette
        self.standardLabel = standardLabel
        self.balancedLabel = balancedLabel
        self.lowLabel = lowLabel
        self.ultraLabel = ultraLabel
        self.customLabel = customLabel
        self.guideLabel = guideLabel
        self.onSelectDelayMs = onSelectDelayMs
        self.onOpenGuide = onOpenGuide
    }

    private var preset: LatencyPreset {
        LatencyPreset(delayMs: delayMs)
    }

    private var effectiveMs: Int {
        preset.effectiveDelayMs(wifiFrequencyMHz: wifiFrequencyMHz)
    }

    private var tierIcon: CozyTingIcon {
        switch preset {
        case .standard: return .latencyStandard
        case .balanced: return .latencyBalanced
        case .low: return .latencyLow
        case .ultra: return .latencyUltra
        case .custom: return .latencyCustom
        }
    }

    private var pillForeground: Color {
        switch preset {
        case .standard:
            return palette.textPrimary
        case .balanced, .low:
            return palette.accentBright
        case .ultra:
            return palette.statusWarning
        case .custom(let ms):
            return ms < 250 ? palette.statusWarning : palette.accentBright
        }
    }

    private var pillBackground: Color {
        switch preset {
        case .standard:
            return palette.controlSurface
        case .balanced, .low:
            return palette.accentSoftBg
        case .ultra:
            return palette.statusWarning.opacity(0.16)
        case .custom(let ms):
            return ms < 250 ? palette.statusWarning.opacity(0.16) : palette.accentSoftBg
        }
    }

    private var pillShortLabel: String {
        switch preset {
        case .standard: return "\(effectiveMs)ms"
        case .balanced: return "\(balancedLabel) 400ms"
        case .low: return "\(lowLabel) 250ms"
        case .ultra: return "\(ultraLabel) 150ms"
        case .custom(let ms): return "\(ms)ms"
        }
    }

    public var body: some View {
        Menu {
            presetMenuButton(
                title: standardLabel,
                ms: LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: wifiFrequencyMHz),
                selected: preset == .standard,
                targetDelayMs: 0
            )
            presetMenuButton(
                title: balancedLabel,
                ms: 400,
                selected: preset == .balanced,
                targetDelayMs: 400
            )
            presetMenuButton(
                title: lowLabel,
                ms: 250,
                selected: preset == .low,
                targetDelayMs: 250
            )
            presetMenuButton(
                title: ultraLabel,
                ms: 150,
                selected: preset == .ultra,
                targetDelayMs: 150
            )

            if case let .custom(ms) = preset {
                Divider()
                Button {
                    onSelectDelayMs(ms)
                } label: {
                    Label("\(customLabel) · \(ms)ms", systemImage: "checkmark")
                }
            }

            Divider()

            Button {
                onOpenGuide()
            } label: {
                Label(guideLabel, systemImage: CozyTingIcon.info.sfSymbolName)
            }
        } label: {
            HStack(spacing: 4) {
                CozyTingIconView(tierIcon, size: 11, weight: .semibold)
                Text(pillShortLabel)
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .lineLimit(1)
                CozyTingIconView(.chevronDown, size: 9, weight: .semibold)
            }
            .foregroundStyle(pillForeground)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(pillBackground, in: Capsule())
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
    }

    @ViewBuilder
    private func presetMenuButton(
        title: String,
        ms: Int,
        selected: Bool,
        targetDelayMs: Int
    ) -> some View {
        Button {
            onSelectDelayMs(targetDelayMs)
        } label: {
            if selected {
                Label("\(title)  \(ms)ms", systemImage: "checkmark")
            } else {
                Text("\(title)  \(ms)ms")
            }
        }
    }
}

/// Style B modal detailing the 4 latency tiers, L1 startup runway AUs, and active Wi-Fi band.
public struct CozyLatencyGuideSheetView: View {
    public let currentDelayMs: Int
    public let wifiFrequencyMHz: Int32
    public let palette: DesignTokens.ThemePalette
    public let title: String
    public let subtitle: String
    public let customSettingsLabel: String
    public let closeLabel: String
    public let onSelectDelayMs: (Int) -> Void
    public let onOpenSettings: () -> Void
    public let onClose: () -> Void

    public init(
        currentDelayMs: Int,
        wifiFrequencyMHz: Int32,
        palette: DesignTokens.ThemePalette,
        title: String = "Latency Modes",
        subtitle: String = "Switch live without disconnecting speakers. Cold-start runway scales automatically via L1 core.",
        customSettingsLabel: String = "Custom ms in Settings",
        closeLabel: String = "Done",
        onSelectDelayMs: @escaping (Int) -> Void,
        onOpenSettings: @escaping () -> Void,
        onClose: @escaping () -> Void
    ) {
        self.currentDelayMs = currentDelayMs
        self.wifiFrequencyMHz = wifiFrequencyMHz
        self.palette = palette
        self.title = title
        self.subtitle = subtitle
        self.customSettingsLabel = customSettingsLabel
        self.closeLabel = closeLabel
        self.onSelectDelayMs = onSelectDelayMs
        self.onOpenSettings = onOpenSettings
        self.onClose = onClose
    }

    private struct TierRowModel: Identifiable {
        let id: String
        let preset: LatencyPreset
        let icon: CozyTingIcon
        let name: String
        let delayLabel: String
        let runwayLabel: String
        let summary: String
        let accent: Color
        let targetDelayMs: Int
    }

    private var rows: [TierRowModel] {
        let stdMs = LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: wifiFrequencyMHz)
        let stdAUs = LatencyPreset.standard.startupRunwayAUs(wifiFrequencyMHz: wifiFrequencyMHz)
        let balAUs = LatencyPreset.balanced.startupRunwayAUs(wifiFrequencyMHz: wifiFrequencyMHz)
        let lowAUs = LatencyPreset.low.startupRunwayAUs(wifiFrequencyMHz: wifiFrequencyMHz)
        let ultAUs = LatencyPreset.ultra.startupRunwayAUs(wifiFrequencyMHz: wifiFrequencyMHz)
        return [
            TierRowModel(
                id: "standard",
                preset: .standard,
                icon: .latencyStandard,
                name: "Standard (Auto)",
                delayLabel: "\(stdMs)ms",
                runwayLabel: "\(stdAUs) AU",
                summary: "5 GHz 800ms / 2.4 GHz or fallback 1000ms. Best stability across rooms.",
                accent: palette.textSecondary,
                targetDelayMs: 0
            ),
            TierRowModel(
                id: "balanced",
                preset: .balanced,
                icon: .latencyBalanced,
                name: "Balanced",
                delayLabel: "400ms",
                runwayLabel: "\(balAUs) AU",
                summary: "Snappier play/pause & seek response on healthy 5 GHz Wi-Fi.",
                accent: palette.accentBright,
                targetDelayMs: 400
            ),
            TierRowModel(
                id: "low",
                preset: .low,
                icon: .latencyLow,
                name: "Low Latency",
                delayLabel: "250ms",
                runwayLabel: "\(lowAUs) AU",
                summary: "Tight lip-sync for videos on clean 5 GHz / Ethernet.",
                accent: palette.accentBright,
                targetDelayMs: 250
            ),
            TierRowModel(
                id: "ultra",
                preset: .ultra,
                icon: .latencyUltra,
                name: "Ultra",
                delayLabel: "150ms",
                runwayLabel: "\(ultAUs) AU",
                summary: "Minimum buffer; requires near-zero jitter LAN.",
                accent: palette.statusWarning,
                targetDelayMs: 150
            ),
        ]
    }

    public var body: some View {
        let activePreset = LatencyPreset(delayMs: currentDelayMs)
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            HStack {
                Text(title)
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

            Text(subtitle)
                .font(.system(size: DesignTokens.Typography.caption))
                .foregroundStyle(palette.textSecondary)

            VStack(spacing: DesignTokens.Spacing.sm) {
                ForEach(rows) { row in
                    let isSelected = activePreset == row.preset
                    Button {
                        onSelectDelayMs(row.targetDelayMs)
                        onClose()
                    } label: {
                        HStack(alignment: .top, spacing: DesignTokens.Spacing.md) {
                            CozyTingIconView(row.icon, size: 16, weight: .semibold)
                                .foregroundStyle(row.accent)
                                .frame(width: 24, height: 24)

                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(row.name)
                                        .font(.system(size: DesignTokens.Typography.body, weight: .semibold))
                                        .foregroundStyle(palette.textPrimary)
                                    Text(row.delayLabel)
                                        .font(.system(size: DesignTokens.Typography.micro, weight: .semibold, design: .monospaced))
                                        .foregroundStyle(row.accent)
                                    Text("· \(row.runwayLabel)")
                                        .font(.system(size: DesignTokens.Typography.micro, design: .monospaced))
                                        .foregroundStyle(palette.textTertiary)
                                }
                                Text(row.summary)
                                    .font(.system(size: DesignTokens.Typography.caption))
                                    .foregroundStyle(palette.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }

                            Spacer(minLength: 4)

                            if isSelected {
                                CozyTingIconView(.check, size: 13, weight: .bold)
                                    .foregroundStyle(palette.accent)
                            }
                        }
                        .padding(DesignTokens.Spacing.md)
                        .background(
                            RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous)
                                .fill(isSelected ? palette.accentSoftBg : palette.rowIdleBg)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous)
                                .strokeBorder(isSelected ? palette.accent : palette.rowIdleBorder, lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }

            HStack(spacing: DesignTokens.Spacing.sm) {
                Button {
                    onClose()
                    onOpenSettings()
                } label: {
                    Text(customSettingsLabel)
                        .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                        .foregroundStyle(palette.accentBright)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(palette.accentSoftBg, in: RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous))
                }
                .buttonStyle(.plain)

                Spacer()

                Button(action: onClose) {
                    Text(closeLabel)
                        .font(.system(size: DesignTokens.Typography.caption, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(palette.controlSurface, in: RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DesignTokens.Spacing.lg)
        .frame(width: 360)
        .background(palette.panelBg)
    }
}
