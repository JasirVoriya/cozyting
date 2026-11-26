import CozyTingAudioKit
import CozyTingCoreKit
import Foundation
import Observation

/// Pending high-volume confirmation request surfaced by L1 `evaluateVolumeGuard`.
public struct PendingVolumeConfirmation: Sendable, Equatable, Identifiable {
    public let id = UUID()
    public let speakerID: String? // `nil` means master group volume
    public let currentVolume: Int
    public let targetVolume: Int
    public let safeLimit: Int
}

/// Central `@MainActor` coordinator integrating L1 discovery, multi-speaker sync engine,
/// `ProcessTapAudioEngine`, 1 Hz stats polling, and volume / delay / source state.
///
/// All blocking L1 calls (`discover`, `connect`, `addReceiver`, `removeReceiver`,
/// `setPlaybackDelay`, `stop`) execute on dedicated background queues inside `CozyTingCoreKit`,
/// keeping the macOS menu bar and popover strictly responsive.
@MainActor
@Observable
public final class CastCoordinator {
    public let preferences: PreferencesStore
    public let logManager: CozyTingLogManager
    public let discoveryClient: CozyTingDiscoveryClient
    public let audioEngine: ProcessTapAudioEngine
    public let sourceCatalog: AppSourceCatalog
    public let nowPlayingBridge: NowPlayingBridge

    // MARK: - Published UI State

    public private(set) var devices: [Device] = []
    public private(set) var isDiscovering: Bool = false
    public private(set) var isCasting: Bool = false
    public private(set) var isPaused: Bool = false
    public private(set) var selectedSpeakerIds: [String] = []
    public private(set) var receiverStatsByIP: [String: ReceiverStats] = [:]
    public private(set) var engineStats: EngineStats?
    public private(set) var lastError: String?
    public private(set) var activeSource: AudioSourceSelection?
    public private(set) var favoriteSources: [FavoriteAudioSource] = []
    public private(set) var autoDetectAudioSource: Bool = true
    public private(set) var hardwareVolumeKeysEnabled: Bool = false
    public private(set) var lastSourceSwitchWasAuto: Bool = false
    public private(set) var activeWifiFrequencyMHz: Int32 = 0

    // Volume & Delay reactive mirrors of PreferencesStore
    public private(set) var masterVolume: Int
    public private(set) var speakerVolumes: [String: Int]
    public private(set) var delayMs: Int
    public private(set) var muteLocalAudio: Bool
    public private(set) var showAllDiscovered: Bool
    public private(set) var volumeGuardEnabled: Bool
    public private(set) var volumeGuardSafeLimit: Int
    public private(set) var authMode: AuthMode
    public private(set) var sourceMode: SourceMode
    public private(set) var themeMode: ThemeMode
    public private(set) var appLanguage: AppLanguage
    public private(set) var lastSelectedSpeakerIds: [String]
    public var pendingVolumeConfirmation: PendingVolumeConfirmation?
    public private(set) var availableUpdateBanner: UpdateDecision?
    public private(set) var hasUpdateBadge: Bool = false
    public var presentedUpdateDecision: UpdateDecision?

    // MARK: - Internal Session Handles

    private var l1Engine: CozyTingEngine?
    private var statsPollingTask: Task<Void, Never>?
    private var connectTask: Task<Void, Never>?
    private var autoDetector: AudioSourceAutoDetector?
    private var hardwareVolumeKeyInterceptor: HardwareVolumeKeyInterceptor?
    private var thisDeviceNameProvider: () -> String

