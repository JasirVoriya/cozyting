import CozyTingCoreKit
import SwiftUI

// MARK: - Style B Sleek 4pt Volume Track Slider

/// Cross-platform Style B thin-track volume slider (4pt track, 14pt thumb).
///
/// Maintains local drag value so 1 Hz L1 stats polling never interrupts user slider gestures,
/// and tints the active fill amber (`statusWarning`) when above `safeLimit` while guard is enabled.
public struct CozyVolumeTrackSlider: View {
    public let value: Int
    public let safeLimit: Int
    public let guardEnabled: Bool
    public let onSelectedSurface: Bool
    public let palette: DesignTokens.ThemePalette
    public let onCommit: (Int) -> Void

    @State private var dragValue: Int?

    public init(
        value: Int,
        safeLimit: Int = VolumeGuardPolicy.defaultSafeLimit,
        guardEnabled: Bool = true,
        onSelectedSurface: Bool = false,
        palette: DesignTokens.ThemePalette,
        onCommit: @escaping (Int) -> Void
    ) {
        self.value = min(max(value, 0), 100)
        self.safeLimit = safeLimit
        self.guardEnabled = guardEnabled
        self.onSelectedSurface = onSelectedSurface
        self.palette = palette
        self.onCommit = onCommit
    }

    private var displayedValue: Int {
        dragValue ?? value
    }

    private var isAboveSafeLimit: Bool {
        guardEnabled && displayedValue > safeLimit
    }

    private var activeTrackColor: Color {
        if isAboveSafeLimit {
            return palette.statusWarning
        }
        return onSelectedSurface ? palette.accent : palette.accentBright
    }

    private var idleTrackColor: Color {
        onSelectedSurface ? palette.trackOnSelected : palette.trackIdle
    }

    private var labelColor: Color {
        if isAboveSafeLimit {
            return palette.statusWarning
        }
        return onSelectedSurface ? palette.onSelectedSecondary : palette.textSecondary
    }

    public var body: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            CozyTingIconView(.volume, size: 13, weight: .medium)
                .foregroundStyle(labelColor)

            GeometryReader { geo in
                let width = max(geo.size.width, 1)
                let fraction = CGFloat(displayedValue) / 100.0
                let thumbDiameter: CGFloat = 13
                let thumbX = min(max(fraction * width, thumbDiameter / 2), width - thumbDiameter / 2)

                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(idleTrackColor)
                        .frame(height: 4)

                    Capsule()
                        .fill(activeTrackColor)
                        .frame(width: max(4, fraction * width), height: 4)

                    if guardEnabled && safeLimit > 0 && safeLimit < 100 {
                        let markerX = CGFloat(safeLimit) / 100.0 * width
                        Rectangle()
                            .fill(palette.statusWarning.opacity(0.65))
                            .frame(width: 1.5, height: 8)
                            .position(x: markerX, y: geo.size.height / 2)
                    }

                    Circle()
                        .fill(Color.white)
                        .shadow(color: Color.black.opacity(0.22), radius: 2, y: 1)
                        .overlay(
                            Circle()
                                .strokeBorder(activeTrackColor.opacity(0.75), lineWidth: 1.5)
                        )
                        .frame(width: thumbDiameter, height: thumbDiameter)
                        .position(x: thumbX, y: geo.size.height / 2)
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { gesture in
                            let ratio = min(max(gesture.location.x / width, 0), 1)
                            dragValue = Int((ratio * 100).rounded())
                        }
                        .onEnded { gesture in
                            let ratio = min(max(gesture.location.x / width, 0), 1)
                            let finalVal = Int((ratio * 100).rounded())
                            dragValue = nil
                            onCommit(finalVal)
                        }
                )
            }
            .frame(height: 20)

            Text("\(displayedValue)%")
                .font(.system(size: DesignTokens.Typography.micro, weight: .medium, design: .monospaced))
                .foregroundStyle(labelColor)
                .frame(width: 34, alignment: .trailing)
        }
    }
}

