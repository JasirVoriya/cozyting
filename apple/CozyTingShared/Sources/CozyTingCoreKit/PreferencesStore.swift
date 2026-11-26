import Foundation

// MARK: - Parity Enums & Source Models

public enum ThemeMode: String, Codable, Sendable, Equatable, CaseIterable {
    case system = "system"
    case light = "light"
    case dark = "dark"

    public static func from(id raw: String?) -> ThemeMode {
        let normalized = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        return ThemeMode(rawValue: normalized) ?? .system
    }
}

public enum AppLanguage: String, Codable, Sendable, Equatable, CaseIterable {
    case system = "system"
    case zhCN = "zh-CN"
    case en = "en"
    case ja = "ja"

    public var languageTag: String {
        switch self {
        case .system: return ""
        case .zhCN: return "zh-CN"
        case .en: return "en"
        case .ja: return "ja"
        }
    }

    public var nativeLabel: String? {
        switch self {
        case .system: return nil
        case .zhCN: return "简体中文"
        case .en: return "English"
        case .ja: return "日本語"
        }
    }

    public static func from(id raw: String?) -> AppLanguage {
        let norm = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        switch norm {
        case "zh-cn", "zh-hans", "zh": return .zhCN
        case "en": return .en
        case "ja": return .ja
        default: return .system
        }
    }
}

public enum UpdateChannel: String, Codable, Sendable, Equatable, CaseIterable {
    case stable = "stable"
    case prerelease = "prerelease"

    public static func from(id raw: String?) -> UpdateChannel? {
        let norm = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        return UpdateChannel(rawValue: norm)
    }

    public static func defaultForCurrentVersion(_ versionName: String) -> UpdateChannel {
        isPrereleaseVersion(versionName) ? .prerelease : .stable
    }

    public static func isPrereleaseVersion(_ versionName: String) -> Bool {
        stripBuildSuffixes(versionName).contains("-")
    }

    public static func stripBuildSuffixes(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("v") || s.hasPrefix("V") {
            s.removeFirst()
        }
        if let plusIdx = s.firstIndex(of: "+") {
            s = String(s[..<plusIdx])
        }
        guard let dashIdx = s.firstIndex(of: "-") else { return s }
        let base = String(s[..<dashIdx])
        var parts = s[s.index(after: dashIdx)...]
            .split(separator: "-")
            .map(String.init)
            .filter { !$0.isEmpty }
        while let last = parts.last?.lowercased(),
              last == "release" || last == "acceptance" || last == "debug" || last == "standard" {
            parts.removeLast()
        }
        return parts.isEmpty ? base : "\(base)-\(parts.joined(separator: "-"))"
    }
}

public enum SourceMode: String, Codable, Sendable, Equatable {
    case app = "app"
    case silent = "silent"

    public static func from(raw: String?) -> SourceMode {
        raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "silent" ? .silent : .app
    }
}

/// Persisted target audio application identity.
/// Strictly persists `bundleIdentifier` and `displayName` (NEVER transient PIDs).
public struct AudioSourceSelection: Codable, Sendable, Equatable {
    public let bundleIdentifier: String
    public let displayName: String

    public init(bundleIdentifier: String, displayName: String) {
        self.bundleIdentifier = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        self.displayName = trimmedName.isEmpty ? self.bundleIdentifier : trimmedName
    }
}

public struct FavoriteAudioSource: Codable, Sendable, Equatable {
    public let bundleIdentifier: String
    public let autoDetectEnabled: Bool

    public init(bundleIdentifier: String, autoDetectEnabled: Bool = true) {
        self.bundleIdentifier = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        self.autoDetectEnabled = autoDetectEnabled
    }
}

// MARK: - PreferencesStore (1:1 Android Parity)

