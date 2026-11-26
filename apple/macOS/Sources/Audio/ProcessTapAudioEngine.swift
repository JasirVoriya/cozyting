import CoreAudio
import CoreAudioTypes
import CozyTingAudioKit
import CozyTingCoreKit
import Foundation

/// Errors thrown by `ProcessTapAudioEngine` (strictly Fail-Closed).
public enum ProcessTapError: Error, Sendable, Equatable {
    case invalidBundleIdentifier
    case targetAppNotRunning(bundleIdentifier: String)
    case permissionDeniedOrTapCreationFailed(status: OSStatus)
    case aggregateDeviceCreationFailed(status: OSStatus)
    case streamFormatUnavailable(status: OSStatus)
    case ioProcRegistrationFailed(status: OSStatus)
    case deviceStartFailed(status: OSStatus)
}

/// Real-time capture state reported by `ProcessTapAudioEngine`.
public struct ProcessTapStatus: Sendable, Equatable {
    public let isCapturing: Bool
    public let isStandbyWaitingForAudio: Bool
    public let targetBundleIdentifier: String?
    public let tappedProcessCount: Int
    public let muteLocalAudio: Bool
    public let sampleRate: Double
    public let channelCount: UInt32
    public let pipelineStats: AudioPipelineStats?
}

/// Production macOS 14.2+ single-app Core Audio Process Tap capture engine.
///
/// Enforces all `apple/AGENTS.md` §3–§4 red lines:
/// - **Fail-Closed single-app isolation**: captures ONLY the target app's process tree via
///   `AudioHardwareCreateProcessTap` + `CATapDescription(stereoMixdownOfProcesses:)`.
/// - **Standby / Armed mode**: if the target app IS running but has not yet created a Core Audio
///   process node (user hasn't pressed Play yet), stays strictly fail-closed (zero global/mic
///   capture) and automatically attaches the single-app Process Tap the moment audio starts.
/// - **True per-app local mute**: `.mutedWhenTapped` when `muteLocalAudio == true`, protected by `MuteGuard`.
/// - **Real-time IOProc discipline**: zero heap allocation, zero locks, zero I/O inside `IOProc`;
///   writes directly into `LockFreeRingBuffer` for `AudioEncodingPipeline`.
/// - **Off-MainActor control queue**: async wrappers ensure `AudioDeviceStart` / TCC permission
///   prompts and HAL teardown never freeze the macOS UI thread.
public final class ProcessTapAudioEngine: @unchecked Sendable {
    private let stateLock = NSLock()
    private let logger: RuntimeLogger?

    private var tapID: AudioObjectID = kAudioObjectUnknown
    private var aggregateDeviceID: AudioObjectID = kAudioObjectUnknown
    private var ioProcID: AudioDeviceIOProcID?
    private var tapDescription: CATapDescription?
    private var tapUUIDString: String = ""

    private var currentBundleID: String?
    private var currentMuteLocalAudio: Bool = true
    private var currentTappedObjectIDs: [AudioObjectID] = []
    private var isStandby: Bool = false
    private var streamASBD = AudioStreamBasicDescription()

    private var pipeline: AudioEncodingPipeline?
    private var captureErrorHandler: (@Sendable (Error) -> Void)?
    private var processListListenerBlock: AudioObjectPropertyListenerBlock?
    private var standbyTimer: DispatchSourceTimer?
    private let listenerQueue = DispatchQueue(label: "com.cozyting.macos.process-tree-listener", qos: .utility)
    private let controlQueue = DispatchQueue(label: "com.cozyting.macos.process-tap-control", qos: .userInitiated)

    // Pre-allocated RT context pointer read strictly by the Core Audio IOProc callback.
    private final class RTIOContext {
        let pipeline: AudioEncodingPipeline
        let asbd: AudioStreamBasicDescription
        let stoppingFlag: AtomicFlag
        var sampleTimeCounter: Int64 = 0

        init(
            pipeline: AudioEncodingPipeline,
            asbd: AudioStreamBasicDescription,
            stoppingFlag: AtomicFlag
        ) {
            self.pipeline = pipeline
            self.asbd = asbd
            self.stoppingFlag = stoppingFlag
        }
    }

    private var rtContext: RTIOContext?
    private var rtStoppingFlag = AtomicFlag(false)

    public init(logger: RuntimeLogger? = nil) {
        self.logger = logger
        MuteGuard.shared.installTerminationHooks()
    }

    deinit {
        stopCapture()
    }