// MARK: - Style B Status Pill

public struct CozyStatusBadge: View {
    public let label: String
    public let isActive: Bool
    public let isPaused: Bool
    public let palette: DesignTokens.ThemePalette

    public init(
        label: String,
        isActive: Bool,
        isPaused: Bool = false,
        palette: DesignTokens.ThemePalette
    ) {
        self.label = label
        self.isActive = isActive
        self.isPaused = isPaused
        self.palette = palette
    }

    private var dotColor: Color {
        if !isActive { return palette.textTertiary }
        return isPaused ? palette.statusWarning : palette.statusSuccess
    }

    public var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(dotColor)
                .frame(width: 6, height: 6)
            Text(label)
                .font(.system(size: DesignTokens.Typography.micro, weight: .medium))
                .foregroundStyle(isActive ? palette.textPrimary : palette.textSecondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3.5)
        .background(palette.controlSurface, in: Capsule())
    }
}

// MARK: - Style B Output Device Row Card

/// Shared Style B output device row (inverted high-contrast surface when selected + inline volume slider).
public struct CozyDeviceRowCard: View {
    public let device: Device
    public let title: String
    public let subtitle: String?
    public let latencyBadge: String?
    public let statusText: String?
    public let protocolBadge: String?
    public let volume: Int
    public let safeLimit: Int
    public let guardEnabled: Bool
    public let palette: DesignTokens.ThemePalette
    public let onTap: () -> Void
    public let onVolumeCommit: ((Int) -> Void)?

    public init(
        device: Device,
        title: String,
        subtitle: String? = nil,
        latencyBadge: String? = nil,
        statusText: String? = nil,
        protocolBadge: String? = nil,
        volume: Int = 30,
        safeLimit: Int = VolumeGuardPolicy.defaultSafeLimit,
        guardEnabled: Bool = true,
        palette: DesignTokens.ThemePalette,
        onTap: @escaping () -> Void,
        onVolumeCommit: ((Int) -> Void)? = nil
    ) {
        self.device = device
        self.title = title
        self.subtitle = subtitle
        self.latencyBadge = latencyBadge
        self.statusText = statusText
        self.protocolBadge = protocolBadge
        self.volume = volume
        self.safeLimit = safeLimit
        self.guardEnabled = guardEnabled
        self.palette = palette
        self.onTap = onTap
        self.onVolumeCommit = onVolumeCommit
    }

    private var isSelected: Bool { device.selected }
    private var primaryColor: Color { isSelected ? palette.onSelectedPrimary : palette.textPrimary }
    private var secondaryColor: Color { isSelected ? palette.onSelectedSecondary : palette.textSecondary }