/// Thread-safe `UserDefaults` persistence store matching Android preference keys,
/// defaults, LRU eviction, and cold-start safety clamping.
public final class PreferencesStore: @unchecked Sendable {
    public enum Keys {
        public static let themeMode = "theme_mode"
        public static let appLanguage = "app_language"
        public static let keepLocalOutput = "keep_local_output"
        public static let autoDetectAudioSource = "auto_detect_audio_source"
        public static let volumeGuardEnabled = "volume_guard_enabled"
        public static let volumeGuardSafeLimit = "volume_guard_safe_limit"
        public static let showAllDiscovered = "show_all_discovered"
        public static let sourceMode = "source_mode"
        public static let delayMs = "delay_ms"
        public static let refFunction = "ref_function"
        public static let authMode = "auth_mode"
        public static let masterVolume = "master_volume"
        public static let speakerVolumesJSON = "speaker_volumes_json"
        public static let devicesJSON = "devices_json"
        public static let lastSelectedSpeakersJSON = "last_selected_speakers_json"
        public static let selectedAudioSource = "selected_audio_source"
        public static let recentAudioSources = "recent_audio_sources"
        public static let favoriteAudioSources = "favorite_audio_sources"
        public static let favoriteAudioSourcesInitialized = "favorite_audio_sources_initialized"
        public static let updateAutoCheck = "update_auto_check"
        public static let updateChannel = "update_channel"
        public static let updateIgnoredTag = "update_ignored_tag"
        public static let updateDismissedBannerTag = "update_dismissed_banner_tag"
        public static let updateLastCheckMs = "update_last_check_ms"
        public static let updateEtag = "update_etag"
        public static let updateCachedManifestJSON = "update_cached_manifest_json"
    }

    public static let defaultMasterVolume = 30
    public static let maxSpeakerVolumes = 32
    public static let maxCachedDevices = 16
    public static let maxRecentSources = 8
    private static let unsetVolumeSentinel = -1

    private struct SpeakerVolumeEntry: Codable {
        let id: String
        let volume: Int
    }

    private struct LossyDeviceElement: Decodable {
        let device: Device?

        init(from decoder: Decoder) throws {
            self.device = try? Device(from: decoder)
        }
    }

    private let lock = NSLock()
    nonisolated(unsafe) private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(userDefaults: UserDefaults = .standard) {
        self.defaults = userDefaults
    }

    // MARK: - Appearance & Language

    public var themeMode: ThemeMode {
        get {
            lock.withLock { ThemeMode.from(id: defaults.string(forKey: Keys.themeMode)) }
        }
        set {
            lock.withLock { defaults.set(newValue.rawValue, forKey: Keys.themeMode) }
        }
    }

    public var appLanguage: AppLanguage {
        get {
            lock.withLock { AppLanguage.from(id: defaults.string(forKey: Keys.appLanguage)) }
        }
        set {
            lock.withLock { defaults.set(newValue.rawValue, forKey: Keys.appLanguage) }
        }
    }

    // MARK: - Audio & Mute Behavior

    /// Parity with Android `keep_local_output` (default `false`):
    /// muting the tapped local audio app during cast is ON (`true`) by default.
    public var keepLocalOutput: Bool {
        get {
            lock.withLock {
                defaults.object(forKey: Keys.keepLocalOutput) as? Bool ?? false
            }
        }
        set {
            lock.withLock { defaults.set(newValue, forKey: Keys.keepLocalOutput) }
        }
    }

    public var muteLocalAudio: Bool {
        get { !keepLocalOutput }
        set { keepLocalOutput = !newValue }
    }

    public var autoDetectAudioSource: Bool {
        get {
            lock.withLock {
                defaults.object(forKey: Keys.autoDetectAudioSource) as? Bool ?? true
            }
        }
        set {
            lock.withLock { defaults.set(newValue, forKey: Keys.autoDetectAudioSource) }
        }
    }

    public var showAllDiscovered: Bool {
        get {
            lock.withLock {
                defaults.object(forKey: Keys.showAllDiscovered) as? Bool ?? false
            }
        }
        set {
            lock.withLock { defaults.set(newValue, forKey: Keys.showAllDiscovered) }
        }
    }

    public var sourceMode: SourceMode {
        get {
            lock.withLock { SourceMode.from(raw: defaults.string(forKey: Keys.sourceMode)) }
        }
        set {
            lock.withLock { defaults.set(newValue.rawValue, forKey: Keys.sourceMode) }
        }
    }

    public var delayMs: Int {
        get {
            lock.withLock {
                max(defaults.object(forKey: Keys.delayMs) as? Int ?? 0, 0)
            }
        }
        set {
            lock.withLock { defaults.set(max(newValue, 0), forKey: Keys.delayMs) }
        }
    }

