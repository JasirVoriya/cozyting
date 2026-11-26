import Foundation

// MARK: - Device & Discovery Models

public enum DeviceKind: String, Codable, Sendable, Equatable {
    case thisDevice = "THIS_DEVICE"
    case speaker = "SPEAKER"
    case tv = "TV"

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self).trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        switch raw {
        case "THIS_DEVICE": self = .thisDevice
        case "TV": self = .tv
        default: self = .speaker
        }
    }
}

public enum CastProtocol: String, Codable, Sendable, Equatable {
    case miplay = "MIPLAY"
    case dlna = "DLNA"
    case airplay = "AIRPLAY"
    case googleCast = "GOOGLE_CAST"

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch raw {
        case "miplay": self = .miplay
        case "dlna": self = .dlna
        case "airplay": self = .airplay
        case "gcast", "google_cast", "googlecast": self = .googleCast
        default:
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unknown CastProtocol: \(raw)"
            )
        }
    }
}

public struct Device: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let name: String
    public let kind: DeviceKind
    public let castable: Bool
    public let selected: Bool
    public let detail: String
    public let `protocol`: CastProtocol?
    public let nameFromDeviceInfo: Bool
    public let roomName: String
    public let roomInfoQueried: Bool

    public init(
        id: String,
        name: String,
        kind: DeviceKind,
        castable: Bool,
        selected: Bool,
        detail: String = "",
        protocol: CastProtocol? = .miplay,
        nameFromDeviceInfo: Bool = false,
        roomName: String = "",
        roomInfoQueried: Bool = false
    ) {
        self.id = id
        self.name = name
        self.kind = kind
        self.castable = castable
        self.selected = selected
        self.detail = detail
        self.protocol = `protocol`
        self.nameFromDeviceInfo = nameFromDeviceInfo
        self.roomName = roomName
        self.roomInfoQueried = roomInfoQueried
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case kind
        case castable
        case selected
        case detail
        case `protocol`
        case nameFromDeviceInfo = "name_from_device_info"
        case roomName = "room_name"
        case roomInfoQueried = "room_info_queried"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        let rawName = (try? c.decode(String.self, forKey: .name))?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        name = rawName.isEmpty ? id : rawName
        kind = (try? c.decode(DeviceKind.self, forKey: .kind)) ?? .speaker
        castable = (try? c.decode(Bool.self, forKey: .castable)) ?? true
        selected = (try? c.decode(Bool.self, forKey: .selected)) ?? false
        detail = (try? c.decode(String.self, forKey: .detail)) ?? ""
        `protocol` = try c.decodeIfPresent(CastProtocol.self, forKey: .protocol)
        nameFromDeviceInfo = (try? c.decode(Bool.self, forKey: .nameFromDeviceInfo)) ?? false
        let room = (try? c.decode(String.self, forKey: .roomName))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        roomName = room
        if let queried = try c.decodeIfPresent(Bool.self, forKey: .roomInfoQueried) {
            roomInfoQueried = queried
        } else {
            roomInfoQueried = !room.isEmpty
        }
    }
}

public struct DiscoveredRow: Codable, Sendable, Equatable {
    public let device: Device
    public let advertisesMiplay: Bool
    public let service: String
    public let apps: String
    public let visibleByDefault: Bool
    public let logLine: String

    enum CodingKeys: String, CodingKey {
        case device
        case advertisesMiplay = "advertises_miplay"
        case service
        case apps
        case visibleByDefault = "visible_by_default"
        case logLine = "log_line"
    }
}

public struct DiscoveryResult: Codable, Sendable, Equatable {
    public let freshCount: Int
    public let castableCount: Int
    public let hiddenCount: Int
    public let rows: [DiscoveredRow]
    public let merged: [Device]
    public let cacheable: [Device]

    enum CodingKeys: String, CodingKey {
        case freshCount = "fresh_count"
        case castableCount = "castable_count"
        case hiddenCount = "hidden_count"
        case rows
        case merged
        case cacheable
    }
}

// MARK: - Route State Machine Models

public enum RouteAction: String, Codable, Sendable, Equatable {
    case selectThis = "select_this"
    case check = "check"
    case uncheck = "uncheck"
    case mergeDiscovered = "merge_discovered"
}

public struct RouteState: Codable, Sendable, Equatable {
    public let thisDeviceSelected: Bool
    public let casting: Bool
    public let selectedSpeakerIds: [String]
    public let devices: [Device]