    private var icon: CozyTingIcon {
        switch device.kind {
        case .thisDevice: return .phone
        case .tv: return .tv
        case .speaker: return .speaker
        }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
            Button(action: onTap) {
                HStack(spacing: DesignTokens.Spacing.sm) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(isSelected ? palette.selectedPillBg : palette.controlSurface)
                            .frame(width: 28, height: 28)
                        CozyTingIconView(icon, size: 14, weight: .medium)
                            .foregroundStyle(isSelected ? palette.accent : palette.textSecondary)
                    }

                    VStack(alignment: .leading, spacing: 1.5) {
                        Text(title)
                            .font(.system(size: DesignTokens.Typography.deviceTitle, weight: isSelected ? .semibold : .medium))
                            .foregroundStyle(device.castable ? primaryColor : palette.textTertiary)
                            .lineLimit(1)

                        if let statusText, !statusText.isEmpty {
                            Text(statusText)
                                .font(.system(size: DesignTokens.Typography.micro))
                                .foregroundStyle(secondaryColor)
                                .lineLimit(1)
                        } else if let subtitle, !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.system(size: DesignTokens.Typography.micro))
                                .foregroundStyle(secondaryColor)
                                .lineLimit(1)
                        }
                    }

                    Spacer(minLength: 4)

                    if let latencyBadge, !latencyBadge.isEmpty {
                        Text(latencyBadge)
                            .font(.system(size: 10.5, weight: .medium, design: .monospaced))
                            .foregroundStyle(secondaryColor)
                    }

                    if let protocolBadge, !protocolBadge.isEmpty {
                        Text(protocolBadge)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(isSelected ? palette.selectedPillText : palette.textSecondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(
                                isSelected ? palette.selectedPillBg : palette.controlSurface,
                                in: Capsule()
                            )
                    }

                    ZStack {
                        Circle()
                            .strokeBorder(
                                isSelected ? palette.accent : palette.textTertiary.opacity(0.45),
                                lineWidth: 1.5
                            )
                            .background(
                                Circle().fill(isSelected ? palette.accent : Color.clear)
                            )
                            .frame(width: 18, height: 18)

                        if isSelected {
                            CozyTingIconView(.check, size: 10, weight: .bold)
                                .foregroundStyle(Color.white)
                        }
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isSelected, device.kind != .thisDevice, let onVolumeCommit {
                CozyVolumeTrackSlider(
                    value: volume,
                    safeLimit: safeLimit,
                    guardEnabled: guardEnabled,
                    onSelectedSurface: true,
                    palette: palette,
                    onCommit: onVolumeCommit
                )
                .padding(.top, 2)
                .padding(.leading, 36)
            }
        }
        .padding(.horizontal, DesignTokens.Spacing.md)
        .padding(.vertical, 9)
        .background(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous)
                .fill(isSelected ? palette.rowSelectedBg : palette.rowIdleBg)
        )
        .overlay(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous)
                .strokeBorder(isSelected ? palette.rowSelectedBorder : palette.rowIdleBorder, lineWidth: 1)
        )
        .opacity(device.castable || device.kind == .thisDevice ? 1.0 : 0.62)
    }
}

// MARK: - Style B High-Volume Confirmation Modal Card

public struct CozyHighVolumeConfirmView: View {
    public let title: String
    public let message: String
    public let keepLabel: String
    public let confirmLabel: String
    public let palette: DesignTokens.ThemePalette
    public let onCancel: () -> Void
    public let onConfirm: () -> Void

    public init(
        title: String,
        message: String,
        keepLabel: String,
        confirmLabel: String,
        palette: DesignTokens.ThemePalette,
        onCancel: @escaping () -> Void,
        onConfirm: @escaping () -> Void
    ) {
        self.title = title
        self.message = message
        self.keepLabel = keepLabel
        self.confirmLabel = confirmLabel
        self.palette = palette
        self.onCancel = onCancel
        self.onConfirm = onConfirm
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            HStack(spacing: DesignTokens.Spacing.sm) {
                Circle()
                    .fill(palette.statusWarning.opacity(0.18))
                    .frame(width: 28, height: 28)
                    .overlay(
                        CozyTingIconView(.volume, size: 14, weight: .semibold)
                            .foregroundStyle(palette.statusWarning)
                    )
                Text(title)
                    .font(.system(size: DesignTokens.Typography.cardTitle, weight: .semibold))
                    .foregroundStyle(palette.textPrimary)
            }

            Text(message)
                .font(.system(size: DesignTokens.Typography.caption))
                .foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: DesignTokens.Spacing.sm) {
                Button(action: onCancel) {
                    Text(keepLabel)
                        .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                        .background(palette.controlSurface, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                }
                .buttonStyle(.plain)

                Button(action: onConfirm) {
                    Text(confirmLabel)
                        .font(.system(size: DesignTokens.Typography.caption, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                        .background(palette.statusWarning, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DesignTokens.Spacing.lg)
        .background(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                .fill(palette.panelBg)
        )
        .overlay(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                .strokeBorder(palette.statusWarning.opacity(0.45), lineWidth: 1)
        )
    }
}
