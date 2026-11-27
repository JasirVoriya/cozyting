import Foundation
import Testing
@testable import CozyTingCoreKit

@Suite("M5 Volume Group Guard, Cold-Start Clamping & Latency Tier Tests")
struct VolumeAndLatencyM5Tests {
    @Test("Group master volume triggers confirmation when any selected speaker crosses safeLimit")
    func groupMasterCrossLineTriggersConfirm() {
        // Master is 40 -> 55 (<= 60), but speaker-B is at 52 -> 67 (crosses 60!)
        let result = VolumeGroupEvaluator.evaluateMasterChange(
            currentMaster: 40,
            targetMaster: 55,
            selectedSpeakerIds: ["192.0.2.10", "192.0.2.11"],
            speakerVolumes: ["192.0.2.10": 35, "192.0.2.11": 52],
            guardEnabled: true,
            safeLimit: 60,
            confirmed: false
        )
        #expect(result == .requireConfirm(currentVolume: 40, targetVolume: 55, safeLimit: 60))

        // Once explicitly confirmed, all speaker targets apply proportionally
        let confirmed = VolumeGroupEvaluator.evaluateMasterChange(
            currentMaster: 40,
            targetMaster: 55,
            selectedSpeakerIds: ["192.0.2.10", "192.0.2.11"],
            speakerVolumes: ["192.0.2.10": 35, "192.0.2.11": 52],
            guardEnabled: true,
            safeLimit: 60,
            confirmed: true
        )
        #expect(confirmed == .allow(
            targetMasterVolume: 55,
            speakerTargets: ["192.0.2.10": 50, "192.0.2.11": 67]
        ))
    }

    @Test("Group master volume allows adjustments already above safeLimit or decreasing")
    func groupMasterAllowsAboveOrDecreasing() {
        let alreadyHigh = VolumeGroupEvaluator.evaluateMasterChange(
            currentMaster: 70,
            targetMaster: 75,
            selectedSpeakerIds: ["192.0.2.10"],
            speakerVolumes: ["192.0.2.10": 72],
            guardEnabled: true,
            safeLimit: 60,
            confirmed: false
        )
        #expect(alreadyHigh == .allow(
            targetMasterVolume: 75,
            speakerTargets: ["192.0.2.10": 77]
        ))

        let decreasing = VolumeGroupEvaluator.evaluateMasterChange(
            currentMaster: 70,
            targetMaster: 50,
            selectedSpeakerIds: ["192.0.2.10"],
            speakerVolumes: ["192.0.2.10": 72],
            guardEnabled: true,
            safeLimit: 60,
            confirmed: false
        )
        #expect(decreasing == .allow(
            targetMasterVolume: 50,
            speakerTargets: ["192.0.2.10": 52]
        ))
    }

    @Test("LatencyPreset maps Wi-Fi frequency and L1 startup runway AUs accurately")
    func latencyPresetAndStartupRunway() {
        #expect(LatencyPreset(delayMs: 0) == .standard)
        #expect(LatencyPreset(delayMs: 400) == .balanced)
        #expect(LatencyPreset(delayMs: 250) == .low)
        #expect(LatencyPreset(delayMs: 150) == .ultra)
        #expect(LatencyPreset(delayMs: 320) == .custom(ms: 320))

        // 5 GHz -> 800ms; 2.4 GHz or unknown 0 -> 1000ms
        #expect(LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: 5180) == 800)
        #expect(LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: 2412) == 1000)
        #expect(LatencyPreset.standard.effectiveDelayMs(wifiFrequencyMHz: 0) == 1000)

        // L1 startup runway scales strictly down across tiers (14 / 8 / 5 / 3 AU)
        let stdAU = LatencyPreset.standard.startupRunwayAUs(wifiFrequencyMHz: 5180)
        let balAU = LatencyPreset.balanced.startupRunwayAUs(wifiFrequencyMHz: 5180)
        let lowAU = LatencyPreset.low.startupRunwayAUs(wifiFrequencyMHz: 5180)
        let ultAU = LatencyPreset.ultra.startupRunwayAUs(wifiFrequencyMHz: 5180)
        #expect(stdAU >= balAU)
        #expect(balAU >= lowAU)
        #expect(lowAU >= ultAU)
        #expect(ultAU >= 1)
    }

    @Test("Cold-start volume restoration clamps both master and speaker map to safeLimit")
    func coldStartClampingParity() {
        let suiteName = "com.cozyting.tests.m5.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let store = PreferencesStore(userDefaults: defaults)
        store.volumeGuardEnabled = true
        store.volumeGuardSafeLimit = 60
        store.saveMasterVolume(85)
        store.saveSpeakerVolume(deviceID: "192.0.2.10", volume: 82)
        store.saveSpeakerVolume(deviceID: "192.0.2.11", volume: 42)

        #expect(store.loadMasterVolumeForColdStart() == 60)
        let restored = store.loadSpeakerVolumesForColdStart()
        #expect(restored["192.0.2.10"] == 60)
        #expect(restored["192.0.2.11"] == 42)

        // When guard is disabled, values restore untouched
        store.volumeGuardEnabled = false
        #expect(store.loadMasterVolumeForColdStart() == 85)
        #expect(store.loadSpeakerVolumesForColdStart()["192.0.2.10"] == 82)
    }
}
