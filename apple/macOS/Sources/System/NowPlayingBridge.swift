import AppKit
import CozyTingCoreKit
import Foundation
import Observation

/// Layered Now Playing bridge compliant with macOS 15.4+ `mediaremoted` restrictions (`M7`).
///
/// Strategy (`docs/MACOS_CLIENT_DESIGN.md` §5.1):
/// - **Layer 1 (Scriptable Players: Apple Music / Spotify)**: Only when the selected source is
///   scriptable AND already running, queries playback metadata & controls via `NSAppleScript`
///   off the main thread (never cold-launches closed apps or injects private `MediaRemote`).
/// - **Layer 2 (Honest Fallback: NetEase, QQ Music, Chrome, Safari, etc.)**: Displays the selected
///   source app icon & name cleanly without fabricating progress bars, and dispatches standard
///   macOS media keys (`NX_KEYTYPE_PLAY`, `NX_KEYTYPE_NEXT`, `NX_KEYTYPE_PREVIOUS`).
@MainActor
@Observable
public final class NowPlayingBridge {
    public private(set) var snapshot: NowPlayingSnapshot?
    public private(set) var artworkImage: NSImage?

    private let scriptQueue = DispatchQueue(label: "com.cozyting.macos.nowplaying-script", qos: .utility)
    @ObservationIgnored nonisolated(unsafe) private var pollTask: Task<Void, Never>?
    private var lastArtworkURL: String?
    @ObservationIgnored nonisolated(unsafe) private var artworkFetchTask: Task<Void, Never>?

    public init() {}

    deinit {
        pollTask?.cancel()
        artworkFetchTask?.cancel()
    }

    public func startPolling(
        sourceProvider: @escaping @MainActor () -> AudioSourceSelection?,
        castingStateProvider: @escaping @MainActor () -> (isCasting: Bool, isPaused: Bool)
    ) {
        stopPolling()
        pollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                let source = sourceProvider()
                let castState = castingStateProvider()
                await self?.refreshOnce(
                    source: source,
                    isCasting: castState.isCasting,
                    isPaused: castState.isPaused
                )
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    public func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
        artworkFetchTask?.cancel()
        artworkFetchTask = nil
    }

    public func refreshOnce(
        source: AudioSourceSelection?,
        isCasting: Bool,
        isPaused: Bool
    ) async {
        guard let source, !source.bundleIdentifier.isEmpty else {
            snapshot = nil
            artworkImage = nil
            lastArtworkURL = nil
            return
        }

        let bid = source.bundleIdentifier
        let name = source.displayName

        #if !COZYTING_APP_STORE
        if NowPlayingParser.isScriptablePlayer(bundleIdentifier: bid),
           isAppRunning(bundleIdentifier: bid) {
            if let parsed = await queryScriptablePlayer(bundleIdentifier: bid, appDisplayName: name) {
                snapshot = parsed
                updateArtworkIfNeeded(for: parsed)
                return
            }
        }
        #endif

        // Honest Layer 2 fallback: clear remote artwork and reflect actual selected source.
        artworkImage = nil
        lastArtworkURL = nil
        snapshot = NowPlayingSnapshot.honestFallback(
            bundleIdentifier: bid,
            appDisplayName: name,
            isCasting: isCasting,
            isPaused: isPaused
        )
    }

    // MARK: - Playback Controls

    public func playPause(source: AudioSourceSelection?) {
        #if !COZYTING_APP_STORE
        if let bid = source?.bundleIdentifier,
           NowPlayingParser.isScriptablePlayer(bundleIdentifier: bid),
           isAppRunning(bundleIdentifier: bid) {
            let appName = scriptApplicationName(for: bid)
            runAppleScriptAsync("tell application \"\(appName)\" to playpause")
            return
        }
        #endif
        postSystemMediaKey(NX_KEYTYPE_PLAY)
    }

    public func nextTrack(source: AudioSourceSelection?) {
        #if !COZYTING_APP_STORE
        if let bid = source?.bundleIdentifier,
           NowPlayingParser.isScriptablePlayer(bundleIdentifier: bid),
           isAppRunning(bundleIdentifier: bid) {
            let appName = scriptApplicationName(for: bid)
            runAppleScriptAsync("tell application \"\(appName)\" to next track")
            return
        }
        #endif
        postSystemMediaKey(NX_KEYTYPE_NEXT)
    }

    public func previousTrack(source: AudioSourceSelection?) {
        #if !COZYTING_APP_STORE
        if let bid = source?.bundleIdentifier,
           NowPlayingParser.isScriptablePlayer(bundleIdentifier: bid),
           isAppRunning(bundleIdentifier: bid) {
            let appName = scriptApplicationName(for: bid)
            runAppleScriptAsync("tell application \"\(appName)\" to previous track")
            return
        }
        #endif
        postSystemMediaKey(NX_KEYTYPE_PREVIOUS)
    }

