import Foundation
import Testing
@testable import CozyTingCoreKit

@Suite("PreferencesStore Parity Tests")
struct PreferencesStoreTests {
    private func makeIsolatedStore() -> (PreferencesStore, UserDefaults, String) {
        let suiteName = "com.cozyting.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return (PreferencesStore(userDefaults: defaults), defaults, suiteName)
    }

    @Test("Default settings match Android parity")
    func defaultsMatchParity() {
        let (store, defaults, suite) = makeIsolatedStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        #expect(store.themeMode == .system)
        #expect(store.appLanguage == .system)
        #expect(store.keepLocalOutput == false)
        #expect(store.muteLocalAudio == true)
        #expect(store.autoDetectAudioSource == true)
        #expect(store.volumeGuardEnabled == true)
        #expect(store.volumeGuardSafeLimit == 60)
        #expect(store.showAllDiscovered == false)
        #expect(store.sourceMode == .app)
        #expect(store.delayMs == 0)
        #expect(store.refFunction == "")
        #expect(store.authMode == .auto)
        #expect(store.loadMasterVolume() == 30)
        #expect(store.updateAutoCheck == true)
        #expect(store.resolvedUpdateChannel(currentVersionName: "0.9.12") == .stable)
        #expect(store.resolvedUpdateChannel(currentVersionName: "0.9.13-rc.1-release") == .prerelease)
    }

    @Test("Cold-start master and per-speaker volume clamping respects VolumeGuardPolicy")
    func coldStartVolumeClamping() {
        let (store, defaults, suite) = makeIsolatedStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        store.volumeGuardEnabled = true
        store.volumeGuardSafeLimit = 57 // snaps to 55
        #expect(store.volumeGuardSafeLimit == 55)

        store.saveMasterVolume(85)
        store.saveSpeakerVolume(deviceID: "192.0.2.10", volume: 90)
        store.saveSpeakerVolume(deviceID: "192.0.2.11", volume: 40)

        #expect(store.loadMasterVolumeForColdStart() == 55)
        let coldSpeakers = store.loadSpeakerVolumesForColdStart()
        #expect(coldSpeakers["192.0.2.10"] == 55)
        #expect(coldSpeakers["192.0.2.11"] == 40)

        // When guard disabled, restores raw saved values
        store.volumeGuardEnabled = false
        #expect(store.loadMasterVolumeForColdStart() == 85)
        #expect(store.loadSpeakerVolumesForColdStart()["192.0.2.10"] == 90)
    }

    @Test("Speaker volume LRU caps at 32 entries and refreshes recency on update")
    func speakerVolumeLRUEviction() {
        let (store, defaults, suite) = makeIsolatedStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        for i in 1...35 {
            store.saveSpeakerVolume(deviceID: "192.0.2.\(i)", volume: i)
            if i == 10 {
                // Refresh 192.0.2.1 after 10 items so 2, 3, 4 become the oldest 3 evicted.
                store.saveSpeakerVolume(deviceID: "192.0.2.1", volume: 77)
            }
        }

        let order = store.loadSpeakerVolumeOrder()
        #expect(order.count == 32)
        // Oldest 3 (2, 3, 4) were evicted; 1 was touched after 2 so it survived.
        let ids = Set(order.map(\.id))
        #expect(ids.contains("192.0.2.1"))
        #expect(!ids.contains("192.0.2.2"))
        #expect(!ids.contains("192.0.2.3"))
        #expect(!ids.contains("192.0.2.4"))
        #expect(ids.contains("192.0.2.35"))
    }

    @Test("Cached devices and last selected speaker IDs enforce cap of 16 and exclude 'this'")
    func deviceAndSpeakerListCaps() {
        let (store, defaults, suite) = makeIsolatedStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        var devices: [Device] = [
            Device(id: "this", name: "Mac", kind: .thisDevice, castable: true, selected: true, protocol: nil),
            Device(id: "192.0.2.200", name: "DLNA", kind: .speaker, castable: false, selected: false, protocol: .dlna),
        ]
        for i in 1...20 {
            devices.append(
                Device(
                    id: "192.0.2.\(i)",
                    name: "Speaker \(i)",
                    kind: .speaker,
                    castable: true,
                    selected: true,
                    protocol: .miplay,
                    nameFromDeviceInfo: true,
                    roomName: "Room \(i)",
                    roomInfoQueried: true
                )
            )
        }
        store.saveCachedDevices(devices)
        let cached = store.loadCachedDevices()
        #expect(cached.count == 16)
        #expect(!cached.contains(where: { $0.id == "this" || $0.id == "192.0.2.200" }))
        #expect(cached.allSatisfy { $0.selected == false })

        let speakerIDs = ["this"] + (1...20).map { "192.0.2.\($0)" }
        store.saveLastSelectedSpeakerIds(speakerIDs)
        let savedIDs = store.loadLastSelectedSpeakerIds()
        #expect(savedIDs.count == 16)
        #expect(!savedIDs.contains("this"))
        #expect(savedIDs.first == "192.0.2.1")
    }

    @Test("AudioSourceSelection and FavoriteAudioSource persist bundle IDs without PIDs")
    func audioSourceAndFavoritesPersistence() {
        let (store, defaults, suite) = makeIsolatedStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        let s1 = AudioSourceSelection(bundleIdentifier: "com.spotify.client", displayName: "Spotify")
        let s2 = AudioSourceSelection(bundleIdentifier: "com.apple.Music", displayName: "Music")
        store.saveSelectedAudioSource(s1)
        store.saveSelectedAudioSource(s2)

        #expect(store.loadSelectedAudioSource() == s2)
        #expect(store.loadRecentAudioSources() == [s2, s1])

        store.saveFavoriteAudioSources([
            FavoriteAudioSource(bundleIdentifier: "com.spotify.client", autoDetectEnabled: true),
            FavoriteAudioSource(bundleIdentifier: "com.apple.Music", autoDetectEnabled: false),
        ])
        #expect(store.hasInitializedFavoriteSources == true)
        let reordered = store.reorderFavoriteAudioSources(
            fromBundleIdentifier: "com.apple.Music",
            toBundleIdentifier: "com.spotify.client"
        )
        #expect(reordered.map(\.bundleIdentifier) == ["com.apple.Music", "com.spotify.client"])
        let toggled = store.setFavoriteAudioSourceAutoDetect(
            bundleIdentifier: "com.apple.Music",
            enabled: true
        )
        #expect(toggled.first?.autoDetectEnabled == true)
    }

    @Test("loadCachedDevices skips unknown CastProtocol entries and falls back empty name to id")
    func loadCachedDevicesFaultToleranceAndEmptyNameFallback() {
        let (store, defaults, suite) = makeIsolatedStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        let rawJSON = """
        [
          {"id":"192.0.2.10","name":"","kind":"SPEAKER","castable":true,"selected":false,"protocol":"MIPLAY"},
          {"id":"192.0.2.11","name":"Future Matter","kind":"SPEAKER","castable":true,"selected":false,"protocol":"MATTER"},
          {"id":"192.0.2.12","name":"   ","kind":"TV","castable":true,"selected":false,"protocol":"MIPLAY"}
        ]
        """
        defaults.set(rawJSON, forKey: PreferencesStore.Keys.devicesJSON)

        let loaded = store.loadCachedDevices()
        #expect(loaded.count == 2)
        #expect(loaded[0].id == "192.0.2.10")
        #expect(loaded[0].name == "192.0.2.10")
        #expect(loaded[1].id == "192.0.2.12")
        #expect(loaded[1].name == "192.0.2.12")
        #expect(loaded[1].kind == .tv)
    }
}
