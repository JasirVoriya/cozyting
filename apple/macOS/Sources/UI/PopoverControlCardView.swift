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
                        preferredWidth: 356,
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
                    .frame(width: 356)
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
        .frame(width: isEmbeddedInMainWindow ? nil : 356)
        .frame(maxWidth: isEmbeddedInMainWindow ? .infinity : 356)
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
        .padding(.horizontal, 14)
        .padding(.top, isEmbeddedInMainWindow ? 2 : 14)
        .padding(.bottom, 14)
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
                CozyTingIconView(.cozyHouse, size: 15)
                    .foregroundStyle(DesignTokens.Brand.coral)

                Text(brandTitle)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(.primary)
            }

            // Native Control Center frosted status pill
            HStack(spacing: 5) {
                Circle()
                    .fill(statusDotColor)
                    .frame(width: 6, height: 6)
                Text(statusLabel)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(Color.primary.opacity(0.07), in: Capsule())
            .overlay(
                Capsule().strokeBorder(Color.primary.opacity(0.09), lineWidth: 0.5)
            )

            Spacer(minLength: 4)

            latencyMenuButton

            if !isEmbeddedInMainWindow {
                GlassCircleIconButton(size: 26, action: onOpenMainWindow) {
                    Image(systemName: "macwindow")
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(.secondary)
                }

                GlassCircleIconButton(size: 26, action: onOpenSettings) {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "gearshape")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.secondary)
                        if coordinator.hasUpdateBadge {
                            Circle()
                                .fill(DesignTokens.Brand.coral)
                                .frame(width: 6, height: 6)
                                .offset(x: 2, y: -2)
                        }
                    }
                }
            }
        }
    }

    private var statusDotColor: Color {
        if !coordinator.isCasting { return .secondary.opacity(0.55) }
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
            .foregroundStyle(preset == .ultra ? .orange : .secondary)
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(Color.primary.opacity(0.07), in: Capsule())
        .overlay(
            Capsule().strokeBorder(Color.primary.opacity(0.09), lineWidth: 0.5)
        )
        .focusEffectDisabled()
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

    // MARK: - Section 2: Centered macOS Control Center Now Playing & Volume Module

    private var nowPlayingAndMasterVolumeModule: some View {
        let np = coordinator.nowPlayingBridge.snapshot
        let sourceRunning = coordinator.isActiveSourceRunning
        let isOutputtingAudio = coordinator.nowPlayingBridge.isActiveSourceOutputtingAudio

        let isMediaPlaying: Bool = {
            if let np, np.mode == .scriptablePlayer { return np.isPlaying }
            if coordinator.isCasting { return !coordinator.isPaused }
            return isOutputtingAudio
        }()

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
            if isOutputtingAudio {
                return t(.nowPlayingPlaying)
            }
            return t(.nowPlayingReadyLan)
        }()

        let subtitleIsWarning = coordinator.activeSource != nil
            && !sourceRunning
            && !coordinator.isCasting
            && coordinator.sourceMode == .app

        return ZStack(alignment: .topTrailing) {
            VStack(spacing: 12) {
                // Centered Media Artwork + Track/App Info
                VStack(spacing: 8) {
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
                                    .frame(width: 54, height: 54)
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                                            .strokeBorder(Color.primary.opacity(0.14), lineWidth: 0.5)
                                    )
                                    .shadow(color: .black.opacity(0.22), radius: 8, y: 4)
                            } else if let bid = coordinator.activeSource?.bundleIdentifier,
                                      let icon = coordinator.sourceCatalog.icon(for: bid) {
                                Image(nsImage: icon)
                                    .resizable()
                                    .interpolation(.high)
                                    .frame(width: 54, height: 54)
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .shadow(color: .black.opacity(0.20), radius: 8, y: 4)
                            } else {
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .fill(Color.primary.opacity(0.10))
                                    .frame(width: 54, height: 54)
                                    .overlay(
                                        Image(systemName: "music.note")
                                            .font(.system(size: 22, weight: .medium))
                                            .foregroundStyle(.secondary)
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
                                    .shadow(color: .black.opacity(0.3), radius: 2, y: 1)
                                    .offset(x: 3, y: 3)
                            }
                        }
                    }
                    .buttonStyle(GlassPressButtonStyle())

                    VStack(spacing: 2.5) {
                        Text(primaryTitle)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                            .multilineTextAlignment(.center)

                        HStack(spacing: 4) {
                            if isOutputtingAudio && !coordinator.isCasting && np?.mode != .scriptablePlayer {
                                Circle()
                                    .fill(.green)
                                    .frame(width: 5.5, height: 5.5)
                            }
                            Text(secondarySubtitle)
                                .font(.system(size: 11.5))
                                .foregroundStyle(subtitleIsWarning ? .orange : .secondary)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }

                // Sleek glass progress bar (Apple Music / Spotify when seekable)
                if let np, np.mode == .scriptablePlayer, np.canSeek, np.durationSeconds > 0 {
                    VStack(spacing: 4) {
                        GeometryReader { geo in
                            let ratio = min(max(np.positionSeconds / np.durationSeconds, 0), 1)
                            ZStack(alignment: .leading) {
                                Capsule()
                                    .fill(Color.primary.opacity(0.12))
                                Capsule()
                                    .fill(Color.primary.opacity(0.75))
                                    .frame(width: max(geo.size.width * ratio, 4))
                            }
                        }
                        .frame(height: 4)

                        HStack {
                            Text(NowPlayingParser.formatTime(np.positionSeconds))
                            Spacer()
                            Text(NowPlayingParser.formatTime(np.durationSeconds))
                        }
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 4)
                }

                // Centered Apple Control Center Borderless Frosted-Glass Transport Controls
                HStack(spacing: 24) {
                    GlassCircleIconButton(size: 34) {
                        coordinator.nowPlayingBridge.previousTrack(source: coordinator.activeSource)
                    } content: {
                        Image(systemName: "backward.fill")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.primary.opacity(0.86))
                    }

                    GlassCircleIconButton(size: 42, prominent: true) {
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
                    } content: {
                        Image(systemName: isMediaPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(.primary)
                            .offset(x: isMediaPlaying ? 0 : 1)
                    }

                    GlassCircleIconButton(size: 34) {
                        coordinator.nowPlayingBridge.nextTrack(source: coordinator.activeSource)
                    } content: {
                        Image(systemName: "forward.fill")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.primary.opacity(0.86))
                    }
                }
                .frame(maxWidth: .infinity)

                // Authentic macOS Control Center Glass Capsule Volume Slider
                ControlCenterVolumeBar(
                    value: coordinator.masterVolume,
                    safeLimit: coordinator.volumeGuardSafeLimit,
                    guardEnabled: coordinator.volumeGuardEnabled,
                    height: 22
                ) { newVol in
                    Task { await coordinator.requestMasterVolume(newVol) }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)

            // Live animated audio waveform indicator in top-right corner when playing
            if isMediaPlaying {
                Image(systemName: "waveform")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .symbolEffect(.variableColor.iterative, isActive: isMediaPlaying)
                    .padding(12)
            }
        }
        .background(.regularMaterial.opacity(0.62), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.5)
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
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1.5)
                        .background(Color.primary.opacity(0.12), in: Capsule())
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
                        GlassPillButton(action: openSourcePicker) {
                            Text(t(.sourceChooseApp))
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.primary)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func sourceChip(bundleIdentifier: String, name: String, selected: Bool) -> some View {
        let running = coordinator.sourceCatalog.isRunning(bundleIdentifier: bundleIdentifier)
        SourceGlassChip(
            icon: coordinator.sourceCatalog.icon(for: bundleIdentifier),
            name: name,
            selected: selected,
            running: running
        ) {
            Task {
                await coordinator.selectAudioSource(
                    AudioSourceSelection(bundleIdentifier: bundleIdentifier, displayName: name),
                    manual: true
                )
            }
        }
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
                    GlassPillButton {
                        Task { await coordinator.restoreLastSelectedSpeakers() }
                    } content: {
                        Text(count > 1 ? CozyTingL10n.format(.restoreLastMulti, language: lang, count) : t(.restoreLast))
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.primary)
                    }
                }

                GlassCircleIconButton(size: 22) {
                    Task { await coordinator.discoverDevices(manual: true) }
                } content: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(coordinator.isDiscovering ? 360 : 0))
                        .animation(
                            coordinator.isDiscovering ? .linear(duration: 1).repeatForever(autoreverses: false) : .default,
                            value: coordinator.isDiscovering
                        )
                }
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
                minHeight: isEmbeddedInMainWindow ? 170 : 165,
                idealHeight: isEmbeddedInMainWindow ? 240 : 200,
                maxHeight: isEmbeddedInMainWindow ? .infinity : 250
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
                GlassPillButton {
                    Task { await coordinator.performErrorAction(notice.action) }
                } content: {
                    Text(buttonTitle)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.primary)
                }
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

