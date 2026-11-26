import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Native macOS Control Center frosted-glass control panel (`NSPopover` & translucent `MainWindow`).
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
            if !isEmbeddedInMainWindow {
                VisualEffectBackground(
                    material: .popover,
                    blendingMode: .behindWindow,
                    state: .active
                )
                .ignoresSafeArea()
            }

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
                Color.black.opacity(0.35)
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

                VStack(alignment: .leading, spacing: 12) {
                    Label(
                        CozyTingL10n.format(.volumeGuardTitle, language: lang, pending.safeLimit),
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .font(.headline)
                    .foregroundStyle(.orange)

                    Text(msg)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    HStack {
                        Spacer()
                        Button(CozyTingL10n.format(.volumeGuardKeep, language: lang, pending.currentVolume)) {
                            coordinator.cancelPendingHighVolume()
                        }
                        .buttonStyle(.bordered)

                        Button(CozyTingL10n.format(.volumeGuardConfirm, language: lang, pending.targetVolume)) {
                            Task { await coordinator.confirmPendingHighVolume() }
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.orange)
                    }
                }
                .padding(16)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.5)
                )
                .shadow(color: .black.opacity(0.25), radius: 16, y: 6)
                .padding(18)
            }
        }
        .frame(width: isEmbeddedInMainWindow ? nil : 352)
        .frame(maxWidth: isEmbeddedInMainWindow ? .infinity : 352)
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
        VStack(spacing: 12) {
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

            nowPlayingAndMasterVolumeModule
            audioSourceSection
            outputDevicesSection
        }
        .padding(14)
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
        HStack(spacing: 8) {
            if !isEmbeddedInMainWindow {
                CozyTingIconView(.cozyHouse, size: 16)
                    .foregroundStyle(DesignTokens.Brand.coral)

                Text(brandTitle)
                    .font(.headline)
                    .foregroundStyle(.primary)
            }

            // Native Control Center status pill
            HStack(spacing: 5) {
                Circle()
                    .fill(statusDotColor)
                    .frame(width: 6, height: 6)
                Text(statusLabel)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 3.5)
            .background(.quaternary.opacity(0.6), in: Capsule())

            Spacer(minLength: 4)

            latencyMenuButton

            if !isEmbeddedInMainWindow {
                Button(action: onOpenMainWindow) {
                    Image(systemName: "macwindow")
                        .font(.system(size: 12, weight: .medium))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                Button(action: onOpenSettings) {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "gearshape")
                            .font(.system(size: 12, weight: .medium))
                        if coordinator.hasUpdateBadge {
                            Circle()
                                .fill(Color.accentColor)
                                .frame(width: 6, height: 6)
                                .offset(x: 3, y: -3)
                        }
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
    }

    private var statusDotColor: Color {
        if !coordinator.isCasting { return .secondary.opacity(0.6) }
        return coordinator.isPaused ? .orange : .green
    }

    private var latencyMenuButton: some View {
        let preset = coordinator.currentLatencyPreset
        let effMs = coordinator.effectiveDelayMs
        let label: String = {
            switch preset {
            case .standard: return "\(effMs)ms"
            case .balanced: return "\(t(.latencyBalanced)) 400ms"
            case .low: return "\(t(.latencyLow)) 250ms"
            case .ultra: return "\(t(.latencyUltra)) 150ms"
            case .custom(let ms): return "\(ms)ms"
            }
        }()

        return Menu {
            let stdMs = LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: coordinator.activeWifiFrequencyMHz)
            latencyMenuItem(title: t(.latencyStandard), ms: stdMs, selected: preset == .standard, target: 0)
            latencyMenuItem(title: t(.latencyBalanced), ms: 400, selected: preset == .balanced, target: 400)
            latencyMenuItem(title: t(.latencyLow), ms: 250, selected: preset == .low, target: 250)
            latencyMenuItem(title: t(.latencyUltra), ms: 150, selected: preset == .ultra, target: 150)

            if case let .custom(ms) = preset {
                Divider()
                Button {
                    Task { await coordinator.setPlaybackDelay(ms) }
                } label: {
                    Label("\(t(.latencyCustom)) · \(ms)ms", systemImage: "checkmark")
                }
            }

            Divider()

            Button {
                openLatencyGuide()
            } label: {
                Label(t(.latencyGuideButton), systemImage: "info.circle")
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "waveform.path.ecg")
                    .font(.system(size: 10, weight: .semibold))
                Text(label)
                    .font(.caption.monospacedDigit().weight(.medium))
            }
            .foregroundStyle(preset == .ultra ? .orange : .primary)
        }
        .menuStyle(.borderedButton)
        .controlSize(.small)
        .fixedSize()
    }

    @ViewBuilder
    private func latencyMenuItem(title: String, ms: Int, selected: Bool, target: Int) -> some View {
        Button {
            Task { await coordinator.setPlaybackDelay(target) }
        } label: {
            if selected {
                Label("\(title)  \(ms)ms", systemImage: "checkmark")
            } else {
                Text("\(title)  \(ms)ms")
            }
        }
    }

    // MARK: - Section 2: Native Glass Now Playing & Master Volume Module

    private var nowPlayingAndMasterVolumeModule: some View {
        let np = coordinator.nowPlayingBridge.snapshot
        let sourceRunning = coordinator.isActiveSourceRunning

        return VStack(spacing: 10) {
            HStack(spacing: 12) {
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
                                .frame(width: 52, height: 52)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        } else if let bid = coordinator.activeSource?.bundleIdentifier,
                                  let icon = coordinator.sourceCatalog.icon(for: bid) {
                            Image(nsImage: icon)
                                .resizable()
                                .interpolation(.high)
                                .frame(width: 52, height: 52)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        } else {
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(Color.accentColor.opacity(0.14))
                                .frame(width: 52, height: 52)
                                .overlay(
                                    Image(systemName: "music.note")
                                        .font(.system(size: 20, weight: .medium))
                                        .foregroundStyle(Color.accentColor)
                                )
                        }

                        if coordinator.nowPlayingBridge.artworkImage != nil,
                           let bid = coordinator.activeSource?.bundleIdentifier,
                           let badgeIcon = coordinator.sourceCatalog.icon(for: bid) {
                            Image(nsImage: badgeIcon)
                                .resizable()
                                .interpolation(.high)
                                .frame(width: 18, height: 18)
                                .clipShape(Circle())
                                .shadow(radius: 2)
                                .offset(x: 3, y: 3)
                        }
                    }
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: 3) {
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
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    Text(secondarySubtitle)
                        .font(.caption)
                        .foregroundStyle(
                            (coordinator.activeSource != nil && !sourceRunning && !coordinator.isCasting && coordinator.sourceMode == .app)
                                ? .orange
                                : .secondary
                        )
                        .lineLimit(1)

                    HStack(spacing: 8) {
                        Button {
                            coordinator.nowPlayingBridge.previousTrack(source: coordinator.activeSource)
                        } label: {
                            Image(systemName: "backward.fill")
                                .font(.system(size: 11, weight: .semibold))
                                .frame(width: 24, height: 24)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)

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
                            Image(systemName: playing ? "pause.fill" : "play.fill")
                                .font(.system(size: 12, weight: .semibold))
                                .frame(width: 28, height: 24)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)

                        Button {
                            coordinator.nowPlayingBridge.nextTrack(source: coordinator.activeSource)
                        } label: {
                            Image(systemName: "forward.fill")
                                .font(.system(size: 11, weight: .semibold))
                                .frame(width: 24, height: 24)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                    .padding(.top, 2)
                }

                Spacer(minLength: 0)
            }

            if let np, np.mode == .scriptablePlayer, np.canSeek, np.durationSeconds > 0 {
                VStack(spacing: 2) {
                    ProgressView(value: min(max(np.positionSeconds, 0), np.durationSeconds), total: np.durationSeconds)
                        .progressViewStyle(.linear)
                    HStack {
                        Text(NowPlayingParser.formatTime(np.positionSeconds))
                        Spacer()
                        Text(NowPlayingParser.formatTime(np.durationSeconds))
                    }
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                }
            }

            NativeMacVolumeSlider(
                value: coordinator.masterVolume,
                safeLimit: coordinator.volumeGuardSafeLimit,
                guardEnabled: coordinator.volumeGuardEnabled
            ) { newVol in
                Task { await coordinator.requestMasterVolume(newVol) }
            }
        }
        .padding(12)
        .background(.regularMaterial.opacity(0.65), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
        )
    }

    // MARK: - Section 3: Audio Source Strip

    private var audioSourceSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(t(.sourceSectionTitle))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                if coordinator.lastSourceSwitchWasAuto {
                    Text(t(.sourceAutoBadge))
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1.5)
                        .background(Color.accentColor.opacity(0.14), in: Capsule())
                }

                Spacer()

                Button {
                    openSourcePicker()
                } label: {
                    HStack(spacing: 3) {
                        Text(t(.commonManage))
                            .font(.caption)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .semibold))
                    }
                    .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 2)

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
                        Button(t(.sourceChooseApp)) {
                            openSourcePicker()
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                }
            }
        }
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
                    Image(systemName: "music.note")
                        .font(.system(size: 11))
                }
                Text(name)
                    .font(.caption.weight(selected ? .semibold : .regular))
                    .lineLimit(1)
                if running {
                    Circle()
                        .fill(.green)
                        .frame(width: 5, height: 5)
                }
            }
            .foregroundStyle(selected ? Color.accentColor : .primary)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(
                Capsule().fill(selected ? Color.accentColor.opacity(0.16) : Color.primary.opacity(0.06))
            )
            .overlay(
                Capsule().strokeBorder(selected ? Color.accentColor.opacity(0.55) : Color.primary.opacity(0.08), lineWidth: 0.75)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Section 4: Native Output Devices List

    private var outputDevicesSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(t(.outputDevicesTitle))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                Spacer()

                if coordinator.canRestoreLastSelection {
                    let count = coordinator.restorableLastSpeakerCount
                    Button(count > 1 ? CozyTingL10n.format(.restoreLastMulti, language: lang, count) : t(.restoreLast)) {
                        Task { await coordinator.restoreLastSelectedSpeakers() }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
                }

                Button {
                    Task { await coordinator.discoverDevices(manual: true) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11, weight: .medium))
                        .rotationEffect(.degrees(coordinator.isDiscovering ? 360 : 0))
                        .animation(
                            coordinator.isDiscovering ? .linear(duration: 1).repeatForever(autoreverses: false) : .default,
                            value: coordinator.isDiscovering
                        )
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
                .disabled(coordinator.isDiscovering)
            }
            .padding(.horizontal, 2)

            if let notice = coordinator.errorNotice {
                errorNoticeCard(notice)
            }

            ScrollView {
                LazyVStack(spacing: 6) {
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

                        NativeMacDeviceRow(
                            device: device,
                            title: title,
                            subtitle: device.kind == .thisDevice ? t(.commonMacSpeakers) : nil,
                            latencyBadge: DeviceDisplayFormatter.formattedLatency(for: stats),
                            statusText: statusDesc,
                            protocolBadge: device.protocol?.rawValue == "MIPLAY" ? BrandStrings.protocolName : device.protocol?.rawValue,
                            volume: coordinator.speakerVolumes[device.id] ?? coordinator.masterVolume,
                            safeLimit: coordinator.volumeGuardSafeLimit,
                            guardEnabled: coordinator.volumeGuardEnabled,
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
                maxHeight: isEmbeddedInMainWindow ? .infinity : 260
            )
        }
    }

    @ViewBuilder
    private func errorNoticeCard(_ notice: CastErrorNotice) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 6) {
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.top, 1)

                Text(notice.message)
                    .font(.caption)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer(minLength: 4)

                Button {
                    coordinator.clearError()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }

            if let buttonTitle = actionTitle(for: notice.action) {
                Button(buttonTitle) {
                    Task { await coordinator.performErrorAction(notice.action) }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
        }
        .padding(10)
        .background(Color.red.opacity(0.11), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Color.red.opacity(0.28), lineWidth: 0.75)
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

// MARK: - Native macOS Control Center Volume Slider & Device Row

private struct NativeMacVolumeSlider: View {
    let value: Int
    let safeLimit: Int
    let guardEnabled: Bool
    let onCommit: (Int) -> Void

    @State private var localValue: Double?

    private var displayed: Double {
        localValue ?? Double(value)
    }

    private var isAboveSafeLimit: Bool {
        guardEnabled && Int(displayed.rounded()) > safeLimit
    }

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: displayed <= 0 ? "speaker.slash.fill" : "speaker.wave.2.fill")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(isAboveSafeLimit ? .orange : .secondary)
                .frame(width: 16)

            Slider(
                value: Binding(
                    get: { displayed },
                    set: { localValue = $0 }
                ),
                in: 0...100,
                onEditingChanged: { editing in
                    if !editing {
                        let finalVal = Int((localValue ?? Double(value)).rounded())
                        localValue = nil
                        onCommit(finalVal)
                    }
                }
            )
            .tint(isAboveSafeLimit ? .orange : .accentColor)
            .controlSize(.small)

            Text("\(Int(displayed.rounded()))%")
                .font(.caption.monospacedDigit())
                .foregroundStyle(isAboveSafeLimit ? .orange : .secondary)
                .frame(width: 34, alignment: .trailing)
        }
    }
}