    enum CodingKeys: String, CodingKey {
        case thisDeviceSelected = "this_device_selected"
        case casting
        case selectedSpeakerIds = "selected_speaker_ids"
        case devices
    }
}

public enum RouteEffect: Codable, Sendable, Equatable {
    case stopCastToThisDevice
    case startCast(speakerIds: [String])
    case addSpeaker(id: String)
    case removeSpeaker(id: String)
    case rejectNonCastable(id: String, reason: String)

    enum CodingKeys: String, CodingKey {
        case type
        case speakerIds = "speaker_ids"
        case id
        case reason
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let type = try c.decode(String.self, forKey: .type)
        switch type {
        case "stop_cast_to_this_device":
            self = .stopCastToThisDevice
        case "start_cast":
            self = .startCast(speakerIds: try c.decode([String].self, forKey: .speakerIds))
        case "add_speaker":
            self = .addSpeaker(id: try c.decode(String.self, forKey: .id))
        case "remove_speaker":
            self = .removeSpeaker(id: try c.decode(String.self, forKey: .id))
        case "reject_non_castable":
            self = .rejectNonCastable(
                id: try c.decode(String.self, forKey: .id),
                reason: try c.decode(String.self, forKey: .reason)
            )
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: c,
                debugDescription: "Unknown RouteEffect type: \(type)"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .stopCastToThisDevice:
            try c.encode("stop_cast_to_this_device", forKey: .type)
        case .startCast(let speakerIds):
            try c.encode("start_cast", forKey: .type)
            try c.encode(speakerIds, forKey: .speakerIds)
        case .addSpeaker(let id):
            try c.encode("add_speaker", forKey: .type)
            try c.encode(id, forKey: .id)
        case .removeSpeaker(let id):
            try c.encode("remove_speaker", forKey: .type)
            try c.encode(id, forKey: .id)
        case .rejectNonCastable(let id, let reason):
            try c.encode("reject_non_castable", forKey: .type)
            try c.encode(id, forKey: .id)
            try c.encode(reason, forKey: .reason)
        }
    }
}

public struct RouteTransition: Codable, Sendable, Equatable {
    public let state: RouteState
    public let effects: [RouteEffect]
}

// MARK: - Auth, Latency & Volume Guard Models

public enum AuthMode: Int32, Codable, Sendable, Equatable, CaseIterable {
    case auto = 0
    case legacy = 1
    case safety = 2

    public var wireName: String {
        switch self {
        case .auto: return "auto"
        case .legacy: return "legacy"
        case .safety: return "safety"
        }
    }

    public static func clamped(_ raw: Int) -> AuthMode {
        AuthMode(rawValue: Int32(min(max(raw, 0), 2))) ?? .auto
    }
}

public enum LatencyPreset: Sendable, Equatable {
    case standard
    case balanced
    case low
    case ultra
    case custom(ms: Int)

    public init(delayMs: Int) {
        switch delayMs {
        case ...0: self = .standard
        case 400: self = .balanced
        case 250: self = .low
        case 150: self = .ultra
        default: self = .custom(ms: delayMs)
        }
    }

    public var delayMs: Int {
        switch self {
        case .standard: return 0
        case .balanced: return 400
        case .low: return 250
        case .ultra: return 150
        case .custom(let ms): return max(ms, 1)
        }
    }
}

public enum VolumeGuardDecision: Codable, Sendable, Equatable {
    case allow(volume: Int, safeLimit: Int)
    case requireConfirm(currentVolume: Int, targetVolume: Int, safeLimit: Int)

    enum CodingKeys: String, CodingKey {
        case decision
        case volume
        case currentVolume = "current_volume"
        case targetVolume = "target_volume"
        case safeLimit = "safe_limit"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let decision = try c.decode(String.self, forKey: .decision)
        let safeLimit = try c.decode(Int.self, forKey: .safeLimit)
        switch decision {
        case "allow":
            let volume = try c.decode(Int.self, forKey: .volume)
            self = .allow(volume: volume, safeLimit: safeLimit)
        case "require_confirm":
            let currentVolume = try c.decode(Int.self, forKey: .currentVolume)
            let targetVolume = try c.decode(Int.self, forKey: .targetVolume)
            self = .requireConfirm(
                currentVolume: currentVolume,
                targetVolume: targetVolume,
                safeLimit: safeLimit
            )
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .decision,
                in: c,
                debugDescription: "Unknown VolumeGuardDecision: \(decision)"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .allow(let volume, let safeLimit):
            try c.encode("allow", forKey: .decision)
            try c.encode(volume, forKey: .volume)
            try c.encode(safeLimit, forKey: .safeLimit)
        case .requireConfirm(let currentVolume, let targetVolume, let safeLimit):
            try c.encode("require_confirm", forKey: .decision)
            try c.encode(currentVolume, forKey: .currentVolume)
            try c.encode(targetVolume, forKey: .targetVolume)
            try c.encode(safeLimit, forKey: .safeLimit)
        }
    }
}