    public init(
        preferences: PreferencesStore,
        logManager: CozyTingLogManager,
        discoveryClient: CozyTingDiscoveryClient = CozyTingDiscoveryClient(),
        thisDeviceNameProvider: @escaping () -> String = { "Mac" }
    ) {
        self.preferences = preferences
        self.logManager = logManager
        self.discoveryClient = discoveryClient
        self.audioEngine = ProcessTapAudioEngine(logger: logManager.runtimeLogger)
        self.sourceCatalog = AppSourceCatalog(preferences: preferences)
        self.nowPlayingBridge = NowPlayingBridge()
        self.thisDeviceNameProvider = thisDeviceNameProvider

        self.volumeGuardEnabled = preferences.volumeGuardEnabled
        self.volumeGuardSafeLimit = preferences.volumeGuardSafeLimit
        self.masterVolume = preferences.loadMasterVolumeForColdStart()
        self.speakerVolumes = preferences.loadSpeakerVolumesForColdStart()
        self.delayMs = preferences.delayMs
        self.muteLocalAudio = preferences.muteLocalAudio
        self.showAllDiscovered = preferences.showAllDiscovered
        self.authMode = preferences.authMode
        self.sourceMode = preferences.sourceMode
        self.themeMode = preferences.themeMode
        self.appLanguage = preferences.appLanguage
        self.lastSelectedSpeakerIds = preferences.loadLastSelectedSpeakerIds()
        self.autoDetectAudioSource = preferences.autoDetectAudioSource
        self.hardwareVolumeKeysEnabled = preferences.hardwareVolumeKeysEnabled

        self.sourceCatalog.refreshCatalog()
        self.favoriteSources = preferences.loadFavoriteAudioSources()
        self.activeSource = preferences.loadSelectedAudioSource()

        CozyTingCoreBridge.setVolumeGuardPolicy(
            enabled: volumeGuardEnabled,
            safeLimit: Int32(volumeGuardSafeLimit)
        )
        CozyTingCoreBridge.setAuthMode(authMode)

        // Hydrate initial list from cached devices immediately on cold start.
        let cached = preferences.loadCachedDevices()
        rebuildDeviceList(from: [makeThisDevice(selected: true)] + cached)

        self.autoDetector = AudioSourceAutoDetector(
            favoritesProvider: { [weak self] in self?.favoriteSources ?? [] },
            currentBundleProvider: { [weak self] in self?.activeSource?.bundleIdentifier },
            enabledProvider: { [weak self] in self?.autoDetectAudioSource ?? false },
            onDetectedBundle: { [weak self] bundleID in
                await self?.handleAutoDetectedAudioSource(bundleIdentifier: bundleID)
            }
        )
        if autoDetectAudioSource {
            self.autoDetector?.start()
        }

        self.hardwareVolumeKeyInterceptor = HardwareVolumeKeyInterceptor(
            isCastingProvider: { [weak self] in self?.isCasting ?? false },
            onVolumeStep: { [weak self] delta in
                await self?.stepMasterVolume(by: delta)
            }
        )
        if hardwareVolumeKeysEnabled {
            let started = self.hardwareVolumeKeyInterceptor?.setEnabled(true) ?? false
            if !started {
                self.hardwareVolumeKeysEnabled = false
                preferences.hardwareVolumeKeysEnabled = false
            }
        }

        self.nowPlayingBridge.startPolling(
            sourceProvider: { [weak self] in self?.activeSource },
            castingStateProvider: { [weak self] in (self?.isCasting ?? false, self?.isPaused ?? false) }
        )
    }

    // MARK: - Computed Helpers

    public var visibleDevices: [Device] {
        DeviceDisplayFormatter.orderedDevices(
            devices,
            lastSelectedIds: lastSelectedSpeakerIds,
            showAllDiscovered: showAllDiscovered
        )
    }

    public var canRestoreLastSelection: Bool {
        guard !isCasting, !lastSelectedSpeakerIds.isEmpty else { return false }
        let availableIDs = Set(devices.filter { $0.kind != .thisDevice && $0.castable }.map(\.id))
        return lastSelectedSpeakerIds.contains { availableIDs.contains($0) }
    }

    public var currentLatencyPreset: LatencyPreset {
        LatencyPreset(delayMs: delayMs)
    }

    public var effectiveDelayMs: Int {
        currentLatencyPreset.effectiveDelayMs(wifiFrequencyMHz: activeWifiFrequencyMHz)
    }

