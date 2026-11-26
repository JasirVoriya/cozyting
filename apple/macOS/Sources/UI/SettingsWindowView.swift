import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// 4-Card Style B Settings Window (`design/UI_SPEC.md` §3 & `docs/MACOS_CLIENT_DESIGN.md` §2.3).
///
/// Cards:
/// 1. Appearance & Discovery (3-state theme, 4-state language, show all discovered, rescan)
/// 2. Audio & Permissions (System Audio Recording deep-link, local app mute, volume guard + 30–90% slider,
///    auto-detect toggle, opt-in F11/F12 hardware volume keys)
/// 3. Connection & Debug (Direct Runtime Logs row with live byte size + collapsible manual IP / source mode /
///    custom delay ms / ref_function / auth mode)
/// 4. About & Updates (Single entry row with version / update badge → `CozyAboutView`)
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
        ScrollView {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.lg) {
                appearanceAndDiscoveryCard
                audioAndPermissionsCard
                connectionAndDebugCard
                aboutAndUpdatesCard
            }
            .padding(DesignTokens.Spacing.xl)
        }
        .frame(minWidth: 520, idealWidth: 540, minHeight: 540, idealHeight: 640)
        .background(palette.bgCanvas)
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

    // MARK: - Card 1: Appearance & Discovery

    private var appearanceAndDiscoveryCard: some View {
        settingsCard(title: t(.settingsSectionAppearance)) {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                HStack {
                    Text(t(.settingsThemeTitle))
                        .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                    Spacer()
                    Picker("", selection: Binding(
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
                    .frame(width: 220)
                }

                Divider().opacity(0.25)

                HStack {
                    Text(t(.settingsLanguageTitle))
                        .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                    Spacer()
                    Picker("", selection: Binding(
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
                    .pickerStyle(.segmented)
                    .frame(width: 310)
                }

                Divider().opacity(0.25)

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t(.settingsShowAllTitle))
                            .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                            .foregroundStyle(palette.textPrimary)
                        Text(t(.settingsShowAllSub))
                            .font(.system(size: DesignTokens.Typography.micro))
                            .foregroundStyle(palette.textSecondary)
                    }
                    Spacer()
                    Toggle("", isOn: Binding(
                        get: { coordinator.showAllDiscovered },
                        set: { coordinator.setShowAllDiscovered($0) }
                    ))
                    .labelsHidden()
                    .toggleStyle(.switch)
                }

                Button {
                    Task { await coordinator.discoverDevices(manual: true) }
                } label: {
                    HStack {
                        CozyTingIconView(.refresh, size: 12, weight: .semibold)
                        Text(t(.settingsRescanBtn))
                            .font(.system(size: DesignTokens.Typography.caption, weight: .semibold))
                    }
                    .foregroundStyle(palette.accentBright)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(palette.accentSoftBg, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Card 2: Audio & System Permissions

    private var audioAndPermissionsCard: some View {
        settingsCard(title: t(.settingsSectionAudio)) {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t(.settingsAudioPermTitle))
                            .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                            .foregroundStyle(palette.textPrimary)
                        Text(t(.settingsAudioPermSub))
                            .font(.system(size: DesignTokens.Typography.micro))
                            .foregroundStyle(palette.textSecondary)
                    }
                    Spacer()
                    Button {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AudioCapture") {
                            NSWorkspace.shared.open(url)
                        }
                    } label: {
                        Text(t(.settingsOpenSystemSettings))
                            .font(.system(size: DesignTokens.Typography.micro, weight: .semibold))
                            .foregroundStyle(palette.accentBright)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(palette.accentSoftBg, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }

                Divider().opacity(0.25)

                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t(.settingsFirewallTitle))
                            .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                            .foregroundStyle(palette.textPrimary)
                        Text(t(.settingsFirewallSub))
                            .font(.system(size: DesignTokens.Typography.micro))
                            .foregroundStyle(palette.textSecondary)
                    }
                    Spacer()
                    Button {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.Network-Settings.extension") {
                            NSWorkspace.shared.open(url)
                        }
                    } label: {
                        Text(t(.settingsOpenFirewall))
                            .font(.system(size: DesignTokens.Typography.micro, weight: .semibold))
                            .foregroundStyle(palette.accentBright)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(palette.accentSoftBg, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }

                Divider().opacity(0.25)

                toggleRow(
                    title: t(.settingsLocalMuteTitle),
                    subtitle: t(.settingsLocalMuteSub),
                    isOn: Binding(
                        get: { coordinator.muteLocalAudio },
                        set: { coordinator.setMuteLocalAudio($0) }
                    )
                )

                Divider().opacity(0.25)

                toggleRow(
                    title: t(.settingsVolumeGuardTitle),
                    subtitle: t(.settingsVolumeGuardSub),
                    isOn: Binding(
                        get: { coordinator.volumeGuardEnabled },
                        set: { coordinator.setVolumeGuard(enabled: $0, safeLimit: coordinator.volumeGuardSafeLimit) }
                    )
                )

                if coordinator.volumeGuardEnabled {
                    HStack(spacing: DesignTokens.Spacing.sm) {
                        Text(t(.settingsVolumeLimitTitle))
                            .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                            .foregroundStyle(palette.textSecondary)
                        Slider(
                            value: Binding(
                                get: { Double(coordinator.volumeGuardSafeLimit) },
                                set: { coordinator.setVolumeGuard(enabled: true, safeLimit: Int($0)) }
                            ),
                            in: 30...90,
                            step: 5
                        )
                        Text("\(coordinator.volumeGuardSafeLimit)%")
                            .font(.system(size: DesignTokens.Typography.caption, weight: .semibold, design: .monospaced))
                            .foregroundStyle(palette.statusWarning)
                            .frame(width: 38, alignment: .trailing)
                    }
                }

                Divider().opacity(0.25)

                toggleRow(
                    title: t(.settingsAutoDetectTitle),
                    subtitle: t(.settingsAutoDetectSub),
                    isOn: Binding(
                        get: { coordinator.autoDetectAudioSource },
                        set: { coordinator.setAutoDetectAudioSource($0) }
                    )
                )

                Divider().opacity(0.25)

                VStack(alignment: .leading, spacing: 6) {
                    toggleRow(
                        title: t(.settingsHwVolKeysTitle),
                        subtitle: t(.settingsHwVolKeysSub),
                        isOn: Binding(
                            get: { coordinator.hardwareVolumeKeysEnabled },
                            set: { enabled in
                                let ok = coordinator.setHardwareVolumeKeysEnabled(enabled)
                                accessibilityGranted = HardwareVolumeKeyInterceptor.isAccessibilityGranted()
                                if enabled && !ok {
                                    HardwareVolumeKeyInterceptor.openAccessibilitySettings()
                                }
                            }
                        )
                    )
                    HStack(spacing: 8) {
                        Text(accessibilityGranted ? t(.settingsAccessibilityGranted) : t(.settingsAccessibilityNeeded))
                            .font(.system(size: 10.5, weight: .medium))
                            .foregroundStyle(accessibilityGranted ? palette.statusSuccess : palette.textTertiary)
                        if !accessibilityGranted {
                            Button {
                                HardwareVolumeKeyInterceptor.openAccessibilitySettings()
                            } label: {
                                Text(t(.settingsOpenSystemSettings))
                                    .font(.system(size: 10.5, weight: .semibold))
                                    .foregroundStyle(palette.accentBright)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    // MARK: - Card 3: Connection & Debug

    private var connectionAndDebugCard: some View {
        settingsCard(title: t(.settingsSectionDebug)) {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                // Direct top-level Runtime Logs row
                Button {
                    refreshLogSize()
                    showLogViewer = true
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsLogsTitle))
                                .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                                .foregroundStyle(palette.textPrimary)
                            Text(CozyTingL10n.format(.settingsLogsSub, language: lang, logSizeLabel))
                                .font(.system(size: DesignTokens.Typography.micro))
                                .foregroundStyle(palette.textSecondary)
                        }
                        Spacer()
                        CozyTingIconView(.chevronRight, size: 12, weight: .semibold)
                            .foregroundStyle(palette.textTertiary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Divider().opacity(0.25)

                // Collapsible Advanced Debug section
                Button {
                    withAnimation(.easeInOut(duration: 0.16)) {
                        showAdvancedDebug.toggle()
                    }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t(.settingsAdvancedHeader))
                                .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                                .foregroundStyle(palette.textPrimary)
                            Text(t(.settingsAdvancedSub))
                                .font(.system(size: DesignTokens.Typography.micro))
                                .foregroundStyle(palette.textSecondary)
                        }
                        Spacer()
                        CozyTingIconView(showAdvancedDebug ? .chevronDown : .chevronRight, size: 12, weight: .semibold)
                            .foregroundStyle(palette.textTertiary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if showAdvancedDebug {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                        // Manual IP
                        VStack(alignment: .leading, spacing: 6) {
                            Text(t(.settingsManualIPLabel))
                                .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                                .foregroundStyle(palette.textSecondary)
                            HStack(spacing: 8) {
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
                            }
                            if let manualIPFeedback {
                                Text(manualIPFeedback)
                                    .font(.system(size: 10.5))
                                    .foregroundStyle(palette.textSecondary)
                            }
                        }

                        // Source Mode
                        HStack {
                            Text(t(.settingsSourceModeLabel))
                                .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                                .foregroundStyle(palette.textSecondary)
                            Spacer()
                            Picker("", selection: Binding(
                                get: { coordinator.sourceMode },
                                set: { coordinator.setSourceMode($0) }
                            )) {
                                Text(t(.settingsSourceModeApp)).tag(SourceMode.app)
                                Text(t(.settingsSourceModeSilent)).tag(SourceMode.silent)
                            }
                            .pickerStyle(.segmented)
                            .frame(width: 210)
                        }

                        // Custom Delay ms
                        VStack(alignment: .leading, spacing: 6) {
                            Text(t(.settingsCustomDelayLabel))
                                .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                                .foregroundStyle(palette.textSecondary)
                            HStack(spacing: 8) {
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
                            }
                            if let customDelayFeedback {
                                Text(customDelayFeedback)
                                    .font(.system(size: 10.5))
                                    .foregroundStyle(palette.statusError)
                            }
                        }

                        // Ref Function
                        HStack {
                            Text(t(.settingsRefModeLabel))
                                .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                                .foregroundStyle(palette.textSecondary)
                            Spacer()
                            Picker("", selection: Binding(
                                get: { coordinator.preferences.refFunction },
                                set: { coordinator.preferences.refFunction = $0 }
                            )) {
                                Text(t(.settingsRefAuto)).tag("")
                                Text(t(.settingsRefSingle)).tag("single")
                                Text(t(.settingsRefMulti)).tag("multi")
                                Text(t(.settingsRefStereo)).tag("stereo")
                            }
                            .pickerStyle(.segmented)
                            .frame(width: 240)
                        }

                        // Auth Mode
                        HStack {
                            Text(t(.settingsAuthModeLabel))
                                .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                                .foregroundStyle(palette.textSecondary)
                            Spacer()
                            Picker("", selection: Binding(
                                get: { coordinator.authMode },
                                set: { coordinator.setAuthMode($0) }
                            )) {
                                Text(t(.settingsAuthAuto)).tag(AuthMode.auto)
                                Text(t(.settingsAuthLegacy)).tag(AuthMode.legacy)
                                Text(t(.settingsAuthSafety)).tag(AuthMode.safety)
                            }
                            .pickerStyle(.segmented)
                            .frame(width: 240)
                        }
                    }
                    .padding(.top, 4)
                }
            }
        }
    }

    // MARK: - Card 4: About & Updates

    private var aboutAndUpdatesCard: some View {
        settingsCard(title: t(.settingsSectionAbout)) {
            Button { showAboutSheet = true } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t(.aboutTitle))
                            .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                            .foregroundStyle(palette.textPrimary)
                        Text(t(.aboutFooterNote))
                            .font(.system(size: DesignTokens.Typography.micro))
                            .foregroundStyle(palette.textSecondary)
                    }
                    Spacer()
                    if hasNewUpdate, case .ready(let decision, _, _) = updateState {
                        HStack(spacing: 5) {
                            Circle().fill(palette.accent).frame(width: 7, height: 7)
                            Text(decision.latestTag)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(Color.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(palette.accent, in: Capsule())
                        }
                    } else {
                        Text("v\(UpdateChannel.stripBuildSuffixes(appVersion))")
                            .font(.system(size: DesignTokens.Typography.caption, design: .monospaced))
                            .foregroundStyle(palette.textSecondary)
                    }
                    CozyTingIconView(.chevronRight, size: 12, weight: .semibold)
                        .foregroundStyle(palette.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
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

    @ViewBuilder
    private func settingsCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
            Text(title)
                .font(.system(size: DesignTokens.Typography.sectionTitle, weight: .semibold))
                .foregroundStyle(palette.textSecondary)
                .padding(.horizontal, 4)

            content()
                .padding(DesignTokens.Spacing.lg)
                .background(
                    RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                        .fill(palette.panelBg)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                        .strokeBorder(palette.panelBorder, lineWidth: 1)
                )
        }
    }

    @ViewBuilder
    private func toggleRow(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: DesignTokens.Typography.body, weight: .medium))
                    .foregroundStyle(palette.textPrimary)
                Text(subtitle)
                    .font(.system(size: DesignTokens.Typography.micro))
                    .foregroundStyle(palette.textSecondary)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
        }
    }
}