public struct VolumeGuardPolicy: Sendable, Equatable {
    public static let defaultSafeLimit = 60
    public static let minSafeLimit = 30
    public static let maxSafeLimit = 90
    public static let step = 5

    public let enabled: Bool
    public let safeLimit: Int

    public init(enabled: Bool = true, safeLimit: Int = defaultSafeLimit) {
        self.enabled = enabled
        self.safeLimit = Self.normalizeSafeLimit(safeLimit)
    }

    public static func normalizeSafeLimit(_ raw: Int) -> Int {
        min(max(raw, minSafeLimit), maxSafeLimit)
    }

    public static func snapSafeLimit(_ raw: Int) -> Int {
        let clamped = normalizeSafeLimit(raw)
        let steps = ((clamped - minSafeLimit) + step / 2) / step
        return normalizeSafeLimit(minSafeLimit + steps * step)
    }

    public func clampForColdStartRestore(_ savedVolume: Int) -> Int {
        let clamped = min(max(savedVolume, 0), 100)
        return enabled ? min(clamped, safeLimit) : clamped
    }
}

// MARK: - Engine & Stats Models

public enum PushAacStatus: Sendable, Equatable {
    case ok
    case invalidOrStopped
    case silentEngineNoFeed
    case invalidBuffer
    case queueFull
    case unknown(Int32)

    public init(code: Int32) {
        switch code {
        case 0: self = .ok
        case -1: self = .invalidOrStopped
        case -2: self = .silentEngineNoFeed
        case -3: self = .invalidBuffer
        case -4: self = .queueFull
        default: self = .unknown(code)
        }
    }
}

public enum ReceiverUIState: String, Codable, Sendable, Equatable {
    case connecting
    case streaming
    case reconnecting
    case error

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        let raw = try c.decode(String.self).lowercased()
        self = ReceiverUIState(rawValue: raw) ?? .error
    }
}

public struct LatencyStats: Codable, Sendable, Equatable {
    public let kind: String
    public let definition: String
    public let playbackDelayMs: Int64
    public let startupRunwayMs: Int64
    public let feedFlushedAusAtStart: UInt64
    public let feedFlushedEstMs: Int64
    public let e2eEstMs: Int64?
    public let scheduleAheadMs: Int64?
    public let segments: [String]
    public let unknownSegments: [String]

    enum CodingKeys: String, CodingKey {
        case kind
        case definition
        case playbackDelayMs = "playback_delay_ms"
        case startupRunwayMs = "startup_runway_ms"
        case feedFlushedAusAtStart = "feed_flushed_aus_at_start"
        case feedFlushedEstMs = "feed_flushed_est_ms"
        case e2eEstMs = "e2e_est_ms"
        case scheduleAheadMs = "schedule_ahead_ms"
        case segments
        case unknownSegments = "unknown_segments"
    }
}

public struct ReceiverStats: Codable, Sendable, Equatable {
    public let ip: String
    public let auth: String
    public let deviceChannel: String?
    public let phase: String
    public let uiState: ReceiverUIState
    public let displayLatencyMs: Int64?
    public let volumeOriginal: UInt8?
    public let volumePremuteAck: UInt8?
    public let volumeNow: UInt8?
    public let mirrorMode: UInt32?
    public let state: UInt32?
    public let setupTransport: String
    public let timeOffsetUs: Int64?
    public let timerRequests: UInt64
    public let videoLatencyMsgs: UInt64
    public let rtspRuntimeRequests: UInt64
    public let heartbeatAcks: UInt64
    public let ausWritten: UInt64
    public let bytesWritten: UInt64
    public let runtimeTimeOffsetsSent: UInt64
    public let runtimeTimeOffsetsAcked: UInt64
    public let pausesAcked: UInt64
    public let resumesAcked: UInt64
    public let volumeAfterPlayAck: UInt8?
    public let reconnects: UInt64
    public let joinedLate: Bool
    public let ausBlackholed: UInt64
    public let channelAck: String?
    public let latencyE2eEstMs: Int64?
    public let latencyScheduleAheadMs: Int64?
    public let notifies: [String]
    public let error: String?

