import SwiftUI

/// Semantic icon catalog aligned 1:1 with `design/icons/*.svg` and the F2 monochrome brand mark.
public enum CozyTingIcon: String, CaseIterable, Sendable {
    case cozyHouse = "cozy_house"
    case speaker = "speaker"
    case tv = "tv"
    case phone = "phone"
    case musicNote = "music_note"
    case volume = "volume"
    case play = "play"
    case pause = "pause"
    case skipPrev = "skip_prev"
    case skipNext = "skip_next"
    case refresh = "refresh"
    case settings = "settings"
    case search = "search"
    case check = "check"
    case close = "close"
    case back = "back"
    case chevronRight = "chevron_right"
    case chevronDown = "chevron_down"
    case dragHandle = "drag_handle"
    case info = "info"
    case sun = "sun"
    case moon = "moon"
    case update = "update"
    case latencyStandard = "latency_standard"
    case latencyBalanced = "latency_balanced"
    case latencyLow = "latency_low"
    case latencyUltra = "latency_ultra"
    case latencyCustom = "latency_custom"

    /// System symbol name used for crisp template rendering matching the 24pt 2px stroke aesthetic.
    public var sfSymbolName: String {
        switch self {
        case .cozyHouse: return "house.fill"
        case .speaker: return "hifispeaker"
        case .tv: return "tv"
        case .phone: return "laptopcomputer"
        case .musicNote: return "music.note"
        case .volume: return "speaker.wave.2"
        case .play: return "play.fill"
        case .pause: return "pause.fill"
        case .skipPrev: return "backward.end.fill"
        case .skipNext: return "forward.end.fill"
        case .refresh: return "arrow.clockwise"
        case .settings: return "gearshape"
        case .search: return "magnifyingglass"
        case .check: return "checkmark"
        case .close: return "xmark"
        case .back: return "chevron.left"
        case .chevronRight: return "chevron.right"
        case .chevronDown: return "chevron.down"
        case .dragHandle: return "line.3.horizontal"
        case .info: return "info.circle"
        case .sun: return "sun.max"
        case .moon: return "moon"
        case .update: return "arrow.down.circle"
        case .latencyStandard: return "antenna.radiowaves.left.and.right"
        case .latencyBalanced: return "gauge.with.dots.needle.50percent"
        case .latencyLow: return "bolt"
        case .latencyUltra: return "flame"
        case .latencyCustom: return "slider.horizontal.3"
        }
    }
}

/// Pure SwiftUI vector Shape reproducing the F2 CozyTing brand mark:
/// midnight lit house + arched window cutout (even-odd) + hanging-tail listening cat + moon & note.
public struct CozyHouseBrandShape: Shape {
    public init() {}

    public func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 24.0
        let ox = rect.midX - 12.0 * s
        let oy = rect.midY - 12.0 * s

        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: ox + x * s, y: oy + y * s)
        }

        var path = Path()

        // 1. Cozy house silhouette with pitched roof & rounded base
        path.move(to: pt(12, 4.2))
        path.addLine(to: pt(20.6, 10.6))
        path.addLine(to: pt(19.0, 10.6))
        path.addLine(to: pt(19.0, 19.2))
        path.addQuadCurve(to: pt(16.6, 21.4), control: pt(19.0, 21.4))
        path.addLine(to: pt(7.4, 21.4))
        path.addQuadCurve(to: pt(5.0, 19.2), control: pt(5.0, 21.4))
        path.addLine(to: pt(5.0, 10.6))
        path.addLine(to: pt(3.4, 10.6))
        path.closeSubpath()

        // 2. Arched window cutout (even-odd hole)
        path.move(to: pt(8.4, 14.5))
        path.addCurve(to: pt(15.6, 14.5), control1: pt(8.4, 10.4), control2: pt(15.6, 10.4))
        path.addLine(to: pt(15.6, 18.6))
        path.addLine(to: pt(8.4, 18.6))
        path.closeSubpath()

        // 3. Cat silhouette seated inside window with ears + curved hanging tail
        path.move(to: pt(9.8, 18.6))
        path.addLine(to: pt(9.8, 15.8))
        path.addLine(to: pt(10.3, 14.2))
        path.addLine(to: pt(11.3, 15.1))
        path.addLine(to: pt(12.7, 15.1))
        path.addLine(to: pt(13.7, 14.2))
        path.addLine(to: pt(14.2, 15.8))
        path.addLine(to: pt(14.2, 18.6))
        path.closeSubpath()

        // 4. Crescent moon (top-left)
        path.addEllipse(in: CGRect(x: ox + 3.2 * s, y: oy + 2.4 * s, width: 3.4 * s, height: 3.4 * s))

        // 5. Floating music note (top-right)
        path.addRoundedRect(
            in: CGRect(x: ox + 17.6 * s, y: oy + 2.6 * s, width: 1.5 * s, height: 4.4 * s),
            cornerSize: CGSize(width: 0.7 * s, height: 0.7 * s)
        )
        path.addEllipse(in: CGRect(x: ox + 15.8 * s, y: oy + 5.6 * s, width: 3.0 * s, height: 2.2 * s))

        return path
    }
}

/// Unified icon view supporting both custom F2 vector brand mark and template linear icons.
public struct CozyTingIconView: View {
    public let icon: CozyTingIcon
    public let size: CGFloat
    public let weight: Font.Weight

    public init(_ icon: CozyTingIcon, size: CGFloat = 16, weight: Font.Weight = .medium) {
        self.icon = icon
        self.size = size
        self.weight = weight
    }

    public var body: some View {
        if icon == .cozyHouse {
            CozyHouseBrandShape()
                .fill(style: FillStyle(eoFill: true))
                .frame(width: size, height: size)
        } else {
            Image(systemName: icon.sfSymbolName)
                .font(.system(size: size, weight: weight))
                .frame(width: size, height: size)
        }
    }
}
