import CoreAudio
import CozyTingCoreKit
import Foundation

/// Monitors Core Audio `kAudioProcessPropertyIsRunningOutput` across running processes
/// and drives debounced, whitelisted auto-detection + live hot-switching (`M6`).
@MainActor
public final class AudioSourceAutoDetector {
    private var policy = AudioSourceAutoDetectPolicy(debounceSeconds: 0.7, manualCooldownSeconds: 4.0)
    private var monitorTask: Task<Void, Never>?
    private var processListListenerBlock: AudioObjectPropertyListenerBlock?
    private var isStarted = false

    private let onDetectedBundle: @MainActor (String) async -> Void
    private let favoritesProvider: @MainActor () -> [FavoriteAudioSource]
    private let currentBundleProvider: @MainActor () -> String?
    private let enabledProvider: @MainActor () -> Bool

    public init(
        favoritesProvider: @escaping @MainActor () -> [FavoriteAudioSource],
        currentBundleProvider: @escaping @MainActor () -> String?,
        enabledProvider: @escaping @MainActor () -> Bool,
        onDetectedBundle: @escaping @MainActor (String) async -> Void
    ) {
        self.favoritesProvider = favoritesProvider
        self.currentBundleProvider = currentBundleProvider
        self.enabledProvider = enabledProvider
        self.onDetectedBundle = onDetectedBundle
    }

    deinit {
        monitorTask?.cancel()
    }

    public func start() {
        guard !isStarted else { return }
        isStarted = true
        installProcessListListener()

        monitorTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                await self?.sampleOnce()
                try? await Task.sleep(for: .milliseconds(350))
            }
        }
    }

    public func stop() {
        guard isStarted else { return }
        isStarted = false
        monitorTask?.cancel()
        monitorTask = nil
        removeProcessListListener()
        policy.reset()
    }

    public func recordManualSelection(bundleIdentifier: String) {
        policy.recordManualSelection(bundleIdentifier: bundleIdentifier, at: ProcessInfo.processInfo.systemUptime)
    }

    private func sampleOnce() async {
        guard enabledProvider() else {
            policy.reset()
            return
        }
        let favorites = favoritesProvider()
        guard favorites.contains(where: \.autoDetectEnabled) else { return }

        let outputting = ProcessTreeResolver.activeOutputBundleIdentifiers()
        let now = ProcessInfo.processInfo.systemUptime
        if let targetBundle = policy.evaluate(
            activeOutputBundles: outputting,
            favorites: favorites,
            currentBundleID: currentBundleProvider(),
            globalEnabled: true,
            now: now
        ) {
            await onDetectedBundle(targetBundle)
        }
    }

    private func installProcessListListener() {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyProcessObjectList,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            Task { @MainActor [weak self] in
                await self?.sampleOnce()
            }
        }
        let status = AudioObjectAddPropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            DispatchQueue.main,
            block
        )
        if status == noErr {
            processListListenerBlock = block
        }
    }

    private func removeProcessListListener() {
        guard let block = processListListenerBlock else { return }
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyProcessObjectList,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        AudioObjectRemovePropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            DispatchQueue.main,
            block
        )
        processListListenerBlock = nil
    }
}