    enum CodingKeys: String, CodingKey {
        case ip
        case auth
        case deviceChannel = "device_channel"
        case phase
        case uiState = "ui_state"
        case displayLatencyMs = "display_latency_ms"
        case volumeOriginal = "volume_original"
        case volumePremuteAck = "volume_premute_ack"
        case volumeNow = "volume_now"
        case mirrorMode = "mirror_mode"
        case state
        case setupTransport = "setup_transport"
        case timeOffsetUs = "time_offset_us"
        case timerRequests = "timer_requests"
        case videoLatencyMsgs = "video_latency_msgs"
        case rtspRuntimeRequests = "rtsp_runtime_requests"
        case heartbeatAcks = "heartbeat_acks"
        case ausWritten = "aus_written"
        case bytesWritten = "bytes_written"
        case runtimeTimeOffsetsSent = "runtime_time_offsets_sent"
        case runtimeTimeOffsetsAcked = "runtime_time_offsets_acked"
        case pausesAcked = "pauses_acked"
        case resumesAcked = "resumes_acked"
        case volumeAfterPlayAck = "volume_after_play_ack"
        case reconnects
        case joinedLate = "joined_late"
        case ausBlackholed = "aus_blackholed"
        case channelAck = "channel_ack"
        case latencyE2eEstMs = "latency_e2e_est_ms"
        case latencyScheduleAheadMs = "latency_schedule_ahead_ms"
        case notifies
        case error
    }
}

public struct EngineStats: Codable, Sendable, Equatable {
    public let uptimeMs: UInt64
    public let ausSent: UInt64
    public let timelineCorrections: UInt64
    public let timeOffsetUs: Int64
    public let playbackDelayUs: Int64
    public let paused: Bool
    public let latency: LatencyStats
    public let receivers: [ReceiverStats]

    enum CodingKeys: String, CodingKey {
        case uptimeMs = "uptime_ms"
        case ausSent = "aus_sent"
        case timelineCorrections = "timeline_corrections"
        case timeOffsetUs = "time_offset_us"
        case playbackDelayUs = "playback_delay_us"
        case paused
        case latency
        case receivers
    }
}

public enum EngineStatsEnvelope: Decodable, Sendable, Equatable {
    case error(String)
    case preSessionQueued(receivers: [ReceiverStats])
    case active(EngineStats)

    enum CodingKeys: String, CodingKey {
        case error
        case receivers
        case uptimeMs = "uptime_ms"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let err = try c.decodeIfPresent(String.self, forKey: .error) {
            self = .error(err)
            return
        }
        if c.contains(.uptimeMs) {
            self = .active(try EngineStats(from: decoder))
            return
        }
        let receivers = try c.decode([ReceiverStats].self, forKey: .receivers)
        self = .preSessionQueued(receivers: receivers)
    }
}

public struct AddReceiverResult: Codable, Sendable, Equatable {
    public let queued: Bool
    public let ip: String
    public let connectFinished: Bool

    enum CodingKeys: String, CodingKey {
        case queued
        case ip
        case connectFinished = "connect_finished"
    }
}

public struct SetVolumeResult: Codable, Sendable, Equatable {
    public let queued: Bool
    public let ip: String
    public let volume: Int
    public let clamped: Bool
    public let safeLimit: Int
    public let connectFinished: Bool

    enum CodingKeys: String, CodingKey {
        case queued
        case ip
        case volume
        case clamped
        case safeLimit = "safe_limit"
        case connectFinished = "connect_finished"
    }
}

public struct TimeOffsetResult: Codable, Sendable, Equatable {
    public let timeOffsetUs: Int64

    enum CodingKeys: String, CodingKey {
        case timeOffsetUs = "time_offset_us"
    }
}

public struct PlaybackDelayResult: Codable, Sendable, Equatable {
    public let playbackDelayMs: Int64
    public let timeOffsetUs: Int64

    enum CodingKeys: String, CodingKey {
        case playbackDelayMs = "playback_delay_ms"
        case timeOffsetUs = "time_offset_us"
    }
}

// MARK: - Update Models

public enum VersionComparison: Int32, Sendable, Equatable {
    case lessThan = -1
    case equal = 0
    case greaterThan = 1
    case invalid = -2

    public init(rawCode: Int32) {
        self = VersionComparison(rawValue: rawCode) ?? .invalid
    }
}

