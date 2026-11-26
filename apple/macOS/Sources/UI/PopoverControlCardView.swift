import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// `352pt` frosted-glass Menu Bar Popover control card (and responsive embedded body for Main Window)
/// implementing `docs/MACOS_CLIENT_DESIGN.md` §2.2.
public struct PopoverControlCardView: View {
    private enum InlineSubView {
        case main
        case sourcePicker
        case latencyGuide
    }

    @Bindable public var coordinator: CastCoordinator
    public let isEmbeddedInMainWindow: Bool
    public let onOpenMainWindow: () -> Void
    public let onOpenSettings: () -> Void

    @Environment(\.colorScheme) private var systemColorScheme
    @State private var showLatencyGuideSheet: Bool = false
    @State private var showSourcePickerSheet: Bool = false
    @State private var inlineSubView: InlineSubView = .main

    public init(
        coordinator: CastCoordinator,
        isEmbeddedInMainWindow: Bool = false,
        onOpenMainWindow: @escaping () -> Void,
        onOpenSettings: @escaping () -> Void
    ) {
        self.coordinator = coordinator
        self.isEmbeddedInMainWindow = isEmbeddedInMainWindow
        self.onOpenMainWindow = onOpenMainWindow
        self.onOpenSettings = onOpenSettings
    }

    private var lang: AppLanguage { coordinator.appLanguage }

    private func t(_ key: L10nKey) -> String {
        CozyTingL10n.text(key, language: lang)
    }

    private var effectiveScheme: ColorScheme {
        PlatformAdapters.preferredColorScheme(for: coordinator.themeMode) ?? systemColorScheme
    }

    private var palette: DesignTokens.ThemePalette {
        DesignTokens.ThemePalette.palette(for: effectiveScheme)
    }

    private var brandTitle: String {
        BrandStrings.appDisplayName(for: lang)
    }

    private var statusLabel: String {
        if coordinator.isCasting {
            let count = coordinator.selectedSpeakerIds.count
            return coordinator.isPaused
                ? CozyTingL10n.format(.statusPausedCount, language: lang, count)
                : CozyTingL10n.format(.statusCastingCount, language: lang, count)
        }
        return t(.statusIdle)
    }