    public var effectiveStartupRunwayAUs: Int {
        currentLatencyPreset.startupRunwayAUs(wifiFrequencyMHz: activeWifiFrequencyMHz)
    }

    public var restorableLastSpeakerCount: Int {
        let availableIDs = Set(devices.filter { $0.kind != .thisDevice && $0.castable }.map(\.id))
        return lastSelectedSpeakerIds.filter { availableIDs.contains($0) }.count
    }

    // MARK: - LAN Discovery

    public func updateWifiFrequencyMHz(_ mhz: Int32) {
        activeWifiFrequencyMHz = mhz
    }

    public func refreshWifiFrequency() {
        let detected = WiFiFrequencyProbe.currentFrequencyMHz()
        activeWifiFrequencyMHz = detected
        logManager.runtimeLogger.info(tag: "WiFi", "Detected Wi-Fi band frequency=\(detected)MHz")
    }

    public func discoverDevices(manual: Bool, timeoutMs: Int32 = 1500) async {
        guard !isDiscovering else { return }
        isDiscovering = true
        defer { isDiscovering = false }

        let localIP = NetworkInterfaceHelper.primaryLocalIPv4()
        refreshWifiFrequency()
        let cachedForMerge: [Device] = manual ? [] : preferences.loadCachedDevices()
        logManager.castOpsLogger.record("DISCOVER start manual=\(manual) local_ip=\(localIP)")

        do {
            let result = try await discoveryClient.discover(
                localIP: localIP,
                timeoutMs: timeoutMs,
                cachedDevices: cachedForMerge,
                forceRefreshNames: manual
            )
            preferences.saveCachedDevices(result.cacheable)

            var incoming = result.merged
            // On manual refresh, preserve currently active/selected speakers so live cast UI stays intact.
            if manual && !selectedSpeakerIds.isEmpty {
                let incomingIDs = Set(incoming.map(\.id))
                for existing in devices where selectedSpeakerIds.contains(existing.id) && !incomingIDs.contains(existing.id) {
                    incoming.append(existing)
                }
            }

            let mergedDevices: [Device]
            if isCasting {
                let transition = try CozyTingCoreBridge.routeApply(
                    devices: devices,
                    action: .mergeDiscovered,
                    target: encodeDevicesJSON(incoming)
                )
                mergedDevices = transition.state.devices
            } else {
                mergedDevices = [makeThisDevice(selected: true)] + incoming
            }

            rebuildDeviceList(from: mergedDevices)
            logManager.castOpsLogger.record(
                "DISCOVER done manual=\(manual) fresh=\(result.freshCount) castable=\(result.castableCount) hidden=\(result.hiddenCount)"
            )
        } catch {
            lastError = String(describing: error)
            logManager.runtimeLogger.warn(tag: "Discovery", "Discovery failed: \(error)")
        }
    }

    /// Manually adds a speaker by IPv4 address for subnets where mDNS is restricted.
    @discardableResult
    public func addManualSpeaker(ip rawIP: String, name rawName: String = "") -> Bool {
        let ip = rawIP.trimmingCharacters(in: .whitespacesAndNewlines)
        guard NetworkInterfaceHelper.isValidIPv4(ip) else { return false }
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        let manualDevice = Device(
            id: ip,
            name: name.isEmpty ? ip : name,
            kind: .speaker,
            castable: true,
            selected: false,
            detail: "manual",
            protocol: .miplay
        )

        var updated = devices.filter { $0.id != ip }
        updated.append(manualDevice)
        rebuildDeviceList(from: updated)
        preferences.saveCachedDevices(updated.filter { $0.kind != .thisDevice })
        logManager.castOpsLogger.record("MANUAL_SPEAKER added ip=\(ip)")
        return true
    }

    // MARK: - Route Selection & Multi-Speaker Sync