public enum UpdateDecisionStatus: String, Codable, Sendable, Equatable {
    case upToDate = "up_to_date"
    case updateAvailable = "update_available"
    case ignored = "ignored"
    case unsupportedOs = "unsupported_os"
    case error = "error"

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        let raw = try c.decode(String.self).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        self = UpdateDecisionStatus(rawValue: raw) ?? .error
    }
}

public struct ChangelogEntry: Codable, Sendable, Equatable {
    public let tag: String
    public let version: String
    public let prerelease: Bool
    public let publishedAt: String
    public let notes: String
    public let notesIsFallback: Bool

    public init(
        tag: String,
        version: String,
        prerelease: Bool,
        publishedAt: String,
        notes: String,
        notesIsFallback: Bool = false
    ) {
        self.tag = tag
        self.version = version
        self.prerelease = prerelease
        self.publishedAt = publishedAt
        self.notes = notes
        self.notesIsFallback = notesIsFallback
    }

    public var shortDate: String {
        publishedAt.count >= 10 ? String(publishedAt.prefix(10)) : publishedAt
    }

    enum CodingKeys: String, CodingKey {
        case tag
        case version
        case prerelease
        case publishedAt = "published_at"
        case notes
        case notesIsFallback = "notes_is_fallback"
    }
}

public struct UpdateDecision: Codable, Sendable, Equatable {
    public let status: UpdateDecisionStatus
    public let currentVersion: String
    public let latestTag: String
    public let latestVersion: String
    public let isPrerelease: Bool
    public let publishedAt: String
    public let releaseNotes: String
    public let notesIsFallback: Bool
    public let changelogEntries: [ChangelogEntry]
    public let versionsBehind: Int
    public let changelogTruncated: Bool
    public let releaseUrl: String
    public let assetName: String
    public let downloadUrl: String
    public let assetSizeBytes: Int64
    public let sha256: String
    public let sha256SumsUrl: String
    public let installable: Bool
    public let error: String
    public let errorCode: String
    public let errorDetail: String

    public var hasNewerVersion: Bool {
        status == .updateAvailable || status == .ignored || status == .unsupportedOs
    }

    public var shortDate: String {
        publishedAt.count >= 10 ? String(publishedAt.prefix(10)) : publishedAt
    }

    enum CodingKeys: String, CodingKey {
        case status
        case currentVersion = "current_version"
        case latestTag = "latest_tag"
        case latestVersion = "latest_version"
        case isPrerelease = "is_prerelease"
        case publishedAt = "published_at"
        case releaseNotes = "release_notes"
        case notesIsFallback = "notes_is_fallback"
        case changelogEntries = "changelog_entries"
        case versionsBehind = "versions_behind"
        case changelogTruncated = "changelog_truncated"
        case releaseUrl = "release_url"
        case assetName = "asset_name"
        case downloadUrl = "download_url"
        case assetSizeBytes = "asset_size_bytes"
        case sha256
        case sha256SumsUrl = "sha256sums_url"
        case installable
        case error
        case errorCode = "error_code"
        case errorDetail = "error_detail"
    }

    public static func error(_ message: String, currentVersion: String = "") -> UpdateDecision {
        UpdateDecision(
            status: .error,
            currentVersion: currentVersion,
            latestTag: "",
            latestVersion: "",
            isPrerelease: false,
            publishedAt: "",
            releaseNotes: "",
            notesIsFallback: false,
            changelogEntries: [],
            versionsBehind: 0,
            changelogTruncated: false,
            releaseUrl: "",
            assetName: "",
            downloadUrl: "",
            assetSizeBytes: 0,
            sha256: "",
            sha256SumsUrl: "",
            installable: false,
            error: message,
            errorCode: "",
            errorDetail: ""
        )
    }
}

// MARK: - Bridge Errors

public enum CoreBridgeError: Error, LocalizedError, Sendable, Equatable {
    case nullReturn
    case invalidJSON(String)
    case l1Error(String)
    case invalidHandle

    public var errorDescription: String? {
        switch self {
        case .nullReturn:
            return "Core returned null response"
        case .invalidJSON(let detail):
            return detail.isEmpty ? "Invalid JSON response from core" : detail
        case .l1Error(let message):
            return message.isEmpty ? "Core protocol error" : message
        case .invalidHandle:
            return "Engine handle is no longer active"
        }
    }
}

struct L1ErrorPayload: Decodable {
    let error: String
}
