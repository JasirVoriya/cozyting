internal import CozyTingCore
import Foundation

/// Safe RAII Swift bridge over all 30 C ABI functions in `core/include/cozyting_core.h`.
///
/// Every `char *` returned from Rust is routed through `takeOwnedCString`, which frees the
/// heap allocation in `defer { cozyting_string_free(ptr) }`.
public enum CozyTingCoreBridge {
    private static let decoder = JSONDecoder()
    private static let encoder = JSONEncoder()

    /// Single RAII helper freeing Rust-allocated `char *` via `cozyting_string_free`.
    private static func takeOwnedCString(_ ptr: UnsafeMutablePointer<CChar>?) -> String? {
        guard let ptr else { return nil }
        defer { cozyting_string_free(ptr) }
        return String(cString: ptr)
    }

    private static func decodeL1JSON<T: Decodable>(_ raw: String?, as type: T.Type) throws -> T {
        guard let raw else { throw CoreBridgeError.nullReturn }
        guard let data = raw.data(using: .utf8) else {
            throw CoreBridgeError.invalidJSON("Non-UTF8 response")
        }
        if let errPayload = try? decoder.decode(L1ErrorPayload.self, from: data) {
            throw CoreBridgeError.l1Error(errPayload.error)
        }
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw CoreBridgeError.invalidJSON("\(error): \(raw)")
        }
    }

    // MARK: - Discovery & Routing

    public static func discoverJSON(
        localIP: String = "",
        timeoutMs: Int32 = 1500,
        cachedJSON: String = ""
    ) throws -> DiscoveryResult {
        let raw = localIP.withCString { ipPtr in
            cachedJSON.withCString { cachedPtr in
                takeOwnedCString(cozyting_discover_json(ipPtr, timeoutMs, cachedPtr))
            }
        }
        return try decodeL1JSON(raw, as: DiscoveryResult.self)
    }

    public static func discoverJSONEx(
        localIP: String = "",
        timeoutMs: Int32 = 1500,
        cachedJSON: String = "",
        forceRefreshNames: Bool = false
    ) throws -> DiscoveryResult {
        let raw = localIP.withCString { ipPtr in
            cachedJSON.withCString { cachedPtr in
                takeOwnedCString(
                    cozyting_discover_json_ex(ipPtr, timeoutMs, cachedPtr, forceRefreshNames)
                )
            }
        }
        return try decodeL1JSON(raw, as: DiscoveryResult.self)
    }

    public static func discoverTSV(
        localIP: String = "",
        timeoutMs: Int32 = 1500
    ) -> String {
        localIP.withCString { ipPtr in
            takeOwnedCString(cozyting_discover_tsv(ipPtr, timeoutMs)) ?? ""
        }
    }

    public static func routeApplyJSON(
        devicesJSON: String,
        action: RouteAction,
        target: String = ""
    ) throws -> RouteTransition {
        let raw = devicesJSON.withCString { devPtr in
            action.rawValue.withCString { actPtr in
                target.withCString { tgtPtr in
                    takeOwnedCString(cozyting_route_apply_json(devPtr, actPtr, tgtPtr))
                }
            }
        }
        return try decodeL1JSON(raw, as: RouteTransition.self)
    }

    public static func routeApply(
        devices: [Device],
        action: RouteAction,
        target: String = ""
    ) throws -> RouteTransition {
        let data = try encoder.encode(devices)
        let devicesJSON = String(data: data, encoding: .utf8) ?? "[]"
        return try routeApplyJSON(devicesJSON: devicesJSON, action: action, target: target)
    }

    // MARK: - Latency, Auth & Volume Guard

    public static func delayForFrequency(mhz: Int32) -> Int32 {
        cozyting_delay_for_frequency(mhz)
    }

    public static func startupRunwayAUs(delayMs: Int32) -> Int32 {
        cozyting_startup_runway_aus(delayMs)
    }

    public static func parseAuthMode(_ raw: String) -> AuthMode? {
        let code = raw.withCString { cozyting_parse_auth_mode($0) }
        return AuthMode(rawValue: code)
    }

    public static func setAuthMode(_ mode: AuthMode) {
        cozyting_set_auth_mode(mode.rawValue)
    }

    public static func setVolumeGuardPolicy(enabled: Bool, safeLimit: Int32) {
        cozyting_set_volume_guard_policy(enabled, safeLimit)
    }

    public static func evaluateVolumeGuard(
        enabled: Bool,
        safeLimit: Int32,
        currentVolume: Int32,
        targetVolume: Int32
    ) throws -> VolumeGuardDecision {
        let raw = takeOwnedCString(
            cozyting_volume_guard_evaluate_json(enabled, safeLimit, currentVolume, targetVolume)
        )
        return try decodeL1JSON(raw, as: VolumeGuardDecision.self)
    }

    // MARK: - Engine Lifecycle & Control (Raw Handle)

    public static func createEngine(silent: Bool) -> Int64 {
        cozyting_engine_create(silent)
    }

    public static func connect(
        handle: Int64,
        ips: [String],
        delayMs: Int32,
        premute: Int32,
        volumeAfterPlay: Int32,
        refFunction: String = "",
        sourceName: String = ""
    ) throws -> EngineStats {
        let joinedIPs = ips.joined(separator: ",")
        let raw = joinedIPs.withCString { ipsPtr in
            refFunction.withCString { refPtr in
                sourceName.withCString { srcPtr in
                    takeOwnedCString(
                        cozyting_engine_connect(
                            handle,
                            ipsPtr,
                            delayMs,
                            premute,
                            volumeAfterPlay,
                            refPtr,
                            srcPtr
                        )
                    )
                }
            }
        }
        return try decodeL1JSON(raw, as: EngineStats.self)
    }

    public static func connectEx(
        handle: Int64,
        ips: [String],
        delayMs: Int32,
        premute: Int32,
        volumeAfterPlay: Int32,
        refFunction: String = "",
        sourceName: String = "",
        volumeConfirmed: Bool
    ) throws -> EngineStats {
        let joinedIPs = ips.joined(separator: ",")
        let raw = joinedIPs.withCString { ipsPtr in
            refFunction.withCString { refPtr in
                sourceName.withCString { srcPtr in
                    takeOwnedCString(
                        cozyting_engine_connect_ex(
                            handle,
                            ipsPtr,
                            delayMs,
                            premute,
                            volumeAfterPlay,
                            refPtr,
                            srcPtr,
                            volumeConfirmed
                        )
                    )
                }
            }
        }
        return try decodeL1JSON(raw, as: EngineStats.self)
    }

    public static func pushAac(
        handle: Int64,
        pointer: UnsafePointer<UInt8>?,
        count: Int
    ) -> PushAacStatus {
        let code = cozyting_engine_push_aac(handle, pointer, count)
        return PushAacStatus(code: code)
    }

    public static func pushAac(handle: Int64, data: Data) -> PushAacStatus {
        data.withUnsafeBytes { rawBuffer in
            let ptr = rawBuffer.bindMemory(to: UInt8.self).baseAddress
            return pushAac(handle: handle, pointer: ptr, count: rawBuffer.count)
        }
    }

    public static func addReceiver(handle: Int64, ip: String) throws -> AddReceiverResult {
        let raw = ip.withCString { ipPtr in
            takeOwnedCString(cozyting_engine_add_receiver(handle, ipPtr))
        }
        return try decodeL1JSON(raw, as: AddReceiverResult.self)
    }

    public static func removeReceiver(handle: Int64, ip: String) -> Bool {
        ip.withCString { ipPtr in
            cozyting_engine_remove_receiver(handle, ipPtr)
        }
    }

    public static func setVolume(handle: Int64, ip: String, volume: Int32) throws -> SetVolumeResult {
        let raw = ip.withCString { ipPtr in
            takeOwnedCString(cozyting_engine_set_volume(handle, ipPtr, volume))
        }
        return try decodeL1JSON(raw, as: SetVolumeResult.self)
    }

    public static func setVolumeChecked(
        handle: Int64,
        ip: String,
        volume: Int32,
        confirmed: Bool
    ) throws -> SetVolumeResult {
        let raw = ip.withCString { ipPtr in
            takeOwnedCString(cozyting_engine_set_volume_checked(handle, ipPtr, volume, confirmed))
        }
        return try decodeL1JSON(raw, as: SetVolumeResult.self)
    }

    /// Note: `cozyting_engine_pause` returns plain text (`"no session"` or semicolon-delimited
    /// per-receiver acknowledgments), NOT JSON.
    public static func pause(handle: Int64) -> String {
        takeOwnedCString(cozyting_engine_pause(handle)) ?? ""
    }

    public static func resume(handle: Int64) throws -> TimeOffsetResult {
        let raw = takeOwnedCString(cozyting_engine_resume(handle))
        return try decodeL1JSON(raw, as: TimeOffsetResult.self)
    }

    public static func switchSource(handle: Int64) throws -> TimeOffsetResult {
        let raw = takeOwnedCString(cozyting_engine_switch_source(handle))
        return try decodeL1JSON(raw, as: TimeOffsetResult.self)
    }

    public static func setPlaybackDelay(handle: Int64, delayMs: Int32) throws -> PlaybackDelayResult {
        let raw = takeOwnedCString(cozyting_engine_set_playback_delay(handle, delayMs))
        return try decodeL1JSON(raw, as: PlaybackDelayResult.self)
    }

    public static func stats(handle: Int64) throws -> EngineStatsEnvelope {
        let raw = takeOwnedCString(cozyting_engine_stats_json(handle))
        guard let raw else { throw CoreBridgeError.nullReturn }
        guard let data = raw.data(using: .utf8) else {
            throw CoreBridgeError.invalidJSON("Non-UTF8 stats response")
        }
        do {
            return try decoder.decode(EngineStatsEnvelope.self, from: data)
        } catch {
            throw CoreBridgeError.invalidJSON("\(error): \(raw)")
        }
    }

    public static func stop(handle: Int64) {
        cozyting_engine_stop(handle)
    }

    // MARK: - Versioning & Updates

    public static func compareVersions(_ a: String, _ b: String) -> VersionComparison {
        let code = a.withCString { aPtr in
            b.withCString { bPtr in
                cozyting_compare_versions(aPtr, bPtr)
            }
        }
        return VersionComparison(rawCode: code)
    }

    public static func evaluateUpdate(
        currentVersion: String,
        channel: String,
        platform: String,
        abi: String,
        osVersion: String,
        ignoredTag: String,
        rawResponseJSON: String
    ) throws -> UpdateDecision {
        let raw = currentVersion.withCString { curPtr in
            channel.withCString { chPtr in
                platform.withCString { plPtr in
                    abi.withCString { abiPtr in
                        osVersion.withCString { osPtr in
                            ignoredTag.withCString { igPtr in
                                rawResponseJSON.withCString { bodyPtr in
                                    takeOwnedCString(
                                        cozyting_update_evaluate_json(
                                            curPtr,
                                            chPtr,
                                            plPtr,
                                            abiPtr,
                                            osPtr,
                                            igPtr,
                                            bodyPtr
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        guard let raw else { throw CoreBridgeError.nullReturn }
        guard let data = raw.data(using: .utf8) else {
            throw CoreBridgeError.invalidJSON("Non-UTF8 update decision")
        }
        do {
            return try decoder.decode(UpdateDecision.self, from: data)
        } catch {
            throw CoreBridgeError.invalidJSON("\(error): \(raw)")
        }
    }

    // MARK: - Core Diagnostics Logging

    public static func initLogFile(
        path: String?,
        maxBytes: UInt64 = 256 * 1024,
        tzOffsetSecs: Int32 = Int32(TimeZone.current.secondsFromGMT())
    ) {
        if let path {
            path.withCString { cozyting_init_log_file($0, maxBytes, tzOffsetSecs) }
        } else {
            cozyting_init_log_file(nil, maxBytes, tzOffsetSecs)
        }
    }

    public static func recentLogs() -> String {
        takeOwnedCString(cozyting_recent_logs()) ?? ""
    }

    public static func clearLogs() {
        cozyting_clear_logs()
    }
}

// MARK: - Background Executors for Blocking L1 Calls

/// Dispatches blocking discovery calls off `@MainActor` onto a dedicated background queue.
public final class CozyTingDiscoveryClient: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.cozyting.core.discovery", qos: .userInitiated)

    public init() {}

    public func discover(
        localIP: String = "",
        timeoutMs: Int32 = 1500,
        cachedDevices: [Device] = [],
        forceRefreshNames: Bool = false
    ) async throws -> DiscoveryResult {
        let cachedJSON: String
        if cachedDevices.isEmpty {
            cachedJSON = ""
        } else {
            let data = try JSONEncoder().encode(cachedDevices)
            cachedJSON = String(data: data, encoding: .utf8) ?? ""
        }
        return try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    let result = try CozyTingCoreBridge.discoverJSONEx(
                        localIP: localIP,
                        timeoutMs: timeoutMs,
                        cachedJSON: cachedJSON,
                        forceRefreshNames: forceRefreshNames
                    )
                    continuation.resume(returning: result)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}

/// Manages a single L1 engine handle lifecycle.
///
/// Blocking control calls run on a dedicated background `DispatchQueue` so `@MainActor` never stalls,
/// while `pushAac` is non-blocking and safe to call directly from the dedicated AAC encoder thread.
public final class CozyTingEngine: @unchecked Sendable {
    private let lock = NSLock()
    private let connectQueue = DispatchQueue(label: "com.cozyting.core.engine-connect", qos: .userInitiated)
    private let controlQueue = DispatchQueue(label: "com.cozyting.core.engine-control", qos: .userInitiated)
    private let teardownQueue = DispatchQueue(label: "com.cozyting.core.engine-teardown", qos: .userInitiated)
    private let queryQueue = DispatchQueue(
        label: "com.cozyting.core.engine-query",
        qos: .userInitiated,
        attributes: .concurrent
    )
    private var _handle: Int64

    public init(silent: Bool) {
        self._handle = CozyTingCoreBridge.createEngine(silent: silent)
    }

    deinit {
        let handleToStop: Int64 = lock.withLock {
            let h = _handle
            _handle = 0
            return h
        }
        if handleToStop > 0 {
            CozyTingCoreBridge.stop(handle: handleToStop)
        }
    }

    public var isStopped: Bool {
        lock.withLock { _handle <= 0 }
    }

    private func currentHandle() throws -> Int64 {
        let h = lock.withLock { _handle }
        guard h > 0 else { throw CoreBridgeError.invalidHandle }
        return h
    }

    /// Non-blocking push from the dedicated AAC encoder thread (never call inside Core Audio IOProc).
    public func pushAac(_ pointer: UnsafePointer<UInt8>?, count: Int) -> PushAacStatus {
        let h = lock.withLock { _handle }
        guard h > 0 else { return .invalidOrStopped }
        return CozyTingCoreBridge.pushAac(handle: h, pointer: pointer, count: count)
    }

    public func pushAac(_ data: Data) -> PushAacStatus {
        let h = lock.withLock { _handle }
        guard h > 0 else { return .invalidOrStopped }
        return CozyTingCoreBridge.pushAac(handle: h, data: data)
    }

    public func connect(
        ips: [String],
        delayMs: Int32 = 0,
        premute: Int32 = -1,
        volumeAfterPlay: Int32 = -1,
        refFunction: String = "",
        sourceName: String = "",
        volumeConfirmed: Bool = false
    ) async throws -> EngineStats {
        let h = try currentHandle()
        return try await runOnQueue(connectQueue) {
            try CozyTingCoreBridge.connectEx(
                handle: h,
                ips: ips,
                delayMs: delayMs,
                premute: premute,
                volumeAfterPlay: volumeAfterPlay,
                refFunction: refFunction,
                sourceName: sourceName,
                volumeConfirmed: volumeConfirmed
            )
        }
    }

    public func addReceiver(ip: String) async throws -> AddReceiverResult {
        let h = try currentHandle()
        return try await runOnQueue(queryQueue) {
            try CozyTingCoreBridge.addReceiver(handle: h, ip: ip)
        }
    }

    public func removeReceiver(ip: String) async -> Bool {
        let h = lock.withLock { _handle }
        guard h > 0 else { return false }
        return await withCheckedContinuation { continuation in
            queryQueue.async {
                continuation.resume(returning: CozyTingCoreBridge.removeReceiver(handle: h, ip: ip))
            }
        }
    }

    public func setVolume(ip: String, volume: Int32, confirmed: Bool = false) async throws -> SetVolumeResult {
        let h = try currentHandle()
        return try await runOnQueue(queryQueue) {
            try CozyTingCoreBridge.setVolumeChecked(handle: h, ip: ip, volume: volume, confirmed: confirmed)
        }
    }

    public func pause() async throws -> String {
        let h = try currentHandle()
        return await withCheckedContinuation { continuation in
            controlQueue.async {
                continuation.resume(returning: CozyTingCoreBridge.pause(handle: h))
            }
        }
    }

    public func resume() async throws -> TimeOffsetResult {
        let h = try currentHandle()
        return try await runOnQueue(controlQueue) {
            try CozyTingCoreBridge.resume(handle: h)
        }
    }

    public func switchSource() async throws -> TimeOffsetResult {
        let h = try currentHandle()
        return try await runOnQueue(controlQueue) {
            try CozyTingCoreBridge.switchSource(handle: h)
        }
    }

    public func setPlaybackDelay(delayMs: Int32) async throws -> PlaybackDelayResult {
        let h = try currentHandle()
        return try await runOnQueue(controlQueue) {
            try CozyTingCoreBridge.setPlaybackDelay(handle: h, delayMs: delayMs)
        }
    }

    public func stats() async throws -> EngineStatsEnvelope {
        let h = try currentHandle()
        return try await runOnQueue(queryQueue) {
            try CozyTingCoreBridge.stats(handle: h)
        }
    }

    public func stop() async {
        let handleToStop: Int64 = lock.withLock {
            let h = _handle
            _handle = 0
            return h
        }
        guard handleToStop > 0 else { return }
        await withCheckedContinuation { continuation in
            teardownQueue.async {
                CozyTingCoreBridge.stop(handle: handleToStop)
                continuation.resume()
            }
        }
    }

    private func runOnQueue<T: Sendable>(
        _ queue: DispatchQueue,
        _ body: @escaping @Sendable () throws -> T
    ) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    continuation.resume(returning: try body())
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}