    public func selectThisDevice() async {
        do {
            let transition = try CozyTingCoreBridge.routeApply(
                devices: devices,
                action: .selectThis
            )
            applyRouteState(transition.state)
            await executeRouteEffects(transition.effects)
        } catch {
            lastError = String(describing: error)
        }
    }

    public func toggleSpeaker(id: String) async {
        guard let target = devices.first(where: { $0.id == id }) else { return }
        let action: RouteAction = target.selected ? .uncheck : .check
        do {
            let transition = try CozyTingCoreBridge.routeApply(
                devices: devices,
                action: action,
                target: id
            )
            applyRouteState(transition.state)
            await executeRouteEffects(transition.effects)
        } catch {
            lastError = String(describing: error)
        }
    }

    public func restoreLastSelectedSpeakers() async {
        let availableIDs = Set(devices.filter { $0.kind != .thisDevice && $0.castable }.map(\.id))
        let targets = lastSelectedSpeakerIds.filter { availableIDs.contains($0) }
        guard !targets.isEmpty else { return }

        var working = devices
        var combinedEffects: [RouteEffect] = []
        for id in targets {
            if let transition = try? CozyTingCoreBridge.routeApply(
                devices: working,
                action: .check,
                target: id
            ) {
                working = transition.state.devices
                combinedEffects.append(contentsOf: transition.effects)
            }
        }
        rebuildDeviceList(from: working)
        selectedSpeakerIds = targets
        await startCastSession(speakerIds: targets)
    }

    // MARK: - Audio Source Selection, Favorites & Auto-Detect Hot Switch

    public func selectAudioSource(_ selection: AudioSourceSelection, manual: Bool = true) async {
        let previous = activeSource
        if manual {
            autoDetector?.recordManualSelection(bundleIdentifier: selection.bundleIdentifier)
        }
        lastSourceSwitchWasAuto = !manual

        if isCasting, sourceMode == .app, let engine = l1Engine {
            if previous?.bundleIdentifier.caseInsensitiveCompare(selection.bundleIdentifier) == .orderedSame {
                activeSource = selection
                preferences.saveSelectedAudioSource(selection)
                return
            }
            do {
                try audioEngine.hotSwitchTargetApp(
                    bundleIdentifier: selection.bundleIdentifier,
                    muteLocalAudio: muteLocalAudio
                )
                _ = try? await engine.switchSource()
                activeSource = selection
                preferences.saveSelectedAudioSource(selection)
                lastError = nil
                logManager.castOpsLogger.record(
                    "SOURCE hot_switch bundle=\(selection.bundleIdentifier) auto=\(!manual)"
                )
            } catch {
                // Fail-closed: preserve previous source metadata when hot-switch fails.
                lastError = String(describing: error)
                logManager.runtimeLogger.warn(
                    tag: "Cast",
                    "Hot-switch failed bundle=\(selection.bundleIdentifier) err=\(error)"
                )
            }
            return
        }

        activeSource = selection
        preferences.saveSelectedAudioSource(selection)
        logManager.castOpsLogger.record("SOURCE selected bundle=\(selection.bundleIdentifier) auto=\(!manual)")
    }

    public func handleAutoDetectedAudioSource(bundleIdentifier: String) async {
        let name = sourceCatalog.displayName(for: bundleIdentifier)
        await selectAudioSource(
            AudioSourceSelection(bundleIdentifier: bundleIdentifier, displayName: name),
            manual: false
        )
    }

    public func setAutoDetectAudioSource(_ enabled: Bool) {
        autoDetectAudioSource = enabled
        preferences.autoDetectAudioSource = enabled
        if enabled {
            autoDetector?.start()
        } else {
            autoDetector?.stop()
        }
    }

    public func addFavoriteSource(bundleIdentifier: String) {
        favoriteSources = preferences.addFavoriteAudioSource(bundleIdentifier: bundleIdentifier, autoDetectEnabled: true)
    }

    public func removeFavoriteSource(bundleIdentifier: String) {
        favoriteSources = preferences.removeFavoriteAudioSource(bundleIdentifier: bundleIdentifier)
    }

