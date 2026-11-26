import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// `352pt` frosted-glass Menu Bar Popover control card implementing `docs/MACOS_CLIENT_DESIGN.md` §2.2.
public struct PopoverControlCardView: View {
    @Bindable public var coordinator: CastCoordinator
    public let onOpenMainWindow: () -> Void
    public let onOpenSettings: () -> Void

    @Environment(\.colorScheme) private var systemColorScheme
    @State private var themeMode: ThemeMode = .system

    public init(
        coordinator: CastCoordinator,
        onOpenMainWindow: @escaping () -> Void,
        onOpenSettings: @escaping () -> Void
    ) {
        self.coordinator = coordinator
        self.onOpenMainWindow = onOpenMainWindow
        self.onOpenSettings = onOpenSettings
    }

    private var effectiveScheme: ColorScheme {
        PlatformAdapters.preferredColorScheme(for: themeMode) ?? systemColorScheme
    }

    private var palette: DesignTokens.ThemePalette {
        DesignTokens.ThemePalette.palette(for: effectiveScheme)
    }

    private var brandTitle: String {
        BrandStrings.appDisplayName(for: coordinator.preferences.appLanguage)
    }

    private var statusLabel: String {
        if coordinator.isCasting {
            let count = coordinator.selectedSpeakerIds.count
            return coordinator.isPaused ? "Paused · \(count)" : "Casting · \(count)"
        }
        return "Idle"
    }

    public var body: some View {
        ZStack {
            VStack(spacing: DesignTokens.Spacing.md) {
                headerBar
                nowPlayingAndMasterVolumeCard
                audioSourceStrip
                outputDevicesSection
            }
            .padding(DesignTokens.Spacing.md)

            if let pending = coordinator.pendingVolumeConfirmation {
                Color.black.opacity(0.45)
                    .ignoresSafeArea()
                    .onTapGesture { coordinator.cancelPendingHighVolume() }

                CozyHighVolumeConfirmView(
                    title: "Volume Above Safe Limit (\(pending.safeLimit)%)",
                    message: "Raise volume from \(pending.currentVolume)% to \(pending.targetVolume)%?",
                    keepLabel: "Keep \(pending.currentVolume)%",
                    confirmLabel: "Set to \(pending.targetVolume)%",
                    palette: palette,
                    onCancel: { coordinator.cancelPendingHighVolume() },
                    onConfirm: {
                        Task { await coordinator.confirmPendingHighVolume() }
                    }
                )
                .padding(DesignTokens.Spacing.lg)
            }
        }
        .frame(width: 352)
        .background(.ultraThinMaterial)
        .background(palette.panelBg.opacity(0.65))
        .preferredColorScheme(PlatformAdapters.preferredColorScheme(for: themeMode))
        .onAppear {
            themeMode = coordinator.preferences.themeMode
        }
    }

    // MARK: - Section 1: Header Bar

    private var headerBar: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            CozyTingIconView(.cozyHouse, size: 17)
                .foregroundStyle(DesignTokens.Brand.coral)

            Text(brandTitle)
                .font(.system(size: DesignTokens.Typography.cardTitle, weight: .bold))
                .foregroundStyle(palette.textPrimary)

            CozyStatusBadge(
                label: statusLabel,
                isActive: coordinator.isCasting,
                isPaused: coordinator.isPaused,
                palette: palette
            )

            Spacer(minLength: 4)

            latencyMenuButton

            Button(action: onOpenMainWindow) {
                Image(systemName: "macwindow")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(palette.textSecondary)
                    .frame(width: 26, height: 26)
                    .background(palette.controlSurface, in: Circle())
            }
            .buttonStyle(.plain)

