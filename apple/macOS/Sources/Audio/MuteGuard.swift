import CoreAudio
import Foundation

/// Safety guard tracking active Core Audio Process Taps and HAL Aggregate Devices.
///
/// Guarantees per `apple/AGENTS.md` §4.2 that `.mutedWhenTapped` taps are always destroyed on
/// normal stop, source hot-switch, `deinit`, `applicationWillTerminate`, or `SIGINT`/`SIGTERM`,
/// preventing a target music app from remaining stuck muted.
public final class MuteGuard: @unchecked Sendable {
    public static let shared = MuteGuard()

    private struct ActiveHandle: Hashable {
        let tapID: AudioObjectID
        let aggregateDeviceID: AudioObjectID
    }

    private let lock = NSLock()
    private var handles: Set<ActiveHandle> = []
    private var signalSources: [DispatchSourceSignal] = []
    private var signalsInstalled = false

    private init() {}

    /// Installs `SIGINT` and `SIGTERM` cleanup dispatch sources once at launch.
    public func installTerminationHooks() {
        lock.withLock {
            guard !signalsInstalled else { return }
            signalsInstalled = true
            let queue = DispatchQueue(label: "com.cozyting.macos.muteguard-signal", qos: .userInitiated)
            for sig in [SIGTERM, SIGINT] {
                signal(sig, SIG_IGN)
                let src = DispatchSource.makeSignalSource(signal: sig, queue: queue)
                src.setEventHandler {
                    MuteGuard.shared.emergencyTeardownAll()
                    Darwin.exit(0)
                }
                src.resume()
                signalSources.append(src)
            }
        }
    }

    public func register(tapID: AudioObjectID, aggregateDeviceID: AudioObjectID) {
        lock.withLock {
            _ = handles.insert(ActiveHandle(tapID: tapID, aggregateDeviceID: aggregateDeviceID))
        }
    }

    public func teardown(tapID: AudioObjectID, aggregateDeviceID: AudioObjectID) {
        lock.withLock {
            _ = handles.remove(ActiveHandle(tapID: tapID, aggregateDeviceID: aggregateDeviceID))
        }
        Self.destroyCoreAudioHandles(tapID: tapID, aggregateDeviceID: aggregateDeviceID)
    }

    /// Synchronously destroys all registered Aggregate Devices and Process Taps.
    public func emergencyTeardownAll() {
        let snapshot: [ActiveHandle] = lock.withLock {
            let list = Array(handles)
            handles.removeAll()
            return list
        }
        for handle in snapshot {
            Self.destroyCoreAudioHandles(
                tapID: handle.tapID,
                aggregateDeviceID: handle.aggregateDeviceID
            )
        }
    }

    public var activeCount: Int {
        lock.withLock { handles.count }
    }

    private static func destroyCoreAudioHandles(
        tapID: AudioObjectID,
        aggregateDeviceID: AudioObjectID
    ) {
        if aggregateDeviceID != kAudioObjectUnknown {
            _ = AudioHardwareDestroyAggregateDevice(aggregateDeviceID)
        }
        if tapID != kAudioObjectUnknown {
            _ = AudioHardwareDestroyProcessTap(tapID)
        }
    }
}