    public func setFavoriteAutoDetect(bundleIdentifier: String, enabled: Bool) {
        favoriteSources = preferences.setFavoriteAudioSourceAutoDetect(
            bundleIdentifier: bundleIdentifier,
            enabled: enabled
        )
    }

    public func reorderFavoriteSource(from: String, to: String) {
        favoriteSources = preferences.reorderFavoriteAudioSources(
            fromBundleIdentifier: from,
            toBundleIdentifier: to
        )
    }

    public func setHardwareVolumeKeysEnabled(_ enabled: Bool) -> Bool {
        if enabled {
            let ok = hardwareVolumeKeyInterceptor?.setEnabled(true) ?? false
            hardwareVolumeKeysEnabled = ok
            preferences.hardwareVolumeKeysEnabled = ok
            return ok
        } else {
            hardwareVolumeKeyInterceptor?.setEnabled(false)
            hardwareVolumeKeysEnabled = false
            preferences.hardwareVolumeKeysEnabled = false
            return true
        }
    }

    public func stopBackgroundMonitors() {
        autoDetector?.stop()
        nowPlayingBridge.stopPolling()
        hardwareVolumeKeyInterceptor?.stopTap()
    }

    public func setMuteLocalAudio(_ mute: Bool) {
        muteLocalAudio = mute
        preferences.muteLocalAudio = mute
        if isCasting && sourceMode == .app {
            try? audioEngine.setMuteLocalAudio(mute)
        }
    }

    // MARK: - Group Playback Controls

    public func togglePauseResume() async {
        guard isCasting, let engine = l1Engine else { return }
        do {
            if isPaused {
                _ = try await engine.resume()
                isPaused = false
                logManager.castOpsLogger.record("CAST resume")
            } else {
                _ = try await engine.pause()
                isPaused = true
                logManager.castOpsLogger.record("CAST pause")
            }
        } catch {
            lastError = String(describing: error)
        }
    }

    public func setPlaybackDelay(_ newDelayMs: Int) async {
        let clean = max(newDelayMs, 0)
        delayMs = clean
        preferences.delayMs = clean
        logManager.castOpsLogger.record("DELAY set ms=\(clean)")

        guard isCasting, let engine = l1Engine else { return }
        let resolvedDelay = Int32(LatencyPreset(delayMs: clean).effectiveDelayMs(wifiFrequencyMHz: activeWifiFrequencyMHz))
        do {
            _ = try await engine.setPlaybackDelay(delayMs: clean == 0 ? 0 : resolvedDelay)
        } catch {
            lastError = String(describing: error)
        }
    }

    // MARK: - Volume Control & High-Volume Guard (L1 Adjudicated)

    public func requestMasterVolume(_ targetVolume: Int, confirmed: Bool = false) async {
        let evaluation = VolumeGroupEvaluator.evaluateMasterChange(
            currentMaster: masterVolume,
            targetMaster: targetVolume,
            selectedSpeakerIds: selectedSpeakerIds,
            speakerVolumes: speakerVolumes,
            guardEnabled: volumeGuardEnabled,
            safeLimit: volumeGuardSafeLimit,
            confirmed: confirmed
        )
        switch evaluation {
        case let .requireConfirm(current, target, safeLimit):
            pendingVolumeConfirmation = PendingVolumeConfirmation(
                speakerID: nil,
                currentVolume: current,
                targetVolume: target,
                safeLimit: safeLimit
            )
            return

        case let .allow(clampedMaster, speakerTargets):
            masterVolume = clampedMaster
            preferences.saveMasterVolume(clampedMaster)

            guard isCasting, let engine = l1Engine else { return }
            for ip in selectedSpeakerIds {
                let nextSpeaker = speakerTargets[ip] ?? clampedMaster
                speakerVolumes[ip] = nextSpeaker
                preferences.saveSpeakerVolume(deviceID: ip, volume: nextSpeaker)
                _ = try? await engine.setVolume(ip: ip, volume: Int32(nextSpeaker), confirmed: confirmed)
            }
        }
    }

