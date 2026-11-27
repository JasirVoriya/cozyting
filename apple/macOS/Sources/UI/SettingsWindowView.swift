import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Native macOS System Settings (`Form().formStyle(.grouped)`) over behind-window frosted glass.
public struct SettingsWindowView: View {
    @Bindable public var coordinator: CastCoordinator
    public let updateService: UpdateService
    public let onAppearanceChanged: (ThemeMode) -> Void
    public let onLanguageChanged: ((AppLanguage) -> Void)?
    public let onOpenUpdateDetail: (() -> Void)?

    @Environment(\.colorScheme) private var systemColorScheme
    @State private var showAdvancedDebug: Bool = false
    @State private var manualIPText: String = ""
    @State private var manualIPFeedback: String?
    @State private var customDelayText: String = ""
    @State private var customDelayFeedback: String?
    @State private var showLogViewer: Bool = false
    @State private var showAboutSheet: Bool = false
    @State private var logSizeLabel: String = "0 B"
    @State private var updateState: UpdateCheckState = .idle
    @State private var betaUpdatesEnabled: Bool = false
    @State private var accessibilityGranted: Bool = HardwareVolumeKeyInterceptor.isAccessibilityGranted()

    public init(
        coordinator: CastCoordinator,
        updateService: UpdateService,
        onAppearanceChanged: @escaping (ThemeMode) -> Void,
        onLanguageChanged: ((AppLanguage) -> Void)? = nil,
        onOpenUpdateDetail: (() -> Void)? = nil
    ) {
        self.coordinator = coordinator
        self.updateService = updateService
        self.onAppearanceChanged = onAppearanceChanged
        self.onLanguageChanged = onLanguageChanged
        self.onOpenUpdateDetail = onOpenUpdateDetail
    }

    private var lang: AppLanguage { coordinator.appLanguage }

    private var effectiveScheme: ColorScheme {
        PlatformAdapters.preferredColorScheme(for: coordinator.themeMode) ?? systemColorScheme
    }

    private var palette: DesignTokens.ThemePalette {
        DesignTokens.ThemePalette.palette(for: effectiveScheme)
    }

    private func t(_ key: L10nKey) -> String {
        CozyTingL10n.text(key, language: lang)
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.9.12"
    }

    private var sponsorEnabled: Bool {
        #if ENABLE_SPONSOR_ENTRY && !COZYTING_APP_STORE
        return true
        #else
        return false
        #endif
    }

