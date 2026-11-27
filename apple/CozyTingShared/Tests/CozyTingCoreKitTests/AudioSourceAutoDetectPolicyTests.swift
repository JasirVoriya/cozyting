import Foundation
import Testing
@testable import CozyTingCoreKit

@Suite("M6 AudioSourceAutoDetectPolicy Tests")
struct AudioSourceAutoDetectPolicyTests {
    private let favorites = [
        FavoriteAudioSource(bundleIdentifier: "com.apple.Music", autoDetectEnabled: true),
        FavoriteAudioSource(bundleIdentifier: "com.tencent.QQMusicMac", autoDetectEnabled: true),
        FavoriteAudioSource(bundleIdentifier: "com.google.Chrome", autoDetectEnabled: false),
    ]

    @Test("Short chimes below 700ms debounce and non-whitelisted apps never trigger switch")
    func shortChimesAndNonWhitelistedIgnored() {
        var policy = AudioSourceAutoDetectPolicy(debounceSeconds: 0.7, manualCooldownSeconds: 4.0)

        // Non-whitelisted system app (Finder / NotificationCenter) sustained for 5s -> ignored
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.notificationcenterui"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 1.0
        ) == nil)
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.notificationcenterui"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 6.0
        ) == nil)

        // Favorite with autoDetectEnabled == false (Chrome) -> ignored
        #expect(policy.evaluate(
            activeOutputBundles: ["com.google.Chrome"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 7.0
        ) == nil)
        #expect(policy.evaluate(
            activeOutputBundles: ["com.google.Chrome"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 10.0
        ) == nil)

        // Whitelisted Music plays briefly for 400ms then stops -> ignored
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 11.0
        ) == nil)
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 11.4
        ) == nil)
        #expect(policy.evaluate(
            activeOutputBundles: [],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 11.5
        ) == nil)
    }

    @Test("Sustained output >= 700ms on whitelisted app triggers switch and rising edge beats older app")
    func sustainedOutputAndRisingEdge() {
        var policy = AudioSourceAutoDetectPolicy(debounceSeconds: 0.7, manualCooldownSeconds: 4.0)

        // Music starts at t=1.0 -> not yet at t=1.5 -> switches at t=1.8
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 1.0
        ) == nil)
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: true,
            now: 1.8
        ) == "com.apple.Music")

        // Music continues outputting; QQMusic starts outputting at t=5.0 -> hot-switches at t=5.8
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music", "com.tencent.QQMusicMac"],
            favorites: favorites,
            currentBundleID: "com.apple.Music",
            globalEnabled: true,
            now: 5.0
        ) == nil)
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music", "com.tencent.QQMusicMac"],
            favorites: favorites,
            currentBundleID: "com.apple.Music",
            globalEnabled: true,
            now: 5.8
        ) == "com.tencent.QQMusicMac")
    }

    @Test("Manual selection cooldown prevents background app from immediately snapping back")
    func manualSwitchCooldownProtectsUserChoice() {
        var policy = AudioSourceAutoDetectPolicy(debounceSeconds: 0.7, manualCooldownSeconds: 4.0)
        policy.recordManualSelection(bundleIdentifier: "com.apple.Music", at: 10.0)

        // Both apps are outputting right after manual switch -> must NOT snap back to QQMusic
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music", "com.tencent.QQMusicMac"],
            favorites: favorites,
            currentBundleID: "com.apple.Music",
            globalEnabled: true,
            now: 10.5
        ) == nil)
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music", "com.tencent.QQMusicMac"],
            favorites: favorites,
            currentBundleID: "com.apple.Music",
            globalEnabled: true,
            now: 12.0
        ) == nil)
    }

    @Test("Global disabled toggle suppresses all auto-detection")
    func globalDisabledSuppressesSwitch() {
        var policy = AudioSourceAutoDetectPolicy(debounceSeconds: 0.5, manualCooldownSeconds: 2.0)
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: false,
            now: 1.0
        ) == nil)
        #expect(policy.evaluate(
            activeOutputBundles: ["com.apple.Music"],
            favorites: favorites,
            currentBundleID: nil,
            globalEnabled: false,
            now: 5.0
        ) == nil)
    }
}