    public func stepMasterVolume(by delta: Int) async {
        await requestMasterVolume(masterVolume + delta, confirmed: false)
    }

    public func requestSpeakerVolume(speakerID: String, targetVolume: Int, confirmed: Bool = false) async {
        let clamped = min(max(targetVolume, 0), 100)
        let current = speakerVolumes[speakerID] ?? masterVolume
        if !confirmed {
            if let decision = try? CozyTingCoreBridge.evaluateVolumeGuard(
                enabled: volumeGuardEnabled,
                safeLimit: Int32(volumeGuardSafeLimit),
                currentVolume: Int32(current),
                targetVolume: Int32(clamped)
            ), case let .requireConfirm(curr, target, safeLimit) = decision {
                pendingVolumeConfirmation = PendingVolumeConfirmation(
                    speakerID: speakerID,
                    currentVolume: curr,
                    targetVolume: target,
                    safeLimit: safeLimit
                )
                return
            }
        }

        speakerVolumes[speakerID] = clamped
        preferences.saveSpeakerVolume(deviceID: speakerID, volume: clamped)

        guard isCasting, let engine = l1Engine else { return }
        _ = try? await engine.setVolume(ip: speakerID, volume: Int32(clamped), confirmed: confirmed)
    }

    public func confirmPendingHighVolume() async {
        guard let pending = pendingVolumeConfirmation else { return }
        pendingVolumeConfirmation = nil
        if let speakerID = pending.speakerID {
            await requestSpeakerVolume(
                speakerID: speakerID,
                targetVolume: pending.targetVolume,
                confirmed: true
            )
        } else {
            await requestMasterVolume(pending.targetVolume, confirmed: true)
        }
    }

    public func cancelPendingHighVolume() {
        pendingVolumeConfirmation = nil
    }

    // MARK: - Settings Sync

    public func setVolumeGuard(enabled: Bool, safeLimit: Int) {
        let snapped = VolumeGuardPolicy.snapSafeLimit(safeLimit)
        volumeGuardEnabled = enabled
        volumeGuardSafeLimit = snapped
        preferences.volumeGuardEnabled = enabled
        preferences.volumeGuardSafeLimit = snapped
        CozyTingCoreBridge.setVolumeGuardPolicy(enabled: enabled, safeLimit: Int32(snapped))
    }

    public func setShowAllDiscovered(_ showAll: Bool) {
        showAllDiscovered = showAll
        preferences.showAllDiscovered = showAll
    }

    public func setAuthMode(_ mode: AuthMode) {
        authMode = mode
        preferences.authMode = mode
        CozyTingCoreBridge.setAuthMode(mode)
    }

    public func setSourceMode(_ mode: SourceMode) {
        sourceMode = mode
        preferences.sourceMode = mode
    }

    public func setThemeMode(_ mode: ThemeMode) {
        themeMode = mode
        preferences.themeMode = mode
    }

    public func setAppLanguage(_ language: AppLanguage) {
        appLanguage = language
        preferences.appLanguage = language
    }

    public func syncUpdatePresentation(from updateService: UpdateService) async {
        availableUpdateBanner = await updateService.activeBannerDecision()
        hasUpdateBadge = await updateService.shouldShowUpdateBadge()
    }

    public func dismissUpdateBanner(using updateService: UpdateService) async {
        if let tag = availableUpdateBanner?.latestTag {
            await updateService.dismissBanner(for: tag)
        }
        availableUpdateBanner = nil
    }

    public func skipUpdateVersion(_ tag: String, using updateService: UpdateService) async {
        _ = await updateService.ignoreVersion(tag)
        availableUpdateBanner = nil
        hasUpdateBadge = false
        presentedUpdateDecision = nil
    }

    // MARK: - Teardown (Strict 4-Step Deadlock-Free Stop)

