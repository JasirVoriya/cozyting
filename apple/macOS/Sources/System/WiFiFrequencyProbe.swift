import CoreWLAN
import Foundation

/// Probes the active Wi-Fi interface channel band via `CoreWLAN` to inform L1 `delayForFrequency`.
///
/// Privacy & Permission Boundary (`docs/MACOS_CLIENT_DESIGN.md` §5.3):
/// - Never requests Location Services or triggers permission prompts.
/// - If `wlanChannel()` is nil (Ethernet, Wi-Fi off, or Location permission denied), returns `0`,
///   allowing L1 `cozyting_delay_for_frequency(0)` to safely select the conservative `1000ms` baseline.
public enum WiFiFrequencyProbe {
    public static func currentFrequencyMHz() -> Int32 {
        guard let interface = CWWiFiClient.shared().interface(),
              let channel = interface.wlanChannel() else {
            return 0
        }
        switch channel.channelBand {
        case .band5GHz:
            return 5180
        case .band6GHz:
            return 5955
        case .band2GHz:
            return 2412
        case .bandUnknown:
            return 0
        @unknown default:
            return 0
        }
    }
}