            Button(action: onOpenSettings) {
                CozyTingIconView(.settings, size: 13, weight: .medium)
                    .foregroundStyle(palette.textSecondary)
                    .frame(width: 26, height: 26)
                    .background(palette.controlSurface, in: Circle())
            }
            .buttonStyle(.plain)
        }
    }

    private var latencyMenuButton: some View {
        Menu {
            Button("Standard (\(LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: coordinator.activeWifiFrequencyMHz))ms)") {
                Task { await coordinator.setPlaybackDelay(0) }
            }
            Button("Balanced (400ms)") {
                Task { await coordinator.setPlaybackDelay(400) }
            }
            Button("Low (250ms)") {
                Task { await coordinator.setPlaybackDelay(250) }
            }
            Button("Ultra (150ms)") {
                Task { await coordinator.setPlaybackDelay(150) }
            }
        } label: {
            HStack(spacing: 3) {
                Text("\(coordinator.effectiveDelayMs)ms")
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                CozyTingIconView(.chevronDown, size: 9, weight: .semibold)
            }
            .foregroundStyle(palette.textPrimary)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(palette.controlSurface, in: Capsule())
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
    }

    // MARK: - Section 2: Now Playing & Master Volume

    private var nowPlayingAndMasterVolumeCard: some View {
        VStack(spacing: DesignTokens.Spacing.sm) {
            HStack(spacing: DesignTokens.Spacing.md) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(palette.accentSoftBg)
                    .frame(width: 52, height: 52)
                    .overlay(
                        CozyTingIconView(.musicNote, size: 22, weight: .medium)
                            .foregroundStyle(DesignTokens.Brand.coral)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text(coordinator.activeSource?.displayName ?? "Select Audio Source")
                        .font(.system(size: DesignTokens.Typography.body, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .lineLimit(1)

                    Text(coordinator.isCasting ? (coordinator.muteLocalAudio ? "Local app muted" : "Local audio kept") : "Ready to cast over LAN")
                        .font(.system(size: DesignTokens.Typography.caption))
                        .foregroundStyle(palette.textSecondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 4)

                if coordinator.isCasting {
                    Button {
                        Task { await coordinator.togglePauseResume() }
                    } label: {
                        CozyTingIconView(coordinator.isPaused ? .play : .pause, size: 14, weight: .semibold)
                            .foregroundStyle(palette.textPrimary)
                            .frame(width: 30, height: 30)
                            .background(palette.controlSurface, in: Circle())
                    }
                    .buttonStyle(.plain)
                }
            }

            CozyVolumeTrackSlider(
                value: coordinator.masterVolume,
                safeLimit: coordinator.volumeGuardSafeLimit,
                guardEnabled: coordinator.volumeGuardEnabled,
                onSelectedSurface: false,
                palette: palette
            ) { newVol in
                Task { await coordinator.requestMasterVolume(newVol) }
            }
        }
        .padding(DesignTokens.Spacing.md)
        .background(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                .fill(palette.rowIdleBg.opacity(0.85))
        )
        .overlay(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                .strokeBorder(palette.rowIdleBorder, lineWidth: 1)
        )
    }

    // MARK: - Section 3: Audio Source Strip

    private var audioSourceStrip: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            Text("Source")
                .font(.system(size: DesignTokens.Typography.sectionTitle, weight: .semibold))
                .foregroundStyle(palette.textSecondary)

            if let source = coordinator.activeSource {
                HStack(spacing: 4) {
                    CozyTingIconView(.musicNote, size: 11)
                    Text(source.displayName)
                        .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                        .lineLimit(1)
                }
                .foregroundStyle(palette.textPrimary)
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(palette.accentSoftBg, in: Capsule())
            } else {
                Text("None selected")
                    .font(.system(size: DesignTokens.Typography.caption))
                    .foregroundStyle(palette.textTertiary)
            }

            Spacer()

            Button(action: onOpenMainWindow) {
                CozyTingIconView(.chevronRight, size: 11, weight: .semibold)
                    .foregroundStyle(palette.textSecondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 4)
    }

    // MARK: - Section 4: Output Devices

    private var outputDevicesSection: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
            HStack(spacing: DesignTokens.Spacing.sm) {
                Text("Output Devices")
                    .font(.system(size: DesignTokens.Typography.sectionTitle, weight: .semibold))
                    .foregroundStyle(palette.textSecondary)

                Spacer()

                if coordinator.canRestoreLastSelection {
                    Button {
                        Task { await coordinator.restoreLastSelectedSpeakers() }
                    } label: {
                        Text("Restore Last")
                            .font(.system(size: DesignTokens.Typography.micro, weight: .medium))
                            .foregroundStyle(palette.accentBright)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(palette.accentSoftBg, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }

                Button {
                    Task { await coordinator.discoverDevices(manual: true) }
                } label: {
                    CozyTingIconView(.refresh, size: 12, weight: .medium)
                        .foregroundStyle(palette.textSecondary)
                        .rotationEffect(.degrees(coordinator.isDiscovering ? 360 : 0))
                        .animation(
                            coordinator.isDiscovering ? .linear(duration: 1).repeatForever(autoreverses: false) : .default,
                            value: coordinator.isDiscovering
                        )
                }
                .buttonStyle(.plain)
                .disabled(coordinator.isDiscovering)
            }
            .padding(.horizontal, 4)

            if let error = coordinator.lastError, !error.isEmpty {
                Text(error)
                    .font(.system(size: DesignTokens.Typography.micro))
                    .foregroundStyle(palette.statusError)
                    .lineLimit(2)
                    .padding(.horizontal, 4)
            }

            ScrollView {
                LazyVStack(spacing: DesignTokens.Spacing.sm) {
                    ForEach(coordinator.visibleDevices) { device in
                        let stats = coordinator.receiverStatsByIP[device.id]
                        let statusDesc: String? = {
                            guard device.selected, device.kind != .thisDevice, let stats else { return nil }
                            switch stats.uiState {
                            case .connecting: return "Connecting…"
                            case .reconnecting: return "Reconnecting…"
                            case .error: return stats.error ?? "Error"
                            case .streaming: return nil
                            }
                        }()

                        CozyDeviceRowCard(
                            device: device,
                            title: DeviceDisplayFormatter.displayTitle(for: device),
                            subtitle: device.kind == .thisDevice ? "Mac Speakers" : nil,
                            latencyBadge: DeviceDisplayFormatter.formattedLatency(for: stats),
                            statusText: statusDesc,
                            protocolBadge: device.protocol?.rawValue == "MIPLAY" ? BrandStrings.protocolName : device.protocol?.rawValue,
                            volume: coordinator.speakerVolumes[device.id] ?? coordinator.masterVolume,
                            safeLimit: coordinator.volumeGuardSafeLimit,
                            guardEnabled: coordinator.volumeGuardEnabled,
                            palette: palette,
                            onTap: {
                                Task {
                                    if device.kind == .thisDevice {
                                        await coordinator.selectThisDevice()
                                    } else {
                                        await coordinator.toggleSpeaker(id: device.id)
                                    }
                                }
                            },
                            onVolumeCommit: device.kind == .thisDevice ? nil : { vol in
                                Task { await coordinator.requestSpeakerVolume(speakerID: device.id, targetVolume: vol) }
                            }
                        )
                    }
                }
            }
            .frame(maxHeight: 230)
        }
    }
}