    public func stopCast() async {
        connectTask?.cancel()
        connectTask = nil
        statsPollingTask?.cancel()
        statsPollingTask = nil

        // Step 1–2: Stop ProcessTapAudioEngine & AAC pipeline first.
        audioEngine.stopCapture()

        // Step 3: Stop L1 CozyTingEngine cleanly.
        if let engine = l1Engine {
            l1Engine = nil
            await engine.stop()
        }

        // Step 4: Clear reactive cast state.
        isCasting = false
        isPaused = false
        selectedSpeakerIds = []
        receiverStatsByIP = [:]
        engineStats = nil
        let resetDevices = devices.map { d in
            Device(
                id: d.id,
                name: d.name,
                kind: d.kind,
                castable: d.castable,
                selected: d.kind == .thisDevice,
                detail: d.detail,
                protocol: d.protocol,
                nameFromDeviceInfo: d.nameFromDeviceInfo,
                roomName: d.roomName,
                roomInfoQueried: d.roomInfoQueried
            )
        }
        rebuildDeviceList(from: resetDevices)
        logManager.castOpsLogger.record("CAST stopped")
    }

    // MARK: - Private Route Execution & 1 Hz Polling

    private func executeRouteEffects(_ effects: [RouteEffect]) async {
        for effect in effects {
            switch effect {
            case .stopCastToThisDevice:
                await stopCast()
            case .startCast(let speakerIds):
                await startCastSession(speakerIds: speakerIds)
            case .addSpeaker(let id):
                await addSpeakerInFlight(id: id)
            case .removeSpeaker(let id):
                await removeSpeakerInFlight(id: id)
            case .rejectNonCastable(let id, let reason):
                lastError = "\(id): \(reason)"
                logManager.runtimeLogger.warn(tag: "Route", "Rejected non-castable device id=\(id) reason=\(reason)")
            }
        }
    }

    private func startCastSession(speakerIds: [String]) async {
        guard !speakerIds.isEmpty else { return }

        // Stop any stale session first using strict 4-step order.
        if l1Engine != nil {
            await stopCast()
        }

        let isSilent = (sourceMode == .silent)
        if !isSilent && (activeSource == nil || activeSource!.bundleIdentifier.isEmpty) {
            lastError = "No audio source selected"
            await stopCast()
            return
        }

        let engine = CozyTingEngine(silent: isSilent)
        self.l1Engine = engine
        self.isCasting = true
        self.isPaused = false
        self.selectedSpeakerIds = speakerIds
        self.lastSelectedSpeakerIds = speakerIds
        self.lastError = nil
        preferences.saveLastSelectedSpeakerIds(speakerIds)

        if !isSilent, let source = activeSource {
            do {
                try audioEngine.startCapture(
                    bundleIdentifier: source.bundleIdentifier,
                    muteLocalAudio: muteLocalAudio
                ) { frame in
                    engine.pushAac(frame.rawAccessUnit)
                }
            } catch {
                lastError = String(describing: error)
                logManager.runtimeLogger.error(tag: "ProcessTap", "Failed to start capture: \(error)")
                await stopCast()
                return
            }
        }

        let initialDelay: Int32 = delayMs == 0
            ? Int32(LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: activeWifiFrequencyMHz))
            : Int32(delayMs)
        let targetVol = Int32(masterVolume)
        let refFunc = preferences.refFunction
        let sourceName = activeSource?.displayName ?? "CozyTing"

        logManager.castOpsLogger.record(
            "CAST start speakers=\(speakerIds.joined(separator: ",")) delay_ms=\(initialDelay) vol=\(targetVol) silent=\(isSilent)"
        )

        startStatsPolling(engine: engine)

