import Foundation

/// Indicates whether Now Playing metadata came from a supported scriptable player
/// or uses the honest macOS 15.4+ fallback card (app icon + name + system media keys).
public enum NowPlayingMode: String, Sendable, Equatable {
    case scriptablePlayer
    case honestAppFallback
}

/// Cross-platform immutable snapshot of the Now Playing card state.
public struct NowPlayingSnapshot: Sendable, Equatable {
    public let bundleIdentifier: String
    public let appDisplayName: String
    public let title: String
    public let artist: String
    public let album: String
    public let durationSeconds: Double
    public let positionSeconds: Double
    public let isPlaying: Bool
    public let mode: NowPlayingMode
    public let canSeek: Bool
    public let artworkURLString: String?

    public init(
        bundleIdentifier: String,
        appDisplayName: String,
        title: String,
        artist: String,
        album: String = "",
        durationSeconds: Double = 0,
        positionSeconds: Double = 0,
        isPlaying: Bool = false,
        mode: NowPlayingMode,
        canSeek: Bool = false,
        artworkURLString: String? = nil
    ) {
        self.bundleIdentifier = bundleIdentifier
        self.appDisplayName = appDisplayName
        self.title = title
        self.artist = artist
        self.album = album
        let safeDuration = max(durationSeconds, 0)
        self.durationSeconds = safeDuration
        self.positionSeconds = safeDuration > 0
            ? min(max(positionSeconds, 0), safeDuration)
            : max(positionSeconds, 0)
        self.isPlaying = isPlaying
        self.mode = mode
        self.canSeek = canSeek && safeDuration > 0
        self.artworkURLString = artworkURLString
    }

    public static func honestFallback(
        bundleIdentifier: String,
        appDisplayName: String,
        isCasting: Bool,
        isPaused: Bool,
        isAudioOutputActive: Bool = false
    ) -> NowPlayingSnapshot {
        NowPlayingSnapshot(
            bundleIdentifier: bundleIdentifier,
            appDisplayName: appDisplayName,
            title: appDisplayName,
            artist: "",
            durationSeconds: 0,
            positionSeconds: 0,
            isPlaying: isCasting ? !isPaused : isAudioOutputActive,
            mode: .honestAppFallback,
            canSeek: false
        )
    }
}

/// Pure parsing & formatting utilities for `NowPlayingBridge` (`M7`).
public enum NowPlayingParser {
    public static let fieldSeparator = "\u{001F}" // ASCII Unit Separator

    /// Known macOS players that expose compliant AppleScript playback dictionaries.
    public static let scriptableBundleIDs: Set<String> = [
        "com.apple.music",
        "com.spotify.client",
    ]

    public static func isScriptablePlayer(bundleIdentifier: String) -> Bool {
        scriptableBundleIDs.contains(bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }

    /// Parses structured `state<US>title<US>artist<US>album<US>duration<US>position[<US>artworkURL]` output.
    /// Note: Spotify returns duration in milliseconds when queried directly; callers normalize to seconds
    /// (or pass `durationInMilliseconds: true`).
    public static func parseAppleScriptPayload(
        _ raw: String,
        bundleIdentifier: String,
        appDisplayName: String,
        durationInMilliseconds: Bool = false
    ) -> NowPlayingSnapshot? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        let parts = trimmed.components(separatedBy: fieldSeparator)
        guard parts.count >= 6 else { return nil }

        let state = parts[0].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if state == "stopped" || state == "missing value" || state.isEmpty {
            return nil
        }

        let title = parts[1].trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title != "missing value" else { return nil }

        let rawArtist = parts[2].trimmingCharacters(in: .whitespacesAndNewlines)
        let artist = (rawArtist == "missing value") ? "" : rawArtist
        let rawAlbum = parts[3].trimmingCharacters(in: .whitespacesAndNewlines)
        let album = (rawAlbum == "missing value") ? "" : rawAlbum

        let rawDuration = Double(parts[4].replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
        let durationSeconds = durationInMilliseconds ? (rawDuration / 1000.0) : rawDuration
        let positionSeconds = Double(parts[5].replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0

        let artworkURL: String? = parts.count >= 7
            ? {
                let candidate = parts[6].trimmingCharacters(in: .whitespacesAndNewlines)
                return candidate.hasPrefix("https://") ? candidate : nil
            }()
            : nil

        return NowPlayingSnapshot(
            bundleIdentifier: bundleIdentifier,
            appDisplayName: appDisplayName,
            title: title,
            artist: artist,
            album: album,
            durationSeconds: durationSeconds,
            positionSeconds: positionSeconds,
            isPlaying: state == "playing",
            mode: .scriptablePlayer,
            canSeek: durationSeconds > 0,
            artworkURLString: artworkURL
        )
    }

    /// Formats seconds as `MM:SS` or `H:MM:SS` for the Now Playing progress scrubber.
    public static func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "00:00" }
        let total = Int(seconds.rounded(.down))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%02d:%02d", minutes, secs)
    }
}
