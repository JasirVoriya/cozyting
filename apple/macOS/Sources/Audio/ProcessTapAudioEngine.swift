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
/// - **True per-app local mute**: `.mutedWhenTapped` when `muteLocalAudio == true`, protected by `MuteGuard`.
/// - **Real-time IOProc discipline**: zero heap allocation, zero locks, zero I/O inside `IOProc`;
///   writes directly into `LockFreeRingBuffer` for `AudioEncodingPipeline`.
/// - **Hot source switch without speaker disconnect**: swaps only the Process Tap + Aggregate Device
///   while preserving the L1 `CozyTingEngine` connection and calling `switchSource()`.
/// - **Deadlock-free 4-step stop order**: atomic stop flag -> stop/destroy IOProc/Aggregate/Tap &
///   drain encoder -> caller stops L1 engine -> clear state.
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
    private var streamASBD = AudioStreamBasicDescription()

    private var pipeline: AudioEncodingPipeline?
    private var processListListenerBlock: AudioObjectPropertyListenerBlock?
    private let listenerQueue = DispatchQueue(label: "com.cozyting.macos.process-tree-listener", qos: .utility)

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

    /// Starts capturing audio exclusively from `bundleIdentifier` and feeding raw 48 kHz AAC-LC AUs to `sink`.
    public func startCapture(
        bundleIdentifier: String,
        muteLocalAudio: Bool,
        sink: @escaping AudioEncodingPipeline.FrameSink
    ) throws {
        let cleanBundle = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanBundle.isEmpty else {
            throw ProcessTapError.invalidBundleIdentifier
        }

        // Ensure clean slate before creating new tap.
        stopCapture()

        let objectIDs = ProcessTreeResolver.resolveAudioObjectIDs(forBundleIdentifier: cleanBundle)
        guard !objectIDs.isEmpty else {
            throw ProcessTapError.targetAppNotRunning(bundleIdentifier: cleanBundle)
        }

        let newPipeline = try AudioEncodingPipeline(sink: sink)
        newPipeline.start()

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
        guard !objectIDs.isEmpty else {
            throw ProcessTapError.targetAppNotRunning(bundleIdentifier: cleanBundle)
        }

        // Tear down old tap/aggregate device first, reset encoder FIFO, then attach new tap.
        teardownTapOnly()
        existingPipeline.requestReset()
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
        let bundle: String? = stateLock.withLock { currentBundleID }
        guard let bundle else { return }
        try hotSwitchTargetApp(bundleIdentifier: bundle, muteLocalAudio: muteLocalAudio)
    }

    /// Deadlock-free Steps 1–2 of `apple/AGENTS.md` §3.3:
    /// 1) Flip atomic stopping flag so IOProc becomes an immediate no-op.
    /// 2) Stop/destroy IOProc, Aggregate Device, and Process Tap (`MuteGuard`), then drain encoder loop.
    /// Caller may safely follow with Step 3 (`await engine.stop()`).
    public func stopCapture() {
        removeProcessListListener()
        teardownTapOnly()

        let pipelineToStop: AudioEncodingPipeline? = stateLock.withLock {
            let p = pipeline
            pipeline = nil
            currentBundleID = nil
            currentTappedObjectIDs = []
            return p
        }
        pipelineToStop?.stop()
    }

    /// Convenience helper enforcing the full 4-step deadlock-free stop sequence with an L1 `CozyTingEngine`.
    public func stopCaptureAndEngine(_ engine: CozyTingEngine) async {
        stopCapture()
        await engine.stop()
    }

    public func status() -> ProcessTapStatus {
        stateLock.withLock {
            ProcessTapStatus(
                isCapturing: tapID != kAudioObjectUnknown && ioProcID != nil,
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

        let tapUUID = tapDesc.uuid.uuidString
        let aggUID = "com.cozyting.tap-aggregate.\(UUID().uuidString)"
        let aggDesc: [String: Any] = [
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

        var createdAggID = AudioObjectID(kAudioObjectUnknown)
        let aggStatus = AudioHardwareCreateAggregateDevice(aggDesc as CFDictionary, &createdAggID)
        guard aggStatus == noErr, createdAggID != kAudioObjectUnknown else {
            _ = AudioHardwareDestroyProcessTap(createdTapID)
            throw ProcessTapError.aggregateDeviceCreationFailed(status: aggStatus)
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

    // MARK: - Dynamic Process-Tree Refresh (Electron / Browser Child Processes)

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
        let snapshot: (bundle: String, currentIDs: [AudioObjectID], tapID: AudioObjectID, desc: CATapDescription)? =
            stateLock.withLock {
                guard let b = currentBundleID,
                      tapID != kAudioObjectUnknown,
                      let d = tapDescription else {
                    return nil
                }
                return (b, currentTappedObjectIDs, tapID, d)
            }
        guard let snapshot else { return }

        let latestIDs = ProcessTreeResolver.resolveAudioObjectIDs(forBundleIdentifier: snapshot.bundle)
        guard !latestIDs.isEmpty, Set(latestIDs) != Set(snapshot.currentIDs) else { return }

        snapshot.desc.processes = latestIDs
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioTapPropertyDescription,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var unmanagedDesc = Unmanaged.passUnretained(snapshot.desc)
        let status = AudioObjectSetPropertyData(
            snapshot.tapID,
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
                "Refreshed process tree bundle=\(snapshot.bundle) processes=\(latestIDs.count)"
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