    public func seek(toSeconds seconds: Double, source: AudioSourceSelection?) {
        #if !COZYTING_APP_STORE
        guard let bid = source?.bundleIdentifier,
              NowPlayingParser.isScriptablePlayer(bundleIdentifier: bid),
              isAppRunning(bundleIdentifier: bid) else {
            return
        }
        let clamped = max(seconds, 0)
        let appName = scriptApplicationName(for: bid)
        runAppleScriptAsync("tell application \"\(appName)\" to set player position to \(clamped)")
        #endif
    }

    // MARK: - Private AppleScript & Media Key Helpers

    private func isAppRunning(bundleIdentifier: String) -> Bool {
        NSWorkspace.shared.runningApplications.contains {
            $0.bundleIdentifier?.caseInsensitiveCompare(bundleIdentifier) == .orderedSame
        }
    }

    private func scriptApplicationName(for bundleIdentifier: String) -> String {
        bundleIdentifier.lowercased() == "com.spotify.client" ? "Spotify" : "Music"
    }

    private func queryScriptablePlayer(
        bundleIdentifier: String,
        appDisplayName: String
    ) async -> NowPlayingSnapshot? {
        let isSpotify = bundleIdentifier.lowercased() == "com.spotify.client"
        let sep = NowPlayingParser.fieldSeparator

        let script: String
        if isSpotify {
            script = """
            tell application "Spotify"
                if player state is stopped then return "stopped"
                set s to player state as string
                set t to name of current track
                set a to artist of current track
                set al to album of current track
                set d to duration of current track
                set p to player position
                set u to artwork url of current track
                return s & "\(sep)" & t & "\(sep)" & a & "\(sep)" & al & "\(sep)" & d & "\(sep)" & p & "\(sep)" & u
            end tell
            """
        } else {
            script = """
            tell application "Music"
                if player state is stopped then return "stopped"
                set s to player state as string
                set t to name of current track
                set a to artist of current track
                set al to album of current track
                set d to duration of current track
                set p to player position
                return s & "\(sep)" & t & "\(sep)" & a & "\(sep)" & al & "\(sep)" & d & "\(sep)" & p
            end tell
            """
        }

        let raw: String? = await withCheckedContinuation { cont in
            scriptQueue.async {
                var errInfo: NSDictionary?
                let appleScript = NSAppleScript(source: script)
                let descriptor = appleScript?.executeAndReturnError(&errInfo)
                cont.resume(returning: descriptor?.stringValue)
            }
        }

        guard let raw else { return nil }
        return NowPlayingParser.parseAppleScriptPayload(
            raw,
            bundleIdentifier: bundleIdentifier,
            appDisplayName: appDisplayName,
            durationInMilliseconds: isSpotify
        )
    }

    private func runAppleScriptAsync(_ source: String) {
        scriptQueue.async {
            var errInfo: NSDictionary?
            NSAppleScript(source: source)?.executeAndReturnError(&errInfo)
        }
    }

    private func updateArtworkIfNeeded(for snapshot: NowPlayingSnapshot) {
        guard let urlStr = snapshot.artworkURLString,
              urlStr != lastArtworkURL,
              let url = URL(string: urlStr) else {
            if snapshot.artworkURLString == nil {
                artworkImage = nil
                lastArtworkURL = nil
            }
            return
        }
        lastArtworkURL = urlStr
        artworkFetchTask?.cancel()
        artworkFetchTask = Task { [weak self] in
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  !Task.isCancelled,
                  let image = NSImage(data: data) else {
                return
            }
            self?.artworkImage = image
        }
    }

    private func postSystemMediaKey(_ keyType: Int32) {
        func post(down: Bool) {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xA00 : 0xB00)
            let data1 = Int((keyType << 16) | ((down ? 0xA : 0xB) << 8))
            guard let event = NSEvent.otherEvent(
                with: .systemDefined,
                location: .zero,
                modifierFlags: flags,
                timestamp: 0,
                windowNumber: 0,
                context: nil,
                subtype: 8,
                data1: data1,
                data2: -1
            ) else { return }
            event.cgEvent?.post(tap: .cghidEventTap)
        }
        post(down: true)
        post(down: false)
    }
}

private let NX_KEYTYPE_PLAY: Int32 = 16
private let NX_KEYTYPE_NEXT: Int32 = 17
private let NX_KEYTYPE_PREVIOUS: Int32 = 18