    // MARK: - Async Non-Blocking Wrappers (Never Block @MainActor)

    public func startCaptureAsync(
        bundleIdentifier: String,
        muteLocalAudio: Bool,
        onError: (@Sendable (Error) -> Void)? = nil,
        sink: @escaping AudioEncodingPipeline.FrameSink
    ) async throws {
        try await withCheckedThrowingContinuation { continuation in
            controlQueue.async {
                do {
                    try self.startCapture(
                        bundleIdentifier: bundleIdentifier,
                        muteLocalAudio: muteLocalAudio,
                        onError: onError,
                        sink: sink
                    )
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    public func hotSwitchTargetAppAsync(
        bundleIdentifier: String,
        muteLocalAudio: Bool
    ) async throws {
        try await withCheckedThrowingContinuation { continuation in
            controlQueue.async {
                do {
                    try self.hotSwitchTargetApp(
                        bundleIdentifier: bundleIdentifier,
                        muteLocalAudio: muteLocalAudio
                    )
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    public func setMuteLocalAudioAsync(_ muteLocalAudio: Bool) async throws {
        try await withCheckedThrowingContinuation { continuation in
            controlQueue.async {
                do {
                    try self.setMuteLocalAudio(muteLocalAudio)
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    public func stopCaptureAsync() async {
        await withCheckedContinuation { continuation in
            controlQueue.async {
                self.stopCapture()
                continuation.resume()
            }
        }
    }

    // MARK: - Synchronous Core Audio Capture API

    /// Starts capturing audio exclusively from `bundleIdentifier` and feeding raw 48 kHz AAC-LC AUs to `sink`.
    public func startCapture(
        bundleIdentifier: String,
        muteLocalAudio: Bool,
        onError: (@Sendable (Error) -> Void)? = nil,
        sink: @escaping AudioEncodingPipeline.FrameSink
    ) throws {
        let cleanBundle = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanBundle.isEmpty else {
            throw ProcessTapError.invalidBundleIdentifier
        }

        // Ensure clean slate before creating new tap.
        stopCapture()

        let objectIDs = ProcessTreeResolver.resolveAudioObjectIDs(forBundleIdentifier: cleanBundle)
        let appRunning = ProcessTreeResolver.isApplicationRunning(bundleIdentifier: cleanBundle)
        guard !objectIDs.isEmpty || appRunning else {
            throw ProcessTapError.targetAppNotRunning(bundleIdentifier: cleanBundle)
        }

        let newPipeline = try AudioEncodingPipeline(sink: sink)
        newPipeline.start()

        stateLock.withLock {
            self.captureErrorHandler = onError
            self.pipeline = newPipeline
            self.currentBundleID = cleanBundle
            self.currentMuteLocalAudio = muteLocalAudio
        }

        if objectIDs.isEmpty {
            // App IS running but hasn't registered a Core Audio process node yet (before Play is pressed).
            // Stay strictly fail-closed in armed standby and attach the single-app tap automatically
            // the instant coreaudiod creates a process object for this app.
            stateLock.withLock {
                self.isStandby = true
                self.currentTappedObjectIDs = []
            }
            installProcessListListener()
            startStandbyTimer()
            logger?.info(
                tag: "ProcessTap",
                "Armed standby for running app bundle=\(cleanBundle) (waiting for audio node) mute=\(muteLocalAudio)"
            )
            return
        }

        do {
            try attachTapAndStartIO(
                bundleIdentifier: cleanBundle,
                processObjectIDs: objectIDs,
                muteLocalAudio: muteLocalAudio,
                pipeline: newPipeline
            )
            installProcessListListener()
            logger?.info(
                tag: "ProcessTap",
                "Started capture bundle=\(cleanBundle) processes=\(objectIDs.count) mute=\(muteLocalAudio)"
            )
        } catch {
            stateLock.withLock {
                self.pipeline = nil
                self.currentBundleID = nil
                self.captureErrorHandler = nil
                self.isStandby = false
            }
            newPipeline.stop()
            throw error
        }
    }

    /// Hot-switches the tapped target app (or mute mode) WITHOUT stopping the L1 `CozyTingEngine` session.
    public func hotSwitchTargetApp(
        bundleIdentifier: String,
        muteLocalAudio: Bool
    ) throws {
        let cleanBundle = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanBundle.isEmpty else {
            throw ProcessTapError.invalidBundleIdentifier
        }

        let existingPipeline: AudioEncodingPipeline? = stateLock.withLock { pipeline }
        guard let existingPipeline else {
            throw ProcessTapError.targetAppNotRunning(bundleIdentifier: cleanBundle)
        }

        let objectIDs = ProcessTreeResolver.resolveAudioObjectIDs(forBundleIdentifier: cleanBundle)
        let appRunning = ProcessTreeResolver.isApplicationRunning(bundleIdentifier: cleanBundle)
        guard !objectIDs.isEmpty || appRunning else {
            throw ProcessTapError.targetAppNotRunning(bundleIdentifier: cleanBundle)
        }

        stopStandbyTimer()
        teardownTapOnly()
        existingPipeline.requestReset()

        if objectIDs.isEmpty {
            stateLock.withLock {
                self.currentBundleID = cleanBundle
                self.currentMuteLocalAudio = muteLocalAudio
                self.currentTappedObjectIDs = []
                self.isStandby = true
            }
            startStandbyTimer()
            logger?.info(
                tag: "ProcessTap",
                "Hot-switched to standby bundle=\(cleanBundle) (waiting for audio node) mute=\(muteLocalAudio)"
            )
            return
        }

        try attachTapAndStartIO(
            bundleIdentifier: cleanBundle,
            processObjectIDs: objectIDs,
            muteLocalAudio: muteLocalAudio,
            pipeline: existingPipeline
        )
        logger?.info(
            tag: "ProcessTap",
            "Hot-switched capture bundle=\(cleanBundle) processes=\(objectIDs.count) mute=\(muteLocalAudio)"
        )
    }

    /// Updates local mute behavior while capturing the same target app.
    public func setMuteLocalAudio(_ muteLocalAudio: Bool) throws {
        let (bundle, standby): (String?, Bool) = stateLock.withLock { (currentBundleID, isStandby) }
        guard let bundle else { return }
        if standby {
            stateLock.withLock { currentMuteLocalAudio = muteLocalAudio }
            return
        }
        try hotSwitchTargetApp(bundleIdentifier: bundle, muteLocalAudio: muteLocalAudio)
    }

    /// Deadlock-free Steps 1–2 of `apple/AGENTS.md` §3.3:
    /// 1) Flip atomic stopping flag so IOProc becomes an immediate no-op.
    /// 2) Stop/destroy IOProc, Aggregate Device, and Process Tap (`MuteGuard`), then drain encoder loop.
    public func stopCapture() {
        stopStandbyTimer()
        removeProcessListListener()
        teardownTapOnly()

        let pipelineToStop: AudioEncodingPipeline? = stateLock.withLock {
            let p = pipeline
            pipeline = nil
            captureErrorHandler = nil
            currentBundleID = nil
            currentTappedObjectIDs = []
            isStandby = false
            return p
        }
        pipelineToStop?.stop()
    }

    /// Convenience helper enforcing the full 4-step deadlock-free stop sequence with an L1 `CozyTingEngine`.
    public func stopCaptureAndEngine(_ engine: CozyTingEngine) async {
        await stopCaptureAsync()
        await engine.stop()
    }

    public func status() -> ProcessTapStatus {
        stateLock.withLock {
            ProcessTapStatus(
                isCapturing: tapID != kAudioObjectUnknown && ioProcID != nil,
                isStandbyWaitingForAudio: isStandby && pipeline != nil,
                targetBundleIdentifier: currentBundleID,
                tappedProcessCount: currentTappedObjectIDs.count,
                muteLocalAudio: currentMuteLocalAudio,
                sampleRate: streamASBD.mSampleRate,
                channelCount: streamASBD.mChannelsPerFrame,
                pipelineStats: pipeline?.stats()
            )
        }
    }

    // MARK: - Core Audio Tap + Aggregate Device Lifecycle

    private func attachTapAndStartIO(
        bundleIdentifier: String,
        processObjectIDs: [AudioObjectID],
        muteLocalAudio: Bool,
        pipeline: AudioEncodingPipeline
    ) throws {
        let tapDesc = CATapDescription(stereoMixdownOfProcesses: processObjectIDs)
        tapDesc.name = "CozyTing-\(bundleIdentifier)"
        tapDesc.uuid = UUID()
        tapDesc.isPrivate = true
        tapDesc.isExclusive = false
        tapDesc.muteBehavior = muteLocalAudio ? .mutedWhenTapped : .unmuted

        var createdTapID = AudioObjectID(kAudioObjectUnknown)
        let tapStatus = AudioHardwareCreateProcessTap(tapDesc, &createdTapID)
        guard tapStatus == noErr, createdTapID != kAudioObjectUnknown else {
            throw ProcessTapError.permissionDeniedOrTapCreationFailed(status: tapStatus)
        }

        let tapUUID = readTapUID(for: createdTapID) ?? tapDesc.uuid.uuidString
        let createdAggID: AudioObjectID
        do {
            createdAggID = try createTapAggregateDevice(tapUUID: tapUUID)
        } catch {
            _ = AudioHardwareDestroyProcessTap(createdTapID)
            throw error
        }

        MuteGuard.shared.register(tapID: createdTapID, aggregateDeviceID: createdAggID)

        // Read actual stream format exposed by the tap / aggregate device.
        let asbd: AudioStreamBasicDescription
        do {
            asbd = try readTapStreamASBD(tapID: createdTapID, aggregateDeviceID: createdAggID)
        } catch {
            MuteGuard.shared.teardown(tapID: createdTapID, aggregateDeviceID: createdAggID)
            throw error
        }

        let stopFlag = AtomicFlag(false)
        let ctx = RTIOContext(pipeline: pipeline, asbd: asbd, stoppingFlag: stopFlag)

        var createdIOProcID: AudioDeviceIOProcID?
        let ctxPtr = Unmanaged.passUnretained(ctx).toOpaque()
        let procStatus = AudioDeviceCreateIOProcID(
            createdAggID,
            Self.realTimeIOProc,
            ctxPtr,
            &createdIOProcID
        )
        guard procStatus == noErr, let createdIOProcID else {
            MuteGuard.shared.teardown(tapID: createdTapID, aggregateDeviceID: createdAggID)
            throw ProcessTapError.ioProcRegistrationFailed(status: procStatus)
        }

        stateLock.withLock {
            self.tapID = createdTapID
            self.aggregateDeviceID = createdAggID
            self.ioProcID = createdIOProcID
            self.tapDescription = tapDesc
            self.tapUUIDString = tapUUID
            self.currentBundleID = bundleIdentifier
            self.currentMuteLocalAudio = muteLocalAudio
            self.currentTappedObjectIDs = processObjectIDs
            self.isStandby = false
            self.streamASBD = asbd
            self.pipeline = pipeline
            self.rtContext = ctx
            self.rtStoppingFlag = stopFlag
        }

        let startStatus = AudioDeviceStart(createdAggID, createdIOProcID)
        guard startStatus == noErr else {
            teardownTapOnly()
            throw ProcessTapError.deviceStartFailed(status: startStatus)
        }
    }

    private func createTapAggregateDevice(tapUUID: String) throws -> AudioObjectID {
        let aggUID = "com.cozyting.tap-aggregate.\(UUID().uuidString)"
        var baseDesc: [String: Any] = [
            kAudioAggregateDeviceNameKey as String: "CozyTing Tap Aggregate",
            kAudioAggregateDeviceUIDKey as String: aggUID,
            kAudioAggregateDeviceIsPrivateKey as String: true,
            kAudioAggregateDeviceIsStackedKey as String: false,
            kAudioAggregateDeviceTapAutoStartKey as String: true,
            kAudioAggregateDeviceTapListKey as String: [
                [
                    kAudioSubTapUIDKey as String: tapUUID,
                    kAudioSubTapDriftCompensationKey as String: true,
                ]
            ],
        ]

        // Prefer syncing aggregate clock to the default output device WITHOUT adding any input subdevice
        // (guarantees zero risk of accidentally exposing a microphone input stream).
        if let clockUID = readDefaultSystemOutputDeviceUID(), !clockUID.isEmpty {
            var clockedDesc = baseDesc
            clockedDesc[kAudioAggregateDeviceClockDeviceKey as String] = clockUID
            var aggID = AudioObjectID(kAudioObjectUnknown)
            if AudioHardwareCreateAggregateDevice(clockedDesc as CFDictionary, &aggID) == noErr,
               aggID != kAudioObjectUnknown {
                return aggID
            }
        }

        // Clean fallback without explicit clock device key.
        baseDesc[kAudioAggregateDeviceUIDKey as String] = "com.cozyting.tap-aggregate.\(UUID().uuidString)"
        var createdAggID = AudioObjectID(kAudioObjectUnknown)
        let aggStatus = AudioHardwareCreateAggregateDevice(baseDesc as CFDictionary, &createdAggID)
        guard aggStatus == noErr, createdAggID != kAudioObjectUnknown else {
            throw ProcessTapError.aggregateDeviceCreationFailed(status: aggStatus)
        }
        return createdAggID
    }

    private func readTapUID(for tapID: AudioObjectID) -> String? {
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioTapPropertyUID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var cfUID: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(tapID, &addr, 0, nil, &size, &cfUID) == noErr,
              let uid = cfUID?.takeRetainedValue() as String?,
              !uid.isEmpty else {
            return nil
        }
        return uid
    }

    private func readDefaultSystemOutputDeviceUID() -> String? {
        var devAddr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultSystemOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var devID = AudioObjectID(kAudioObjectUnknown)
        var devSize = UInt32(MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &devAddr,
            0,
            nil,
            &devSize,
            &devID
        ) == noErr, devID != kAudioObjectUnknown else {
            return nil
        }

        var uidAddr = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyDeviceUID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var cfUID: Unmanaged<CFString>?
        var uidSize = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(devID, &uidAddr, 0, nil, &uidSize, &cfUID) == noErr,
              let uid = cfUID?.takeRetainedValue() as String?,
              !uid.isEmpty else {
            return nil
        }
        return uid
    }

    private func teardownTapOnly() {
        let (aggID, procID, oldTapID): (AudioObjectID, AudioDeviceIOProcID?, AudioObjectID) = stateLock.withLock {
            rtStoppingFlag.value = true
            let agg = aggregateDeviceID
            let proc = ioProcID
            let tap = tapID
            aggregateDeviceID = kAudioObjectUnknown
            ioProcID = nil
            tapID = kAudioObjectUnknown
            tapDescription = nil
            tapUUIDString = ""
            return (agg, proc, tap)
        }

        if aggID != kAudioObjectUnknown, let procID {
            _ = AudioDeviceStop(aggID, procID)
            _ = AudioDeviceDestroyIOProcID(aggID, procID)
        }
        if oldTapID != kAudioObjectUnknown || aggID != kAudioObjectUnknown {
            MuteGuard.shared.teardown(tapID: oldTapID, aggregateDeviceID: aggID)
        }

        stateLock.withLock {
            rtContext = nil
        }
    }

    // MARK: - Dynamic Process-Tree Refresh & Standby Promotion

    private func startStandbyTimer() {
        stopStandbyTimer()
        let timer = DispatchSource.makeTimerSource(queue: listenerQueue)
        timer.schedule(deadline: .now() + .milliseconds(400), repeating: .milliseconds(500))
        timer.setEventHandler { [weak self] in
            self?.refreshProcessTreeIfNeeded()
        }
        timer.resume()
        stateLock.withLock {
            standbyTimer = timer
        }
    }

    private func stopStandbyTimer() {
        let timer: DispatchSourceTimer? = stateLock.withLock {
            let t = standbyTimer
            standbyTimer = nil
            return t
        }
        timer?.cancel()
    }

    private func installProcessListListener() {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyProcessObjectList,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            self?.refreshProcessTreeIfNeeded()
        }
        let status = AudioObjectAddPropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            listenerQueue,
            block
        )
        if status == noErr {
            stateLock.withLock {
                processListListenerBlock = block
            }
        }
    }

    private func removeProcessListListener() {
        let block: AudioObjectPropertyListenerBlock? = stateLock.withLock {
            let b = processListListenerBlock
            processListListenerBlock = nil
            return b
        }
        guard let block else { return }
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyProcessObjectList,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        _ = AudioObjectRemovePropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            listenerQueue,
            block
        )
    }

    private func refreshProcessTreeIfNeeded() {
        let state: (
            bundle: String,
            mute: Bool,
            standby: Bool,
            currentIDs: [AudioObjectID],
            tapID: AudioObjectID,
            desc: CATapDescription?,
            pipeline: AudioEncodingPipeline?,
            onError: (@Sendable (Error) -> Void)?
        )? = stateLock.withLock {
            guard let b = currentBundleID, let p = pipeline else { return nil }
            return (b, currentMuteLocalAudio, isStandby, currentTappedObjectIDs, tapID, tapDescription, p, captureErrorHandler)
        }
        guard let state, let activePipeline = state.pipeline else { return }

        let latestIDs = ProcessTreeResolver.resolveAudioObjectIDs(forBundleIdentifier: state.bundle)
        guard !latestIDs.isEmpty else { return }

        // Case 1: Armed standby -> target app just created its first Core Audio process node!
        if state.standby || state.tapID == kAudioObjectUnknown {
            let bundle = state.bundle
            let mute = state.mute
            let onError = state.onError
            controlQueue.async { [weak self] in
                guard let self else { return }
                let stillStandby: Bool = self.stateLock.withLock {
                    self.isStandby && self.currentBundleID == bundle && self.pipeline != nil
                }
                guard stillStandby else { return }
                do {
                    try self.attachTapAndStartIO(
                        bundleIdentifier: bundle,
                        processObjectIDs: latestIDs,
                        muteLocalAudio: mute,
                        pipeline: activePipeline
                    )
                    self.stopStandbyTimer()
                    self.logger?.info(
                        tag: "ProcessTap",
                        "Promoted standby to active capture bundle=\(bundle) processes=\(latestIDs.count) mute=\(mute)"
                    )
                } catch {
                    self.logger?.error(
                        tag: "ProcessTap",
                        "Standby promotion failed bundle=\(bundle) err=\(error)"
                    )
                    self.stopStandbyTimer()
                    onError?(error)
                }
            }
            return
        }

        // Case 2: Active tap -> child helper process spawned/exited (Electron / Chrome / Safari).
        guard Set(latestIDs) != Set(state.currentIDs), let desc = state.desc else { return }
        desc.processes = latestIDs
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioTapPropertyDescription,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var unmanagedDesc = Unmanaged.passUnretained(desc)
        let status = AudioObjectSetPropertyData(
            state.tapID,
            &address,
            0,
            nil,
            UInt32(MemoryLayout<Unmanaged<CATapDescription>>.size),
            &unmanagedDesc
        )
        if status == noErr {
            stateLock.withLock {
                currentTappedObjectIDs = latestIDs
            }
            logger?.info(
                tag: "ProcessTap",
                "Refreshed process tree bundle=\(state.bundle) processes=\(latestIDs.count)"
            )
        }
    }

    private func readTapStreamASBD(
        tapID: AudioObjectID,
        aggregateDeviceID: AudioObjectID
    ) throws -> AudioStreamBasicDescription {
        var asbd = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)

        var tapAddr = AudioObjectPropertyAddress(
            mSelector: kAudioTapPropertyFormat,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        if AudioObjectGetPropertyData(tapID, &tapAddr, 0, nil, &size, &asbd) == noErr,
           asbd.mSampleRate > 0, asbd.mChannelsPerFrame > 0 {
            return asbd
        }

        var aggAddr = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyStreamFormat,
            mScope: kAudioDevicePropertyScopeInput,
            mElement: kAudioObjectPropertyElementMain
        )
        size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        let status = AudioObjectGetPropertyData(aggregateDeviceID, &aggAddr, 0, nil, &size, &asbd)
        guard status == noErr, asbd.mSampleRate > 0, asbd.mChannelsPerFrame > 0 else {
            throw ProcessTapError.streamFormatUnavailable(status: status)
        }
        return asbd
    }

    // MARK: - Real-Time Audio IOProc (ZERO alloc, ZERO locks, ZERO I/O)

    private static let realTimeIOProc: AudioDeviceIOProc = {
        _, _, inInputData, _, _, _, inClientData in
        guard let inClientData else { return noErr }
        let ctx = Unmanaged<RTIOContext>.fromOpaque(inClientData).takeUnretainedValue()
        if ctx.stoppingFlag.value {
            return noErr
        }

        let abl = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: inInputData))
        let bufferCount = abl.count
        guard bufferCount > 0 else { return noErr }

        var payloadBytes: UInt32 = 0
        for i in 0..<bufferCount {
            payloadBytes &+= abl[i].mDataByteSize
        }
        guard payloadBytes > 0, ctx.asbd.mBytesPerFrame > 0 else { return noErr }

        let firstBufferBytes = abl[0].mDataByteSize
        let frameCount = firstBufferBytes / ctx.asbd.mBytesPerFrame
        guard frameCount > 0 else { return noErr }

        let header = AudioChunkHeader(
            asbd: ctx.asbd,
            frameCount: frameCount,
            bufferCount: UInt32(bufferCount),
            payloadByteCount: payloadBytes,
            sampleTime: ctx.sampleTimeCounter
        )
        ctx.sampleTimeCounter &+= Int64(frameCount)

        _ = ctx.pipeline.enqueueAudioBufferList(header: header, bufferList: inInputData)
        return noErr
    }
}