    public var refFunction: String {
        get {
            lock.withLock { defaults.string(forKey: Keys.refFunction) ?? "" }
        }
        set {
            lock.withLock { defaults.set(newValue, forKey: Keys.refFunction) }
        }
    }

    public var authMode: AuthMode {
        get {
            lock.withLock {
                let raw = defaults.object(forKey: Keys.authMode) as? Int ?? 0
                return AuthMode.clamped(raw)
            }
        }
        set {
            lock.withLock { defaults.set(Int(newValue.rawValue), forKey: Keys.authMode) }
        }
    }

    // MARK: - Volume Guard & Volumes

    public var volumeGuardEnabled: Bool {
        get {
            lock.withLock {
                defaults.object(forKey: Keys.volumeGuardEnabled) as? Bool ?? true
            }
        }
        set {
            lock.withLock { defaults.set(newValue, forKey: Keys.volumeGuardEnabled) }
        }
    }

    public var volumeGuardSafeLimit: Int {
        get {
            lock.withLock {
                let raw = defaults.object(forKey: Keys.volumeGuardSafeLimit) as? Int
                    ?? VolumeGuardPolicy.defaultSafeLimit
                return VolumeGuardPolicy.snapSafeLimit(raw)
            }
        }
        set {
            lock.withLock {
                defaults.set(VolumeGuardPolicy.snapSafeLimit(newValue), forKey: Keys.volumeGuardSafeLimit)
            }
        }
    }

    public var volumeGuardPolicy: VolumeGuardPolicy {
        VolumeGuardPolicy(enabled: volumeGuardEnabled, safeLimit: volumeGuardSafeLimit)
    }

    public func loadMasterVolume(defaultVolume: Int = PreferencesStore.defaultMasterVolume) -> Int {
        lock.withLock {
            let raw = defaults.object(forKey: Keys.masterVolume) as? Int ?? Self.unsetVolumeSentinel
            if raw == Self.unsetVolumeSentinel {
                return min(max(defaultVolume, 0), 100)
            }
            return min(max(raw, 0), 100)
        }
    }

    public func loadMasterVolumeForColdStart(
        defaultVolume: Int = PreferencesStore.defaultMasterVolume
    ) -> Int {
        let saved = loadMasterVolume(defaultVolume: defaultVolume)
        return volumeGuardPolicy.clampForColdStartRestore(saved)
    }

    public func saveMasterVolume(_ volume: Int) {
        lock.withLock {
            defaults.set(min(max(volume, 0), 100), forKey: Keys.masterVolume)
        }
    }

    /// Loads per-speaker independent volumes in LRU order (oldest first, most-recent last).
    public func loadSpeakerVolumeOrder() -> [(id: String, volume: Int)] {
        lock.withLock { loadSpeakerVolumeOrderLocked() }
    }

    public func loadSpeakerVolumes() -> [String: Int] {
        let entries = loadSpeakerVolumeOrder()
        var dict: [String: Int] = [:]
        for entry in entries {
            dict[entry.id] = entry.volume
        }
        return dict
    }

    public func loadSpeakerVolumesForColdStart() -> [String: Int] {
        let policy = volumeGuardPolicy
        var dict: [String: Int] = [:]
        for entry in loadSpeakerVolumeOrder() {
            dict[entry.id] = policy.clampForColdStartRestore(entry.volume)
        }
        return dict
    }

    public func saveSpeakerVolume(deviceID: String, volume: Int) {
        let cleanID = deviceID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanID.isEmpty else { return }
        let clamped = min(max(volume, 0), 100)
        lock.withLock {
            var entries = loadSpeakerVolumeOrderLocked().filter { $0.id != cleanID }
            entries.append((id: cleanID, volume: clamped))
            if entries.count > Self.maxSpeakerVolumes {
                entries.removeFirst(entries.count - Self.maxSpeakerVolumes)
            }
            saveSpeakerVolumeOrderLocked(entries)
        }
    }

    public func saveSpeakerVolumes(_ ordered: [(id: String, volume: Int)]) {
        lock.withLock {
            var dedup: [(id: String, volume: Int)] = []
            for item in ordered {
                let cleanID = item.id.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !cleanID.isEmpty else { continue }
                dedup.removeAll { $0.id == cleanID }
                dedup.append((id: cleanID, volume: min(max(item.volume, 0), 100)))
            }
            if dedup.count > Self.maxSpeakerVolumes {
                dedup.removeFirst(dedup.count - Self.maxSpeakerVolumes)
            }
            saveSpeakerVolumeOrderLocked(dedup)
        }
    }