        let initialVolumeConfirmed = !volumeGuardEnabled || Int(targetVol) <= volumeGuardSafeLimit
        connectTask = Task { [weak self] in
            do {
                let stats = try await engine.connect(
                    ips: speakerIds,
                    delayMs: initialDelay,
                    premute: 0,
                    volumeAfterPlay: targetVol,
                    refFunction: refFunc,
                    sourceName: sourceName,
                    volumeConfirmed: initialVolumeConfirmed
                )
                guard let self, !Task.isCancelled else { return }
                self.engineStats = stats
                self.applyReceiverStats(stats.receivers)
                // Apply per-speaker saved volumes if distinct from master.
                for ip in speakerIds {
                    if let saved = self.speakerVolumes[ip], saved != Int(targetVol) {
                        _ = try? await engine.setVolume(
                            ip: ip,
                            volume: Int32(saved),
                            confirmed: !self.volumeGuardEnabled || saved <= self.volumeGuardSafeLimit
                        )
                    }
                }
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.lastError = String(describing: error)
                self.logManager.runtimeLogger.error(tag: "Cast", "Connect failed: \(error)")
            }
        }
    }

    private func addSpeakerInFlight(id: String) async {
        guard let engine = l1Engine else {
            await startCastSession(speakerIds: selectedSpeakerIds)
            return
        }
        lastSelectedSpeakerIds = selectedSpeakerIds
        preferences.saveLastSelectedSpeakerIds(selectedSpeakerIds)
        logManager.castOpsLogger.record("CAST add_speaker ip=\(id)")
        do {
            _ = try await engine.addReceiver(ip: id)
            let targetVol = speakerVolumes[id] ?? masterVolume
            _ = try? await engine.setVolume(
                ip: id,
                volume: Int32(targetVol),
                confirmed: !volumeGuardEnabled || targetVol <= volumeGuardSafeLimit
            )
        } catch {
            lastError = String(describing: error)
        }
    }

    private func removeSpeakerInFlight(id: String) async {
        guard let engine = l1Engine else { return }
        logManager.castOpsLogger.record("CAST remove_speaker ip=\(id)")
        _ = await engine.removeReceiver(ip: id)
        receiverStatsByIP.removeValue(forKey: id)
        if !selectedSpeakerIds.isEmpty {
            lastSelectedSpeakerIds = selectedSpeakerIds
            preferences.saveLastSelectedSpeakerIds(selectedSpeakerIds)
        }
    }

    private func startStatsPolling(engine: CozyTingEngine) {
        statsPollingTask?.cancel()
        statsPollingTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    let envelope = try await engine.stats()
                    guard let self, !Task.isCancelled else { return }
                    switch envelope {
                    case .active(let stats):
                        self.engineStats = stats
                        self.isPaused = stats.paused
                        self.applyReceiverStats(stats.receivers)
                    case .preSessionQueued(let receivers):
                        self.applyReceiverStats(receivers)
                    case .error(let msg):
                        self.lastError = msg
                    }
                } catch {
                    // Transient poll failure while stopping; loop exits on cancellation.
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private func applyReceiverStats(_ receivers: [ReceiverStats]) {
        var next: [String: ReceiverStats] = [:]
        for r in receivers {
            next[r.ip] = r
        }
        receiverStatsByIP = next
    }

    private func applyRouteState(_ state: RouteState) {
        rebuildDeviceList(from: state.devices)
        selectedSpeakerIds = state.selectedSpeakerIds
    }

    private func rebuildDeviceList(from raw: [Device]) {
        var hasThis = false
        var dedup: [Device] = []
        var seen: Set<String> = []
        for d in raw {
            if d.kind == .thisDevice { hasThis = true }
            if seen.insert(d.id).inserted {
                dedup.append(d)
            }
        }
        if !hasThis {
            dedup.insert(makeThisDevice(selected: selectedSpeakerIds.isEmpty), at: 0)
        }
        devices = dedup
    }

    private func makeThisDevice(selected: Bool) -> Device {
        DeviceDisplayFormatter.makeThisDevice(
            displayName: thisDeviceNameProvider(),
            selected: selected
        )
    }

    private func encodeDevicesJSON(_ list: [Device]) -> String {
        guard let data = try? JSONEncoder().encode(list),
              let json = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return json
    }
}
