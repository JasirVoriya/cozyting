import Foundation
import Testing
@testable import CozyTingCoreKit

@Suite("M7 NowPlaying Model & AppleScript Parser Tests")
struct NowPlayingModelTests {
    @Test("Identifies scriptable players vs honest-fallback apps")
    func scriptableClassification() {
        #expect(NowPlayingParser.isScriptablePlayer(bundleIdentifier: "com.apple.Music"))
        #expect(NowPlayingParser.isScriptablePlayer(bundleIdentifier: "com.spotify.client"))
        #expect(!NowPlayingParser.isScriptablePlayer(bundleIdentifier: "com.netease.163music"))
        #expect(!NowPlayingParser.isScriptablePlayer(bundleIdentifier: "com.tencent.QQMusicMac"))
        #expect(!NowPlayingParser.isScriptablePlayer(bundleIdentifier: "com.google.Chrome"))
    }

    @Test("Parses Apple Music & Spotify structured payloads and clamps position")
    func appleScriptPayloadParsing() {
        let sep = NowPlayingParser.fieldSeparator
        let appleMusicRaw = ["playing", "Midnight Walk", "Cozy Band", "Warm Light", "238.4", "84.2"].joined(separator: sep)
        let parsedMusic = NowPlayingParser.parseAppleScriptPayload(
            appleMusicRaw,
            bundleIdentifier: "com.apple.Music",
            appDisplayName: "Music"
        )
        #expect(parsedMusic != nil)
        #expect(parsedMusic?.title == "Midnight Walk")
        #expect(parsedMusic?.artist == "Cozy Band")
        #expect(parsedMusic?.isPlaying == true)
        #expect(parsedMusic?.canSeek == true)
        #expect(parsedMusic?.mode == .scriptablePlayer)
        #expect(NowPlayingParser.formatTime(parsedMusic?.positionSeconds ?? 0) == "01:24")
        #expect(NowPlayingParser.formatTime(parsedMusic?.durationSeconds ?? 0) == "03:58")

        // Spotify millisecond duration + artwork HTTPS URL + out-of-range position clamp
        let spotifyRaw = [
            "paused",
            "Rainy Evening",
            "LoFi Cat",
            "Night",
            "180000",
            "999.0",
            "https://example.com/art.jpg",
        ].joined(separator: sep)
        let parsedSpotify = NowPlayingParser.parseAppleScriptPayload(
            spotifyRaw,
            bundleIdentifier: "com.spotify.client",
            appDisplayName: "Spotify",
            durationInMilliseconds: true
        )
        #expect(parsedSpotify?.isPlaying == false)
        #expect(parsedSpotify?.durationSeconds == 180.0)
        #expect(parsedSpotify?.positionSeconds == 180.0)
        #expect(parsedSpotify?.artworkURLString == "https://example.com/art.jpg")
    }

    @Test("Returns nil for stopped/empty state and formats hours cleanly")
    func stoppedPayloadAndTimeFormatting() {
        let sep = NowPlayingParser.fieldSeparator
        let stopped = ["stopped", "", "", "", "0", "0"].joined(separator: sep)
        #expect(NowPlayingParser.parseAppleScriptPayload(stopped, bundleIdentifier: "com.apple.Music", appDisplayName: "Music") == nil)
        #expect(NowPlayingParser.formatTime(3725) == "1:02:05")
        #expect(NowPlayingParser.formatTime(-5) == "00:00")
    }
}