    private func loadSpeakerVolumeOrderLocked() -> [(id: String, volume: Int)] {
        guard let raw = defaults.string(forKey: Keys.speakerVolumesJSON),
              !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = raw.data(using: .utf8) else {
            return []
        }
        if let list = try? decoder.decode([SpeakerVolumeEntry].self, from: data) {
            return list.compactMap { entry in
                let id = entry.id.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !id.isEmpty, (0...100).contains(entry.volume) else { return nil }
                return (id: id, volume: entry.volume)
            }
        }
        if let dict = try? decoder.decode([String: Int].self, from: data) {
            return dict.keys.sorted().compactMap { key in
                let id = key.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !id.isEmpty, let v = dict[key], (0...100).contains(v) else { return nil }
                return (id: id, volume: v)
            }
        }
        return []
    }

    private func saveSpeakerVolumeOrderLocked(_ entries: [(id: String, volume: Int)]) {
        let encodable = entries.map { SpeakerVolumeEntry(id: $0.id, volume: $0.volume) }
        if let data = try? encoder.encode(encodable),
           let str = String(data: data, encoding: .utf8) {
            defaults.set(str, forKey: Keys.speakerVolumesJSON)
        }
    }

    // MARK: - Devices & Last Selected Speaker Group

    public func loadCachedDevices() -> [Device] {
        lock.withLock {
            guard let raw = defaults.string(forKey: Keys.devicesJSON),
                  let data = raw.data(using: .utf8),
                  let decoded = try? decoder.decode([LossyDeviceElement].self, from: data) else {
                return []
            }
            var seen: Set<String> = []
            var out: [Device] = []
            for d in decoded.compactMap(\.device) where !d.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                if seen.insert(d.id).inserted {
                    out.append(d)
                }
            }
            return Array(out.prefix(Self.maxCachedDevices))
        }
    }

    public func saveCachedDevices(_ devices: [Device]) {
        lock.withLock {
            var seen: Set<String> = []
            var cacheable: [Device] = []
            for d in devices {
                let id = d.id.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !id.isEmpty,
                      d.kind == .speaker || d.kind == .tv,
                      d.castable,
                      seen.insert(id).inserted else {
                    continue
                }
                cacheable.append(
                    Device(
                        id: id,
                        name: d.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? id : d.name,
                        kind: d.kind,
                        castable: true,
                        selected: false,
                        detail: "",
                        protocol: d.protocol ?? .miplay,
                        nameFromDeviceInfo: d.nameFromDeviceInfo,
                        roomName: d.roomName,
                        roomInfoQueried: d.roomInfoQueried
                    )
                )
                if cacheable.count >= Self.maxCachedDevices {
                    break
                }
            }
            if let data = try? encoder.encode(cacheable),
               let str = String(data: data, encoding: .utf8) {
                defaults.set(str, forKey: Keys.devicesJSON)
            }
        }
    }

    public func loadLastSelectedSpeakerIds() -> [String] {
        lock.withLock {
            guard let raw = defaults.string(forKey: Keys.lastSelectedSpeakersJSON),
                  let data = raw.data(using: .utf8),
                  let decoded = try? decoder.decode([String].self, from: data) else {
                return []
            }
            var seen: Set<String> = []
            var out: [String] = []
            for rawID in decoded {
                let id = rawID.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !id.isEmpty, id != "this", seen.insert(id).inserted else { continue }
                out.append(id)
                if out.count >= Self.maxCachedDevices { break }
            }
            return out
        }
    }

    public func saveLastSelectedSpeakerIds(_ ids: [String]) {
        lock.withLock {
            var seen: Set<String> = []
            var clean: [String] = []
            for rawID in ids {
                let id = rawID.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !id.isEmpty, id != "this", seen.insert(id).inserted else { continue }
                clean.append(id)
                if clean.count >= Self.maxCachedDevices { break }
            }
            if let data = try? encoder.encode(clean),
               let str = String(data: data, encoding: .utf8) {
                defaults.set(str, forKey: Keys.lastSelectedSpeakersJSON)
            }
        }
    }

    // MARK: - Target Audio Sources & Favorites (Bundle ID + Display Name, NEVER PID)

    public func loadSelectedAudioSource() -> AudioSourceSelection? {
        lock.withLock {
            guard let data = defaults.data(forKey: Keys.selectedAudioSource),
                  let decoded = try? decoder.decode(AudioSourceSelection.self, from: data),
                  !decoded.bundleIdentifier.isEmpty else {
                return nil
            }
            return decoded
        }
    }

    public func saveSelectedAudioSource(_ selection: AudioSourceSelection) {
        guard !selection.bundleIdentifier.isEmpty else { return }
        lock.withLock {
            if let data = try? encoder.encode(selection) {
                defaults.set(data, forKey: Keys.selectedAudioSource)
            }
            var recent = loadRecentAudioSourcesLocked().filter {
                $0.bundleIdentifier != selection.bundleIdentifier
            }
            recent.insert(selection, at: 0)
            if recent.count > Self.maxRecentSources {
                recent = Array(recent.prefix(Self.maxRecentSources))
            }
            if let recentData = try? encoder.encode(recent) {
                defaults.set(recentData, forKey: Keys.recentAudioSources)
            }
        }
    }

    public func clearSelectedAudioSource() {
        lock.withLock {
            defaults.removeObject(forKey: Keys.selectedAudioSource)
            defaults.removeObject(forKey: Keys.recentAudioSources)
        }
    }

    public func loadRecentAudioSources(limit: Int = PreferencesStore.maxRecentSources) -> [AudioSourceSelection] {
        lock.withLock {
            Array(loadRecentAudioSourcesLocked().prefix(max(limit, 0)))
        }
    }

    private func loadRecentAudioSourcesLocked() -> [AudioSourceSelection] {
        guard let data = defaults.data(forKey: Keys.recentAudioSources),
              let decoded = try? decoder.decode([AudioSourceSelection].self, from: data) else {
            return []
        }
        var seen: Set<String> = []
        return decoded.filter { !$0.bundleIdentifier.isEmpty && seen.insert($0.bundleIdentifier).inserted }
    }

    public var hasInitializedFavoriteSources: Bool {
        lock.withLock {
            defaults.object(forKey: Keys.favoriteAudioSourcesInitialized) as? Bool ?? false
        }
    }

    public func loadFavoriteAudioSources() -> [FavoriteAudioSource] {
        lock.withLock { loadFavoriteAudioSourcesLocked() }
    }

    public func saveFavoriteAudioSources(_ sources: [FavoriteAudioSource]) {
        lock.withLock { saveFavoriteAudioSourcesLocked(sources) }
    }

    @discardableResult
    public func addFavoriteAudioSource(
        bundleIdentifier: String,
        autoDetectEnabled: Bool = true
    ) -> [FavoriteAudioSource] {
        let clean = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        return lock.withLock {
            var current = loadFavoriteAudioSourcesLocked()
            if !clean.isEmpty, !current.contains(where: { $0.bundleIdentifier == clean }) {
                current.append(FavoriteAudioSource(bundleIdentifier: clean, autoDetectEnabled: autoDetectEnabled))
                saveFavoriteAudioSourcesLocked(current)
            }
            return current
        }
    }

    @discardableResult
    public func removeFavoriteAudioSource(bundleIdentifier: String) -> [FavoriteAudioSource] {
        let clean = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        return lock.withLock {
            let updated = loadFavoriteAudioSourcesLocked().filter { $0.bundleIdentifier != clean }
            saveFavoriteAudioSourcesLocked(updated)
            return updated
        }
    }

    @discardableResult
    public func setFavoriteAudioSourceAutoDetect(
        bundleIdentifier: String,
        enabled: Bool
    ) -> [FavoriteAudioSource] {
        let clean = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        return lock.withLock {
            let updated = loadFavoriteAudioSourcesLocked().map { item in
                item.bundleIdentifier == clean
                    ? FavoriteAudioSource(bundleIdentifier: clean, autoDetectEnabled: enabled)
                    : item
            }
            saveFavoriteAudioSourcesLocked(updated)
            return updated
        }
    }

    @discardableResult
    public func reorderFavoriteAudioSources(
        fromBundleIdentifier: String,
        toBundleIdentifier: String
    ) -> [FavoriteAudioSource] {
        lock.withLock {
            var list = loadFavoriteAudioSourcesLocked()
            guard let fromIdx = list.firstIndex(where: { $0.bundleIdentifier == fromBundleIdentifier }),
                  let toIdx = list.firstIndex(where: { $0.bundleIdentifier == toBundleIdentifier }),
                  fromIdx != toIdx else {
                return list
            }
            let item = list.remove(at: fromIdx)
            list.insert(item, at: toIdx)
            saveFavoriteAudioSourcesLocked(list)
            return list
        }
    }

    private func loadFavoriteAudioSourcesLocked() -> [FavoriteAudioSource] {
        guard let data = defaults.data(forKey: Keys.favoriteAudioSources),
              let decoded = try? decoder.decode([FavoriteAudioSource].self, from: data) else {
            return []
        }
        var seen: Set<String> = []
        return decoded.filter { !$0.bundleIdentifier.isEmpty && seen.insert($0.bundleIdentifier).inserted }
    }

    private func saveFavoriteAudioSourcesLocked(_ sources: [FavoriteAudioSource]) {
        var seen: Set<String> = []
        var dedup: [FavoriteAudioSource] = []
        for s in sources {
            let clean = s.bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !clean.isEmpty, seen.insert(clean).inserted else { continue }
            dedup.append(FavoriteAudioSource(bundleIdentifier: clean, autoDetectEnabled: s.autoDetectEnabled))
        }
        if let data = try? encoder.encode(dedup) {
            defaults.set(data, forKey: Keys.favoriteAudioSources)
            defaults.set(true, forKey: Keys.favoriteAudioSourcesInitialized)
        }
    }

    // MARK: - Update Preferences

    public var updateAutoCheck: Bool {
        get {
            lock.withLock { defaults.object(forKey: Keys.updateAutoCheck) as? Bool ?? true }
        }
        set {
            lock.withLock { defaults.set(newValue, forKey: Keys.updateAutoCheck) }
        }
    }

    public var updateChannelRaw: String {
        get {
            lock.withLock { defaults.string(forKey: Keys.updateChannel) ?? "" }
        }
        set {
            lock.withLock {
                defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Keys.updateChannel)
            }
        }
    }

    public func resolvedUpdateChannel(currentVersionName: String) -> UpdateChannel {
        UpdateChannel.from(id: updateChannelRaw)
            ?? UpdateChannel.defaultForCurrentVersion(currentVersionName)
    }

    public var ignoredUpdateTag: String {
        get {
            lock.withLock { defaults.string(forKey: Keys.updateIgnoredTag) ?? "" }
        }
        set {
            lock.withLock {
                defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Keys.updateIgnoredTag)
            }
        }
    }

    public var dismissedUpdateBannerTag: String {
        get {
            lock.withLock { defaults.string(forKey: Keys.updateDismissedBannerTag) ?? "" }
        }
        set {
            lock.withLock {
                defaults.set(
                    newValue.trimmingCharacters(in: .whitespacesAndNewlines),
                    forKey: Keys.updateDismissedBannerTag
                )
            }
        }
    }

    public var lastUpdateCheckMs: Int64 {
        get {
            lock.withLock {
                let raw = defaults.object(forKey: Keys.updateLastCheckMs) as? Int64
                    ?? Int64(defaults.object(forKey: Keys.updateLastCheckMs) as? Int ?? 0)
                return max(raw, 0)
            }
        }
        set {
            lock.withLock { defaults.set(max(newValue, 0), forKey: Keys.updateLastCheckMs) }
        }
    }

    public var updateEtag: String {
        get {
            lock.withLock { defaults.string(forKey: Keys.updateEtag) ?? "" }
        }
        set {
            lock.withLock { defaults.set(newValue, forKey: Keys.updateEtag) }
        }
    }

    public var cachedUpdateManifestJSON: String {
        get {
            lock.withLock { defaults.string(forKey: Keys.updateCachedManifestJSON) ?? "" }
        }
        set {
            lock.withLock { defaults.set(newValue, forKey: Keys.updateCachedManifestJSON) }
        }
    }
}
