import Foundation

/// Outcome of evaluating a group master volume change across active speakers via L1 `evaluateVolumeGuard`.
public enum GroupVolumeGuardResult: Sendable, Equatable {
    case allow(targetMasterVolume: Int, speakerTargets: [String: Int])
    case requireConfirm(currentVolume: Int, targetVolume: Int, safeLimit: Int)
}

/// Cross-platform volume group evaluator & latency runway helper (zero UI imports).
///
/// Mirrors `design/UI_SPEC.md` §2.2:
/// When raising master volume across multiple active speakers, if **either** the master slider
/// **or any** affected speaker crosses from `<= safeLimit` to `> safeLimit`, the change is
/// intercepted for explicit user confirmation before dispatching to L1.
public enum VolumeGroupEvaluator {
    public static func evaluateMasterChange(
        currentMaster: Int,
        targetMaster: Int,
        selectedSpeakerIds: [String],
        speakerVolumes: [String: Int],
        guardEnabled: Bool,
        safeLimit: Int,
        confirmed: Bool
    ) -> GroupVolumeGuardResult {
        let clampedMaster = min(max(targetMaster, 0), 100)
        let delta = clampedMaster - currentMaster

        var nextSpeakerVolumes: [String: Int] = [:]
        for ip in selectedSpeakerIds {
            let cur = speakerVolumes[ip] ?? currentMaster
            nextSpeakerVolumes[ip] = min(max(cur + delta, 0), 100)
        }

        if confirmed || !guardEnabled {
            return .allow(targetMasterVolume: clampedMaster, speakerTargets: nextSpeakerVolumes)
        }

        // 1. Check master slider itself against L1 policy.
        if let decision = try? CozyTingCoreBridge.evaluateVolumeGuard(
            enabled: guardEnabled,
            safeLimit: Int32(safeLimit),
            currentVolume: Int32(currentMaster),
            targetVolume: Int32(clampedMaster)
        ), case let .requireConfirm(curr, target, limit) = decision {
            return .requireConfirm(currentVolume: curr, targetVolume: target, safeLimit: limit)
        }

        // 2. Check every selected speaker — any speaker crossing safeLimit triggers confirmation.
        for ip in selectedSpeakerIds {
            let cur = speakerVolumes[ip] ?? currentMaster
            let next = nextSpeakerVolumes[ip] ?? clampedMaster
            if let decision = try? CozyTingCoreBridge.evaluateVolumeGuard(
                enabled: guardEnabled,
                safeLimit: Int32(safeLimit),
                currentVolume: Int32(cur),
                targetVolume: Int32(next)
            ), case let .requireConfirm(_, _, limit) = decision {
                return .requireConfirm(
                    currentVolume: currentMaster,
                    targetVolume: clampedMaster,
                    safeLimit: limit
                )
            }
        }

        return .allow(targetMasterVolume: clampedMaster, speakerTargets: nextSpeakerVolumes)
    }
}

public extension LatencyPreset {
    /// Returns the L1 startup prefill runway in AAC Access Units (`14 / 8 / 5 / 3 AU`).
    func startupRunwayAUs(wifiFrequencyMHz: Int32) -> Int {
        let effective = effectiveDelayMs(wifiFrequencyMHz: wifiFrequencyMHz)
        return Int(CozyTingCoreBridge.startupRunwayAUs(delayMs: Int32(effective)))
    }
}