// MARK: - Frosted-Glass Source Chip & Output Device Row

private struct SourceGlassChip: View {
    let icon: NSImage?
    let name: String
    let selected: Bool
    let running: Bool
    let action: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let icon {
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
            .foregroundStyle(selected ? Color.primary : Color.primary.opacity(0.78))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(
                Capsule().fill(
                    selected
                        ? Color.primary.opacity(0.17)
                        : Color.primary.opacity(isHovered ? 0.11 : 0.06)
                )
            )
            .overlay(
                Capsule().strokeBorder(
                    selected
                        ? Color.primary.opacity(0.28)
                        : Color.primary.opacity(isHovered ? 0.14 : 0.08),
                    lineWidth: selected ? 0.75 : 0.5
                )
            )
            .contentShape(Capsule())
        }
        .buttonStyle(GlassPressButtonStyle())
        .onHover { hovering in
            withAnimation(.easeOut(duration: 0.12)) {
                isHovered = hovering
            }
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

    @State private var isHovered: Bool = false

    private var isSelected: Bool { device.selected }

    private var symbolName: String {
        switch device.kind {
        case .thisDevice: return "laptopcomputer"
        case .tv: return "tv"
        case .speaker: return "hifispeaker.fill"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: onTap) {
                HStack(spacing: 10) {
                    ZStack {
                        Circle()
                            .fill(isSelected ? Color.accentColor : Color.primary.opacity(0.10))
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
                            .foregroundStyle(isSelected ? .primary : .secondary)
                            .padding(.horizontal, 6.5)
                            .padding(.vertical, 2)
                            .background(
                                isSelected ? Color.primary.opacity(0.14) : Color.primary.opacity(0.06),
                                in: Capsule()
                            )
                    }

                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(isSelected ? Color.accentColor : .secondary.opacity(0.45))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isSelected, device.kind != .thisDevice, let onVolumeCommit {
                ControlCenterVolumeBar(
                    value: volume,
                    safeLimit: safeLimit,
                    guardEnabled: guardEnabled,
                    height: 18,
                    onCommit: onVolumeCommit
                )
                .padding(.leading, 38)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8.5)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(
                    isSelected
                        ? Color.primary.opacity(0.12)
                        : Color.primary.opacity(isHovered ? 0.085 : 0.05)
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(
                    isSelected
                        ? Color.primary.opacity(0.22)
                        : Color.primary.opacity(isHovered ? 0.12 : 0.07),
                    lineWidth: isSelected ? 0.75 : 0.5
                )
        )
        .opacity(device.castable || device.kind == .thisDevice ? 1.0 : 0.6)
        .onHover { hovering in
            withAnimation(.easeOut(duration: 0.12)) {
                isHovered = hovering
            }
        }
    }
}