    public var body: some View {
        ZStack {
            Group {
                if !isEmbeddedInMainWindow && inlineSubView == .sourcePicker {
                    AudioSourcePickerView(
                        coordinator: coordinator,
                        palette: palette,
                        preferredWidth: 352,
                        onClose: { inlineSubView = .main }
                    )
                } else if !isEmbeddedInMainWindow && inlineSubView == .latencyGuide {
                    CozyLatencyGuideSheetView(
                        currentDelayMs: coordinator.delayMs,
                        wifiFrequencyMHz: coordinator.activeWifiFrequencyMHz,
                        language: lang,
                        palette: palette,
                        onSelectDelayMs: { ms in
                            Task { await coordinator.setPlaybackDelay(ms) }
                        },
                        onOpenSettings: {
                            inlineSubView = .main
                            onOpenSettings()
                        },
                        onClose: { inlineSubView = .main }
                    )
                    .frame(width: 352)
                } else {
                    mainCardBody
                }
            }

            if let pending = coordinator.pendingVolumeConfirmation {
                Color.black.opacity(0.45)
                    .ignoresSafeArea()
                    .onTapGesture { coordinator.cancelPendingHighVolume() }

                let msg: String = {
                    if let speakerID = pending.speakerID,
                       let device = coordinator.devices.first(where: { $0.id == speakerID }) {
                        return CozyTingL10n.format(
                            .volumeGuardMsgSpeaker,
                            language: lang,
                            DeviceDisplayFormatter.displayTitle(for: device),
                            pending.currentVolume,
                            pending.targetVolume
                        )
                    }
                    return CozyTingL10n.format(
                        .volumeGuardMsgMaster,
                        language: lang,
                        pending.currentVolume,
                        pending.targetVolume
                    )
                }()

                CozyHighVolumeConfirmView(
                    title: CozyTingL10n.format(.volumeGuardTitle, language: lang, pending.safeLimit),
                    message: msg,
                    keepLabel: CozyTingL10n.format(.volumeGuardKeep, language: lang, pending.currentVolume),
                    confirmLabel: CozyTingL10n.format(.volumeGuardConfirm, language: lang, pending.targetVolume),
                    palette: palette,
                    onCancel: { coordinator.cancelPendingHighVolume() },
                    onConfirm: {
                        Task { await coordinator.confirmPendingHighVolume() }
                    }
                )
                .padding(DesignTokens.Spacing.lg)
            }
        }
        .frame(width: isEmbeddedInMainWindow ? nil : 352)
        .frame(maxWidth: isEmbeddedInMainWindow ? .infinity : 352)
        .background(.ultraThinMaterial)
        .background(palette.panelBg.opacity(0.65))
        .preferredColorScheme(PlatformAdapters.preferredColorScheme(for: coordinator.themeMode))
        .sheet(isPresented: $showLatencyGuideSheet) {
            CozyLatencyGuideSheetView(
                currentDelayMs: coordinator.delayMs,
                wifiFrequencyMHz: coordinator.activeWifiFrequencyMHz,
                language: lang,
                palette: palette,
                onSelectDelayMs: { ms in
                    Task { await coordinator.setPlaybackDelay(ms) }
                },
                onOpenSettings: {
                    showLatencyGuideSheet = false
                    onOpenSettings()
                },
                onClose: { showLatencyGuideSheet = false }
            )
        }
        .sheet(isPresented: $showSourcePickerSheet) {
            AudioSourcePickerView(
                coordinator: coordinator,
                palette: palette,
                preferredWidth: 420,
                onClose: { showSourcePickerSheet = false }
            )
        }
        .sheet(
            isPresented: Binding(
                get: { coordinator.presentedUpdateDecision != nil },
                set: { if !$0 { coordinator.presentedUpdateDecision = nil } }
            )
        ) {
            if let decision = coordinator.presentedUpdateDecision {
                CozyUpdateDetailSheetView(
                    decision: decision,
                    language: lang,
                    palette: palette,
                    onOpenReleasePage: { url in NSWorkspace.shared.open(url) },
                    onOpenDownloadedDMG: { url in
                        NSWorkspace.shared.activateFileViewerSelecting([url])
                    },
                    onSkipVersion: {
                        coordinator.preferences.ignoredUpdateTag = decision.latestTag
                        coordinator.presentedUpdateDecision = nil
                        Task {
                            await coordinator.syncUpdatePresentation(
                                from: UpdateService(preferences: coordinator.preferences)
                            )
                        }
                    },
                    onDismiss: { coordinator.presentedUpdateDecision = nil }
                )
            }
        }
        .background {
            // In-panel keyboard shortcuts for ±5% master volume (zero Accessibility permissions).
            HStack(spacing: 0) {
                Button("") { Task { await coordinator.stepMasterVolume(by: 5) } }
                    .keyboardShortcut(.upArrow, modifiers: [.command])
                Button("") { Task { await coordinator.stepMasterVolume(by: -5) } }
                    .keyboardShortcut(.downArrow, modifiers: [.command])
                Button("") { Task { await coordinator.stepMasterVolume(by: 5) } }
                    .keyboardShortcut("=", modifiers: [])
                Button("") { Task { await coordinator.stepMasterVolume(by: -5) } }
                    .keyboardShortcut("-", modifiers: [])
            }
            .opacity(0)
            .allowsHitTesting(false)
        }
    }

    private var mainCardBody: some View {
        VStack(spacing: DesignTokens.Spacing.md) {
            headerBar
            if let bannerDecision = coordinator.availableUpdateBanner {
                CozyUpdateBannerView(
                    latestTag: bannerDecision.latestTag,
                    language: lang,
                    palette: palette,
                    onView: { coordinator.presentedUpdateDecision = bannerDecision },
                    onDismiss: {
                        coordinator.preferences.dismissedUpdateBannerTag = bannerDecision.latestTag
                        Task {
                            await coordinator.syncUpdatePresentation(
                                from: UpdateService(preferences: coordinator.preferences)
                            )
                        }
                    }
                )
            }
            nowPlayingAndMasterVolumeCard
            audioSourceStrip
            outputDevicesSection
        }
        .padding(DesignTokens.Spacing.md)
    }

