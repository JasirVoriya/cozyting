import Foundation

/// Cross-platform speaker display, ordering, and latency tier formatting helpers (zero `#if os(...)`).
public enum DeviceDisplayFormatter {
    /// Formats speaker title & room name cleanly as `miName · roomName` without duplicating
    /// when `roomName` is empty or already embedded in `name`.
    public static func displayTitle(for device: Device) -> String {
        let name = device.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let room = device.roomName.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = name.isEmpty ? device.id : name
        guard !room.isEmpty else { return base }
        if base.localizedCaseInsensitiveContains(room) {
            return base
        }
        return "\(base) · \(room)"
    }

    /// Formats inline latency badge (`398ms`) or `nil` when not actively streaming / unavailable.
    public static func formattedLatency(for stats: ReceiverStats?) -> String? {
        guard let stats, stats.uiState == .streaming, let ms = stats.displayLatencyMs, ms > 0 else {
            return nil
        }
        return "\(ms)ms"
    }

    /// Orders devices so `thisDevice` is always first, followed by pinned `lastSelectedSpeakerIds`
    /// in their saved order, followed by remaining castable speakers, and finally non-castable entries.
    public static func orderedDevices(
        _ devices: [Device],
        lastSelectedIds: [String],
        showAllDiscovered: Bool
    ) -> [Device] {
        let filtered = devices.filter { device in
            device.kind == .thisDevice || device.castable || device.selected || showAllDiscovered
        }

        let pinnedOrder: [String: Int] = Dictionary(
            uniqueKeysWithValues: lastSelectedIds.enumerated().map { ($0.element, $0.offset) }
        )

        return filtered.sorted { a, b in
            if a.kind == .thisDevice && b.kind != .thisDevice { return true }
            if b.kind == .thisDevice && a.kind != .thisDevice { return false }

            // Actively selected speakers stay at top during casting, then pinned last-selected.
            if a.selected != b.selected { return a.selected }

            let pinA = pinnedOrder[a.id]
            let pinB = pinnedOrder[b.id]
            switch (pinA, pinB) {
            case let (ia?, ib?):
                return ia < ib
            case (_?, nil):
                return true
            case (nil, _?):
                return false
            case (nil, nil):
                break
            }

            if a.castable != b.castable { return a.castable }
            let nameCmp = a.name.localizedCaseInsensitiveCompare(b.name)
            if nameCmp != .orderedSame {
                return nameCmp == .orderedAscending
            }
            return a.id < b.id
        }
    }

    /// Canonical `this` device sentinel expected by L1 `cozyting_route_apply_json`.
    public static func makeThisDevice(displayName: String, selected: Bool) -> Device {
        Device(
            id: "this",
            name: displayName,
            kind: .thisDevice,
            castable: true,
            selected: selected,
            detail: "",
            protocol: nil,
            nameFromDeviceInfo: false,
            roomName: "",
            roomInfoQueried: false
        )
    }
}

public extension LatencyPreset {
    /// Resolves effective millisecond buffer given optional detected Wi-Fi frequency (MHz).
    func effectiveDelayMs(wifiFrequencyMHz: Int32) -> Int {
        switch self {
        case .standard:
            return Int(CozyTingCoreBridge.delayForFrequency(mhz: wifiFrequencyMHz))
        default:
            return delayMs
        }
    }
}
