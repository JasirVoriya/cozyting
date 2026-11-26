import Foundation

/// Pure state machine governing automatic audio-source detection & hot-switching (`M6`).
///
/// Design invariants (`docs/MACOS_CLIENT_DESIGN.md` §5.2):
/// 1. Only apps listed in `favorites` with `autoDetectEnabled == true` are ever eligible.
/// 2. Short system chimes / notification sounds are filtered by requiring sustained output
///    for at least `debounceSeconds` (default `0.7s`).
/// 3. After a manual source switch, the previously active app is suppressed during
///    `manualCooldownSeconds` (default `4.0s`) so background playback cannot snap back.
/// 4. Rising edges (a newly outputting whitelisted app) take priority over already-playing apps.
public struct AudioSourceAutoDetectPolicy: Sendable {
    public let debounceSeconds: TimeInterval
    public let manualCooldownSeconds: TimeInterval

    private var candidateStartTimes: [String: TimeInterval] = [:]
    private var previousOutputting: Set<String> = []
    private var lastManualBundleID: String?
    private var lastManualTimestamp: TimeInterval = -.infinity

    public init(
        debounceSeconds: TimeInterval = 0.7,
        manualCooldownSeconds: TimeInterval = 4.0
    ) {
        self.debounceSeconds = max(debounceSeconds, 0)
        self.manualCooldownSeconds = max(manualCooldownSeconds, 0)
    }

    /// Records a manual user selection so stale background output won't immediately revert it.
    public mutating func recordManualSelection(bundleIdentifier: String, at now: TimeInterval) {
        lastManualBundleID = bundleIdentifier.lowercased()
        lastManualTimestamp = now
        candidateStartTimes.removeAll()
    }

    /// Resets transient debounce state (e.g. when global auto-detect is turned off).
    public mutating func reset() {
        candidateStartTimes.removeAll()
        previousOutputting.removeAll()
    }

    /// Evaluates the current set of outputting bundle IDs and returns a bundle ID to switch to, if any.
    public mutating func evaluate(
        activeOutputBundles: Set<String>,
        favorites: [FavoriteAudioSource],
        currentBundleID: String?,
        globalEnabled: Bool,
        now: TimeInterval
    ) -> String? {
        guard globalEnabled else {
            reset()
            return nil
        }

        let normalizedActive = Set(activeOutputBundles.map { $0.lowercased() })
        let normalizedCurrent = currentBundleID?.lowercased()

        // Allowed whitelist preserving user-defined favorite order.
        let allowedOrdered: [(normalized: String, original: String)] = favorites
            .filter(\.autoDetectEnabled)
            .map { ($0.bundleIdentifier.lowercased(), $0.bundleIdentifier) }
        let allowedMap: [String: String] = Dictionary(
            uniqueKeysWithValues: allowedOrdered.map { ($0.normalized, $0.original) }
        )

        // Drop candidates that stopped outputting or left the whitelist.
        candidateStartTimes = candidateStartTimes.filter { key, _ in
            normalizedActive.contains(key) && allowedMap[key] != nil
        }

        // Track start timestamps for currently outputting whitelisted apps.
        for (norm, _) in allowedOrdered where normalizedActive.contains(norm) {
            if candidateStartTimes[norm] == nil {
                candidateStartTimes[norm] = now
            }
        }

        let risingEdges = normalizedActive.subtracting(previousOutputting)
        previousOutputting = normalizedActive

        // During manual cooldown, ignore other apps unless a brand-new rising edge occurs after cooldown
        // or the current manual app stopped outputting completely.
        let inManualCooldown = (now - lastManualTimestamp) < manualCooldownSeconds
        if inManualCooldown, let manualID = lastManualBundleID, normalizedCurrent == manualID {
            // Only allow switching away during cooldown if the manual app is NOT outputting
            // AND another app actually started a fresh rising edge.
            if normalizedActive.contains(manualID) || risingEdges.isEmpty {
                return nil
            }
        }

        // If current source is already actively outputting, only consider another whitelisted app
        // that started outputting *more recently* (newer candidate timestamp).
        let currentStart = normalizedCurrent.flatMap { candidateStartTimes[$0] }
        let currentIsOutputting = normalizedCurrent.map { normalizedActive.contains($0) } ?? false

        var eligible: [(norm: String, original: String, startedAt: TimeInterval, priorityIndex: Int)] = []
        for (idx, entry) in allowedOrdered.enumerated() {
            guard entry.normalized != normalizedCurrent,
                  let startedAt = candidateStartTimes[entry.normalized] else {
                continue
            }
            guard (now - startedAt) >= debounceSeconds else {
                continue
            }
            if currentIsOutputting, let curStart = currentStart, startedAt <= curStart {
                // Old background app that was already running before current app; do not snap back.
                continue
            }
            eligible.append((entry.normalized, entry.original, startedAt, idx))
        }

        guard let best = eligible.sorted(by: { lhs, rhs in
            if lhs.startedAt != rhs.startedAt {
                // Most recently started outputting app wins (rising-edge semantics).
                return lhs.startedAt > rhs.startedAt
            }
            return lhs.priorityIndex < rhs.priorityIndex
        }).first else {
            return nil
        }

        // Preserve `best.norm`'s start timestamp while it continues outputting so any *later*
        // rising-edge app cleanly has `startedAt > currentStart`.
        return best.original
    }
}