private struct NativeMacDeviceRow: View {
    let device: Device
    let title: String
    let subtitle: String?
    let latencyBadge: String?
    let statusText: String?
    let protocolBadge: String?
    let volume: Int
    let safeLimit: Int
    let guardEnabled: Bool
    let onTap: () -> Void
    let onVolumeCommit: ((Int) -> Void)?

    private var isSelected: Bool { device.selected }

    private var symbolName: String {
        switch device.kind {
        case .thisDevice: return "laptopcomputer"
        case .tv: return "tv"
        case .speaker: return "hifispeaker.fill"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(action: onTap) {
                HStack(spacing: 10) {
                    ZStack {
                        Circle()
                            .fill(isSelected ? Color.accentColor : Color.primary.opacity(0.08))
                            .frame(width: 28, height: 28)
                        Image(systemName: symbolName)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(isSelected ? .white : .secondary)
                    }

                    VStack(alignment: .leading, spacing: 1.5) {
                        Text(title)
                            .font(.subheadline.weight(isSelected ? .semibold : .regular))
                            .foregroundStyle(device.castable || device.kind == .thisDevice ? .primary : .tertiary)
                            .lineLimit(1)

                        if let statusText, !statusText.isEmpty {
                            Text(statusText)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        } else if let subtitle, !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }

                    Spacer(minLength: 4)

                    if let latencyBadge, !latencyBadge.isEmpty {
                        Text(latencyBadge)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }

                    if let protocolBadge, !protocolBadge.isEmpty {
                        Text(protocolBadge)
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(isSelected ? Color.accentColor : .secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(
                                isSelected ? Color.accentColor.opacity(0.14) : Color.primary.opacity(0.06),
                                in: Capsule()
                            )
                    }

                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(isSelected ? Color.accentColor : .secondary.opacity(0.5))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isSelected, device.kind != .thisDevice, let onVolumeCommit {
                NativeMacVolumeSlider(
                    value: volume,
                    safeLimit: safeLimit,
                    guardEnabled: guardEnabled,
                    onCommit: onVolumeCommit
                )
                .padding(.leading, 36)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .fill(isSelected ? Color.accentColor.opacity(0.12) : Color.primary.opacity(0.045))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 11, style: .continuous)
                .strokeBorder(
                    isSelected ? Color.accentColor.opacity(0.45) : Color.primary.opacity(0.07),
                    lineWidth: isSelected ? 1 : 0.5
                )
        )
        .opacity(device.castable || device.kind == .thisDevice ? 1.0 : 0.6)
    }
}