    public var body: some View {
        ZStack {
            VisualEffectBackground(
                material: .underWindowBackground,
                blendingMode: .behindWindow,
                state: .followsWindowActiveState
            )
            .ignoresSafeArea()

            Form {
                // Section 1: Appearance & Discovery
                Section(t(.settingsSectionAppearance)) {
                    Picker(t(.settingsThemeTitle), selection: Binding(
                        get: { coordinator.themeMode },
                        set: { newMode in
                            coordinator.setThemeMode(newMode)
                            onAppearanceChanged(newMode)
                        }
                    )) {
                        Text(t(.themeSystem)).tag(ThemeMode.system)
                        Text(t(.themeLight)).tag(ThemeMode.light)
                        Text(t(.themeDark)).tag(ThemeMode.dark)
                    }
                    .pickerStyle(.segmented)

                    Picker(t(.settingsLanguageTitle), selection: Binding(
                        get: { coordinator.appLanguage },
                        set: { newLang in
                            coordinator.setAppLanguage(newLang)
                            onLanguageChanged?(newLang)
                        }
                    )) {
                        Text(t(.langSystem)).tag(AppLanguage.system)
                        Text("简体中文").tag(AppLanguage.zhCN)
                        Text("English").tag(AppLanguage.en)
                        Text("日本語").tag(AppLanguage.ja)
                    }
                    .pickerStyle(.menu)

                    Toggle(isOn: Binding(
                        get: { coordinator.showAllDiscovered },
                        set: { coordinator.setShowAllDiscovered($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsShowAllTitle))
                            Text(t(.settingsShowAllSub))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    HStack {
                        Spacer()
                        Button {
                            Task { await coordinator.discoverDevices(manual: true) }
                        } label: {
                            Label(t(.settingsRescanBtn), systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(.bordered)
                        .disabled(coordinator.isDiscovering)
                    }
                }

                // Section 2: Audio & Permissions
                Section(t(.settingsSectionAudio)) {
                    LabeledContent {
                        Button(t(.settingsOpenSystemSettings)) {
                            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AudioCapture") {
                                NSWorkspace.shared.open(url)
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsAudioPermTitle))
                            Text(t(.settingsAudioPermSub))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    LabeledContent {
                        Button(t(.settingsOpenFirewall)) {
                            if let url = URL(string: "x-apple.systempreferences:com.apple.Network-Settings.extension") {
                                NSWorkspace.shared.open(url)
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsFirewallTitle))
                            Text(t(.settingsFirewallSub))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Toggle(isOn: Binding(
                        get: { coordinator.muteLocalAudio },
                        set: { coordinator.setMuteLocalAudio($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsLocalMuteTitle))
                            Text(t(.settingsLocalMuteSub))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Toggle(isOn: Binding(
                        get: { coordinator.volumeGuardEnabled },
                        set: { coordinator.setVolumeGuard(enabled: $0, safeLimit: coordinator.volumeGuardSafeLimit) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsVolumeGuardTitle))
                            Text(t(.settingsVolumeGuardSub))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if coordinator.volumeGuardEnabled {
                        LabeledContent(t(.settingsVolumeLimitTitle)) {
                            HStack(spacing: 8) {
                                Slider(
                                    value: Binding(
                                        get: { Double(coordinator.volumeGuardSafeLimit) },
                                        set: { coordinator.setVolumeGuard(enabled: true, safeLimit: Int($0)) }
                                    ),
                                    in: 30...90,
                                    step: 5
                                )
                                .frame(width: 150)
                                Text("\(coordinator.volumeGuardSafeLimit)%")
                                    .font(.caption.monospacedDigit().weight(.semibold))
                                    .foregroundStyle(.orange)
                                    .frame(width: 36, alignment: .trailing)
                            }
                        }
                    }

                    Toggle(isOn: Binding(
                        get: { coordinator.autoDetectAudioSource },
                        set: { coordinator.setAutoDetectAudioSource($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsAutoDetectTitle))
                            Text(t(.settingsAutoDetectSub))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Toggle(isOn: Binding(
                            get: { coordinator.hardwareVolumeKeysEnabled },
                            set: { enabled in
                                let ok = coordinator.setHardwareVolumeKeysEnabled(enabled)
                                accessibilityGranted = HardwareVolumeKeyInterceptor.isAccessibilityGranted()
                                if enabled && !ok {
                                    HardwareVolumeKeyInterceptor.openAccessibilitySettings()
                                }
                            }
                        )) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t(.settingsHwVolKeysTitle))
                                Text(t(.settingsHwVolKeysSub))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        HStack(spacing: 8) {
                            Text(accessibilityGranted ? t(.settingsAccessibilityGranted) : t(.settingsAccessibilityNeeded))
                                .font(.caption)
                                .foregroundStyle(accessibilityGranted ? .green : .secondary)
                            if !accessibilityGranted {
                                Button(t(.settingsOpenSystemSettings)) {
                                    HardwareVolumeKeyInterceptor.openAccessibilitySettings()
                                }
                                .buttonStyle(.link)
                                .font(.caption)
                            }
                        }
                    }
                }

                // Section 3: Connection & Debug
                Section(t(.settingsSectionDebug)) {
                    Button {
                        refreshLogSize()
                        showLogViewer = true
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t(.settingsLogsTitle))
                                    .foregroundStyle(.primary)
                                Text(CozyTingL10n.format(.settingsLogsSub, language: lang, logSizeLabel))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    DisclosureGroup(isExpanded: $showAdvancedDebug) {
                        VStack(alignment: .leading, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(t(.settingsManualIPLabel))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                HStack {
                                    TextField(t(.settingsManualIPHint), text: $manualIPText)
                                        .textFieldStyle(.roundedBorder)
                                    Button(t(.commonAdd)) {
                                        let trimmed = manualIPText.trimmingCharacters(in: .whitespacesAndNewlines)
                                        if coordinator.addManualSpeaker(ip: trimmed) {
                                            manualIPFeedback = CozyTingL10n.format(.settingsManualIPAdded, language: lang, trimmed)
                                            manualIPText = ""
                                        } else {
                                            manualIPFeedback = t(.settingsManualIPInvalid)
                                        }
                                    }
                                    .buttonStyle(.bordered)
                                }
                                if let manualIPFeedback {
                                    Text(manualIPFeedback)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }

                            Picker(t(.settingsSourceModeLabel), selection: Binding(
                                get: { coordinator.sourceMode },
                                set: { coordinator.setSourceMode($0) }
                            )) {
                                Text(t(.settingsSourceModeApp)).tag(SourceMode.app)
                                Text(t(.settingsSourceModeSilent)).tag(SourceMode.silent)
                            }
                            .pickerStyle(.segmented)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(t(.settingsCustomDelayLabel))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                HStack {
                                    TextField("\(coordinator.delayMs)", text: $customDelayText)
                                        .textFieldStyle(.roundedBorder)
                                    Button(t(.commonApply)) {
                                        let trimmed = customDelayText.trimmingCharacters(in: .whitespacesAndNewlines)
                                        let parsed = trimmed.isEmpty ? 0 : Int(trimmed)
                                        if let ms = parsed, ms == 0 || (50...5000).contains(ms) {
                                            customDelayFeedback = nil
                                            Task { await coordinator.setPlaybackDelay(ms) }
                                        } else {
                                            customDelayFeedback = t(.settingsCustomDelayInvalid)
                                        }
                                    }
                                    .buttonStyle(.bordered)
                                }
                                if let customDelayFeedback {
                                    Text(customDelayFeedback)
                                        .font(.caption)
                                        .foregroundStyle(.red)
                                }
                            }

                            Picker(t(.settingsRefModeLabel), selection: Binding(
                                get: { coordinator.preferences.refFunction },
                                set: { coordinator.preferences.refFunction = $0 }
                            )) {
                                Text(t(.settingsRefAuto)).tag("")
                                Text(t(.settingsRefSingle)).tag("single")
                                Text(t(.settingsRefMulti)).tag("multi")
                                Text(t(.settingsRefStereo)).tag("stereo")
                            }
                            .pickerStyle(.segmented)

                            Picker(t(.settingsAuthModeLabel), selection: Binding(
                                get: { coordinator.authMode },
                                set: { coordinator.setAuthMode($0) }
                            )) {
                                Text(t(.settingsAuthAuto)).tag(AuthMode.auto)
                                Text(t(.settingsAuthLegacy)).tag(AuthMode.legacy)
                                Text(t(.settingsAuthSafety)).tag(AuthMode.safety)
                            }
                            .pickerStyle(.segmented)
                        }
                        .padding(.top, 6)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsAdvancedHeader))
                            Text(t(.settingsAdvancedSub))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // Section 4: About & Updates
                Section(t(.settingsSectionAbout)) {
                    Button { showAboutSheet = true } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t(.aboutTitle))
                                    .foregroundStyle(.primary)
                                Text(t(.aboutFooterNote))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if hasNewUpdate, case .ready(let decision, _, _) = updateState {
                                Text(decision.latestTag)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 2.5)
                                    .background(Color.accentColor, in: Capsule())
                            } else {
                                Text("v\(UpdateChannel.stripBuildSuffixes(appVersion))")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .formStyle(.grouped)
            .scrollContentBackground(.hidden)
            .padding(.top, 24)
        }
        .frame(minWidth: 500, idealWidth: 540, minHeight: 520, idealHeight: 640)
        .preferredColorScheme(PlatformAdapters.preferredColorScheme(for: coordinator.themeMode))
        .onAppear {
            refreshVolatileStates()
        }
        .sheet(isPresented: $showLogViewer, onDismiss: { refreshLogSize() }) {
            CozyLogViewerView(
                logManager: coordinator.logManager,
                language: lang,
                palette: palette,
                appVersion: appVersion,
                onExportedFile: { url in
                    NSWorkspace.shared.activateFileViewerSelecting([url])
                },
                onClose: { showLogViewer = false }
            )
            .frame(width: 620, height: 520)
        }
        .sheet(isPresented: $showAboutSheet) {
            CozyAboutView(
                appVersion: appVersion,
                language: lang,
                palette: palette,
                updateStatusText: updateRowBadgeText,
                hasUpdateAvailable: hasNewUpdate,
                isCheckingUpdates: isCheckingUpdates,
                betaChannelEnabled: betaUpdatesEnabled,
                sponsorEntryEnabled: sponsorEnabled,
                weChatQRImage: loadBundledQR(named: "wechat-tip"),
                alipayQRImage: loadBundledQR(named: "alipay-tip"),
                onCheckUpdates: {
                    Task {
                        if hasNewUpdate, case .ready(let decision, _, _) = updateState {
                            showAboutSheet = false
                            coordinator.presentedUpdateDecision = decision
                        } else {
                            let next = await updateService.checkManually()
                            updateState = next
                            await coordinator.syncUpdatePresentation(from: updateService)
                            if case .ready(let decision, _, _) = next,
                               decision.status == .updateAvailable {
                                showAboutSheet = false
                                coordinator.presentedUpdateDecision = decision
                            }
                        }
                    }
                },
                onToggleBetaChannel: { enabled in
                    betaUpdatesEnabled = enabled
                    coordinator.preferences.updateChannelRaw = enabled ? UpdateChannel.prerelease.rawValue : UpdateChannel.stable.rawValue
                    Task {
                        updateState = await updateService.reevaluateFromCache(manual: false)
                    }
                },
                onOpenReleases: {
                    NSWorkspace.shared.open(UpdateConfig.publicReleasesWebURL)
                },
                onSaveWeChatQR: { saveBundledQRToDownloads(named: "wechat-tip", outName: "cozyting-wechat-tip.png") },
                onSaveAlipayQR: { saveBundledQRToDownloads(named: "alipay-tip", outName: "cozyting-alipay-tip.png") },
                onClose: { showAboutSheet = false }
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
                        Task {
                            await coordinator.skipUpdateVersion(decision.latestTag, using: updateService)
                            updateState = await updateService.state
                        }
                    },
                    onDismiss: { coordinator.presentedUpdateDecision = nil }
                )
            }
        }
    }

    // MARK: - Helpers

    private var isCheckingUpdates: Bool {
        if case .checking = updateState { return true }
        return false
    }

    private var hasNewUpdate: Bool {
        if case .ready(let decision, _, _) = updateState {
            return decision.status == .updateAvailable
        }
        return false
    }

    private var updateRowBadgeText: String {
        switch updateState {
        case .idle:
            return t(.aboutUpToDate)
        case .checking:
            return t(.aboutChecking)
        case .ready(let decision, _, _):
            if decision.status == .updateAvailable {
                return decision.latestTag
            }
            return t(.aboutUpToDate)
        case .failed:
            return t(.aboutCheckFailed)
        }
    }

    private func refreshVolatileStates() {
        refreshLogSize()
        customDelayText = coordinator.delayMs == 0 ? "" : "\(coordinator.delayMs)"
        accessibilityGranted = HardwareVolumeKeyInterceptor.isAccessibilityGranted()
        betaUpdatesEnabled = coordinator.preferences.resolvedUpdateChannel(currentVersionName: appVersion) == .prerelease
        Task {
            updateState = await updateService.state
        }
    }

    private func refreshLogSize() {
        logSizeLabel = CozyTingLogManager.formatSize(coordinator.logManager.totalSizeBytes())
    }

    private func loadBundledQR(named name: String) -> Image? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "png"),
              let nsImage = NSImage(contentsOf: url) else {
            return nil
        }
        return Image(nsImage: nsImage)
    }

    private func saveBundledQRToDownloads(named name: String, outName: String) {
        guard let src = Bundle.main.url(forResource: name, withExtension: "png"),
              let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first else {
            return
        }
        let dst = downloads.appendingPathComponent(outName)
        try? FileManager.default.removeItem(at: dst)
        if (try? FileManager.default.copyItem(at: src, to: dst)) != nil {
            NSWorkspace.shared.activateFileViewerSelecting([dst])
        }
    }
}