    private func openSourcePicker() {
        if isEmbeddedInMainWindow {
            showSourcePickerSheet = true
        } else {
            inlineSubView = .sourcePicker
        }
    }

    private func openLatencyGuide() {
        if isEmbeddedInMainWindow {
            showLatencyGuideSheet = true
        } else {
            inlineSubView = .latencyGuide
        }
    }

    // MARK: - Section 1: Header Bar

    private var headerBar: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            if !isEmbeddedInMainWindow {
                CozyTingIconView(.cozyHouse, size: 17)
                    .foregroundStyle(DesignTokens.Brand.coral)

                Text(brandTitle)
                    .font(.system(size: DesignTokens.Typography.cardTitle, weight: .bold))
                    .foregroundStyle(palette.textPrimary)
            }

            CozyStatusBadge(
                label: statusLabel,
                isActive: coordinator.isCasting,
                isPaused: coordinator.isPaused,
                palette: palette
            )

            Spacer(minLength: 4)

            latencyMenuButton

            if !isEmbeddedInMainWindow {
                Button(action: onOpenMainWindow) {
                    Image(systemName: "macwindow")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 26, height: 26)
                        .background(palette.controlSurface, in: Circle())
                }
                .buttonStyle(.plain)

                Button(action: onOpenSettings) {
                    ZStack(alignment: .topTrailing) {
                        CozyTingIconView(.settings, size: 13, weight: .medium)
                            .foregroundStyle(palette.textSecondary)
                            .frame(width: 26, height: 26)
                            .background(palette.controlSurface, in: Circle())
                        if coordinator.hasUpdateBadge {
                            Circle()
                                .fill(palette.accent)
                                .frame(width: 8, height: 8)
                                .offset(x: 1, y: -1)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var latencyMenuButton: some View {
        CozyLatencyPillMenu(
            delayMs: coordinator.delayMs,
            wifiFrequencyMHz: coordinator.activeWifiFrequencyMHz,
            palette: palette,
            standardLabel: t(.latencyStandard),
            balancedLabel: t(.latencyBalanced),
            lowLabel: t(.latencyLow),
            ultraLabel: t(.latencyUltra),
            customLabel: t(.latencyCustom),
            guideLabel: t(.latencyGuideButton),
            onSelectDelayMs: { ms in
                Task { await coordinator.setPlaybackDelay(ms) }
            },
            onOpenGuide: {
                openLatencyGuide()
            }
        )
    }

    // MARK: - Section 2: Now Playing & Master Volume

    private var nowPlayingAndMasterVolumeCard: some View {
        let np = coordinator.nowPlayingBridge.snapshot
        let sourceRunning = coordinator.isActiveSourceRunning
        return VStack(spacing: DesignTokens.Spacing.sm) {
            HStack(spacing: DesignTokens.Spacing.md) {
                Button {
                    if let bid = coordinator.activeSource?.bundleIdentifier, !bid.isEmpty {
                        coordinator.sourceCatalog.launchApplication(bundleIdentifier: bid)
                    } else {
                        openSourcePicker()
                    }
                } label: {
                    ZStack(alignment: .bottomTrailing) {
                        if let art = coordinator.nowPlayingBridge.artworkImage {
                            Image(nsImage: art)
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                                .frame(width: 58, height: 58)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        } else if let bid = coordinator.activeSource?.bundleIdentifier,
                                  let icon = coordinator.sourceCatalog.icon(for: bid) {
                            Image(nsImage: icon)
                                .resizable()
                                .interpolation(.high)
                                .frame(width: 58, height: 58)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        } else {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(palette.accentSoftBg)
                                .frame(width: 58, height: 58)
                                .overlay(
                                    CozyTingIconView(.musicNote, size: 22, weight: .medium)
                                        .foregroundStyle(DesignTokens.Brand.coral)
                                )
                        }

                        if coordinator.nowPlayingBridge.artworkImage != nil,
                           let bid = coordinator.activeSource?.bundleIdentifier,
                           let badgeIcon = coordinator.sourceCatalog.icon(for: bid) {
                            Image(nsImage: badgeIcon)
                                .resizable()
                                .interpolation(.high)
                                .frame(width: 20, height: 20)
                                .clipShape(Circle())
                                .overlay(Circle().strokeBorder(palette.panelBg, lineWidth: 1.5))
                                .offset(x: 3, y: 3)
                        }
                    }
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: 4) {
                    let primaryTitle: String = {
                        if let np, np.mode == .scriptablePlayer, !np.title.isEmpty {
                            return np.title
                        }
                        if let source = coordinator.activeSource {
                            return coordinator.isCasting
                                ? CozyTingL10n.format(.nowPlayingCastingPrefix, language: lang, source.displayName)
                                : source.displayName
                        }
                        return t(.nowPlayingSelectSource)
                    }()

                    let secondarySubtitle: String = {
                        if coordinator.isCasting, coordinator.isStandbyWaitingForAudio,
                           let name = coordinator.activeSource?.displayName {
                            return CozyTingL10n.format(.sourceWaitingAudioHint, language: lang, name)
                        }
                        if let np, np.mode == .scriptablePlayer {
                            return np.artist.isEmpty ? np.appDisplayName : np.artist
                        }
                        if coordinator.isCasting {
                            return t(coordinator.muteLocalAudio ? .nowPlayingLocalMuted : .nowPlayingLocalKept)
                        }
                        if coordinator.activeSource != nil && !sourceRunning && coordinator.sourceMode == .app {
                            return t(.sourceNotRunningHint)
                        }
                        return t(.nowPlayingReadyLan)
                    }()

                    Text(primaryTitle)
                        .font(.system(size: DesignTokens.Typography.body, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .lineLimit(1)

                    Text(secondarySubtitle)
                        .font(.system(size: DesignTokens.Typography.caption))
                        .foregroundStyle(
                            (coordinator.activeSource != nil && !sourceRunning && !coordinator.isCasting && coordinator.sourceMode == .app)
                                ? palette.statusWarning
                                : palette.textSecondary
                        )
                        .lineLimit(1)

                    HStack(spacing: 12) {
                        Button {
                            coordinator.nowPlayingBridge.previousTrack(source: coordinator.activeSource)
                        } label: {
                            CozyTingIconView(.skipPrev, size: 12, weight: .semibold)
                                .foregroundStyle(palette.textPrimary)
                                .frame(width: 26, height: 26)
                                .background(palette.controlSurface, in: Circle())
                        }
                        .buttonStyle(.plain)

                        Button {
                            let isScriptable = coordinator.activeSource.map {
                                NowPlayingParser.isScriptablePlayer(bundleIdentifier: $0.bundleIdentifier)
                            } ?? false
                            if isScriptable && sourceRunning {
                                coordinator.nowPlayingBridge.playPause(source: coordinator.activeSource)
                            } else if coordinator.isCasting {
                                Task { await coordinator.togglePauseResume() }
                            } else {
                                coordinator.nowPlayingBridge.playPause(source: coordinator.activeSource)
                            }
                        } label: {
                            let playing: Bool = {
                                if let np, np.mode == .scriptablePlayer { return np.isPlaying }
                                return coordinator.isCasting && !coordinator.isPaused
                            }()
                            CozyTingIconView(playing ? .pause : .play, size: 13, weight: .semibold)
                                .foregroundStyle(.white)
                                .frame(width: 30, height: 30)
                                .background(palette.accent, in: Circle())
                        }
                        .buttonStyle(.plain)

                        Button {
                            coordinator.nowPlayingBridge.nextTrack(source: coordinator.activeSource)
                        } label: {
                            CozyTingIconView(.skipNext, size: 12, weight: .semibold)
                                .foregroundStyle(palette.textPrimary)
                                .frame(width: 26, height: 26)
                                .background(palette.controlSurface, in: Circle())
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.top, 2)
                }

                Spacer(minLength: 0)
            }

            if let np, np.mode == .scriptablePlayer, np.canSeek, np.durationSeconds > 0 {
                VStack(spacing: 3) {
                    GeometryReader { geo in
                        let ratio = min(max(np.positionSeconds / np.durationSeconds, 0), 1)
                        ZStack(alignment: .leading) {
                            Capsule()
                                .fill(palette.trackIdle)
                                .frame(height: 4)
                            Capsule()
                                .fill(palette.accent)
                                .frame(width: max(4, geo.size.width * ratio), height: 4)
                        }
                        .frame(maxHeight: .infinity)
                        .contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onEnded { value in
                                    let frac = min(max(value.location.x / max(geo.size.width, 1), 0), 1)
                                    coordinator.nowPlayingBridge.seek(
                                        toSeconds: frac * np.durationSeconds,
                                        source: coordinator.activeSource
                                    )
                                }
                        )
                    }
                    .frame(height: 12)

                    HStack {
                        Text(NowPlayingParser.formatTime(np.positionSeconds))
                        Spacer()
                        Text(NowPlayingParser.formatTime(np.durationSeconds))
                    }
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(palette.textTertiary)
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
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: DesignTokens.Spacing.sm) {
                Text(t(.sourceSectionTitle))
                    .font(.system(size: DesignTokens.Typography.sectionTitle, weight: .semibold))
                    .foregroundStyle(palette.textSecondary)

                if coordinator.lastSourceSwitchWasAuto {
                    Text(t(.sourceAutoBadge))
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(palette.accentBright)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(palette.accentSoftBg, in: Capsule())
                }

                Spacer()

                Button {
                    openSourcePicker()
                } label: {
                    HStack(spacing: 3) {
                        Text(t(.commonManage))
                            .font(.system(size: DesignTokens.Typography.micro, weight: .medium))
                        CozyTingIconView(.chevronRight, size: 9, weight: .semibold)
                    }
                    .foregroundStyle(palette.textSecondary)
                }
                .buttonStyle(.plain)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    if let active = coordinator.activeSource,
                       !coordinator.favoriteSources.contains(where: {
                           $0.bundleIdentifier.caseInsensitiveCompare(active.bundleIdentifier) == .orderedSame
                       }) {
                        sourceChip(
                            bundleIdentifier: active.bundleIdentifier,
                            name: active.displayName,
                            selected: true
                        )
                    }

                    ForEach(coordinator.favoriteSources, id: \.bundleIdentifier) { fav in
                        let name = coordinator.sourceCatalog.displayName(for: fav.bundleIdentifier)
                        let selected = coordinator.activeSource?.bundleIdentifier
                            .caseInsensitiveCompare(fav.bundleIdentifier) == .orderedSame
                        sourceChip(
                            bundleIdentifier: fav.bundleIdentifier,
                            name: name,
                            selected: selected
                        )
                    }

                    if coordinator.favoriteSources.isEmpty && coordinator.activeSource == nil {
                        Button {
                            openSourcePicker()
                        } label: {
                            Text(t(.sourceChooseApp))
                                .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                                .foregroundStyle(palette.accentBright)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(palette.accentSoftBg, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.horizontal, 4)
    }

    @ViewBuilder
    private func sourceChip(bundleIdentifier: String, name: String, selected: Bool) -> some View {
        let running = coordinator.sourceCatalog.isRunning(bundleIdentifier: bundleIdentifier)
        Button {
            Task {
                await coordinator.selectAudioSource(
                    AudioSourceSelection(bundleIdentifier: bundleIdentifier, displayName: name),
                    manual: true
                )
            }
        } label: {
            HStack(spacing: 5) {
                if let icon = coordinator.sourceCatalog.icon(for: bundleIdentifier) {
                    Image(nsImage: icon)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 15, height: 15)
                        .clipShape(RoundedRectangle(cornerRadius: 3.5, style: .continuous))
                } else {
                    CozyTingIconView(.musicNote, size: 11)
                }
                Text(name)
                    .font(.system(size: DesignTokens.Typography.caption, weight: selected ? .semibold : .medium))
                    .lineLimit(1)
                if running {
                    Circle()
                        .fill(palette.statusSuccess)
                        .frame(width: 5, height: 5)
                }
            }
            .foregroundStyle(selected ? palette.accentBright : palette.textPrimary)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(
                Capsule().fill(selected ? palette.accentSoftBg : palette.controlSurface)
            )
            .overlay(
                Capsule().strokeBorder(selected ? palette.accent : Color.clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Section 4: Output Devices

    private var outputDevicesSection: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
            HStack(spacing: DesignTokens.Spacing.sm) {
                Text(t(.outputDevicesTitle))
                    .font(.system(size: DesignTokens.Typography.sectionTitle, weight: .semibold))
                    .foregroundStyle(palette.textSecondary)

                Spacer()

                if coordinator.canRestoreLastSelection {
                    let count = coordinator.restorableLastSpeakerCount
                    Button {
                        Task { await coordinator.restoreLastSelectedSpeakers() }
                    } label: {
                        Text(count > 1 ? CozyTingL10n.format(.restoreLastMulti, language: lang, count) : t(.restoreLast))
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

            if let notice = coordinator.errorNotice {
                errorNoticeCard(notice)
            }

            ScrollView {
                LazyVStack(spacing: DesignTokens.Spacing.sm) {
                    ForEach(coordinator.visibleDevices) { device in
                        let stats = coordinator.receiverStatsByIP[device.id]
                        let statusDesc: String? = {
                            guard device.selected, device.kind != .thisDevice, let stats else { return nil }
                            switch stats.uiState {
                            case .connecting: return t(.statusConnecting)
                            case .reconnecting: return t(.statusReconnecting)
                            case .error: return stats.error ?? t(.statusError)
                            case .streaming: return nil
                            }
                        }()

                        let title = device.kind == .thisDevice
                            ? t(.thisDeviceName)
                            : DeviceDisplayFormatter.displayTitle(for: device)

                        CozyDeviceRowCard(
                            device: device,
                            title: title,
                            subtitle: device.kind == .thisDevice ? t(.commonMacSpeakers) : nil,
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
            .frame(
                minHeight: isEmbeddedInMainWindow ? 180 : 170,
                idealHeight: isEmbeddedInMainWindow ? 260 : 210,
                maxHeight: isEmbeddedInMainWindow ? .infinity : 250
            )
        }
    }

    @ViewBuilder
    private func errorNoticeCard(_ notice: CastErrorNotice) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 6) {
                Text(notice.message)
                    .font(.system(size: DesignTokens.Typography.micro, weight: .medium))
                    .foregroundStyle(palette.statusError)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer(minLength: 4)

                Button {
                    coordinator.clearError()
                } label: {
                    CozyTingIconView(.close, size: 9, weight: .semibold)
                        .foregroundStyle(palette.textTertiary)
                        .frame(width: 18, height: 18)
                }
                .buttonStyle(.plain)
            }

            if let buttonTitle = actionTitle(for: notice.action) {
                Button {
                    Task { await coordinator.performErrorAction(notice.action) }
                } label: {
                    Text(buttonTitle)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(palette.accent, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(palette.statusError.opacity(0.11), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(palette.statusError.opacity(0.28), lineWidth: 1)
        )
    }

    private func actionTitle(for action: CastErrorNotice.Action) -> String? {
        switch action {
        case .none:
            return nil
        case .launchAndCast:
            return t(.errLaunchAndCast)
        case .openAudioPermission:
            return t(.settingsOpenSystemSettings)
        case .openFirewallSettings:
            return t(.errOpenFirewallSettings)
        case .openLocalNetworkSettings:
            return t(.errOpenLocalNetworkSettings)
        case .retryDiscovery:
            return t(.errRetry)
        }
    }
}
