import AppKit
import SwiftUI

/// Authentic macOS behind-window frosted glass (`NSVisualEffectView`) bridge for SwiftUI.
public struct VisualEffectBackground: NSViewRepresentable {
    public let material: NSVisualEffectView.Material
    public let blendingMode: NSVisualEffectView.BlendingMode
    public let state: NSVisualEffectView.State
    public let isEmphasized: Bool

    public init(
        material: NSVisualEffectView.Material = .underWindowBackground,
        blendingMode: NSVisualEffectView.BlendingMode = .behindWindow,
        state: NSVisualEffectView.State = .followsWindowActiveState,
        isEmphasized: Bool = false
    ) {
        self.material = material
        self.blendingMode = blendingMode
        self.state = state
        self.isEmphasized = isEmphasized
    }

    public func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = blendingMode
        view.state = state
        view.isEmphasized = isEmphasized
        return view
    }

    public func updateNSView(_ nsView: NSVisualEffectView, context: Context) {
        nsView.material = material
        nsView.blendingMode = blendingMode
        nsView.state = state
        nsView.isEmphasized = isEmphasized
    }
}

/// Sleek borderless circular macOS Control Center glass icon button with smooth hover & press feedback.
public struct GlassCircleIconButton<Content: View>: View {
    public let size: CGFloat
    public let prominent: Bool
    public let action: () -> Void
    @ViewBuilder public let content: () -> Content

    @State private var isHovered: Bool = false

    public init(
        size: CGFloat = 28,
        prominent: Bool = false,
        action: @escaping () -> Void,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.size = size
        self.prominent = prominent
        self.action = action
        self.content = content
    }

    public var body: some View {
        Button(action: action) {
            content()
                .frame(width: size, height: size)
                .background(
                    Circle().fill(
                        prominent
                            ? Color.primary.opacity(isHovered ? 0.22 : 0.14)
                            : Color.primary.opacity(isHovered ? 0.13 : 0.0)
                    )
                )
                .overlay(
                    Circle().strokeBorder(
                        prominent
                            ? Color.primary.opacity(isHovered ? 0.28 : 0.18)
                            : Color.primary.opacity(isHovered ? 0.14 : 0.0),
                        lineWidth: 0.5
                    )
                )
                .contentShape(Circle())
        }
        .buttonStyle(GlassPressButtonStyle())
        .focusEffectDisabled()
        .onHover { hovering in
            withAnimation(.easeOut(duration: 0.12)) {
                isHovered = hovering
            }
        }
    }
}

/// Sleek translucent glass capsule button for compact macOS HUD / Control Center secondary actions.
public struct GlassPillButton<Content: View>: View {
    public let action: () -> Void
    @ViewBuilder public let content: () -> Content

    @State private var isHovered: Bool = false

    public init(
        action: @escaping () -> Void,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.action = action
        self.content = content
    }

    public var body: some View {
        Button(action: action) {
            content()
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(
                    Capsule().fill(Color.primary.opacity(isHovered ? 0.14 : 0.08))
                )
                .overlay(
                    Capsule().strokeBorder(Color.primary.opacity(isHovered ? 0.20 : 0.11), lineWidth: 0.5)
                )
                .contentShape(Capsule())
        }
        .buttonStyle(GlassPressButtonStyle())
        .focusEffectDisabled()
        .onHover { hovering in
            withAnimation(.easeOut(duration: 0.12)) {
                isHovered = hovering
            }
        }
    }
}

/// Subtle spring press-down feel matching native macOS Control Center controls.
public struct GlassPressButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.93 : 1.0)
            .opacity(configuration.isPressed ? 0.82 : 1.0)
            .animation(.easeOut(duration: 0.10), value: configuration.isPressed)
    }
}

/// Authentic macOS Control Center thick glass capsule volume slider (click or drag to adjust).
public struct ControlCenterVolumeBar: View {
    public let value: Int
    public let safeLimit: Int
    public let guardEnabled: Bool
    public let height: CGFloat
    public let onCommit: (Int) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var dragValue: Double?
    @State private var isHovered: Bool = false

    public init(
        value: Int,
        safeLimit: Int,
        guardEnabled: Bool,
        height: CGFloat = 22,
        onCommit: @escaping (Int) -> Void
    ) {
        self.value = value
        self.safeLimit = safeLimit
        self.guardEnabled = guardEnabled
        self.height = height
        self.onCommit = onCommit
    }

    private var displayed: Double {
        min(max(dragValue ?? Double(value), 0), 100)
    }

    private var isAboveSafeLimit: Bool {
        guardEnabled && Int(displayed.rounded()) > safeLimit
    }

    private var speakerSymbol: String {
        let v = Int(displayed.rounded())
        if v <= 0 { return "speaker.slash.fill" }
        if v < 34 { return "speaker.wave.1.fill" }
        if v < 67 { return "speaker.wave.2.fill" }
        return "speaker.wave.3.fill"
    }

    public var body: some View {
        HStack(spacing: 8) {
            GeometryReader { geo in
                let trackWidth = max(geo.size.width, height)
                let fraction = displayed / 100.0
                let fillWidth = height + (trackWidth - height) * fraction

                ZStack(alignment: .leading) {
                    // Recessed frosted glass capsule track
                    Capsule()
                        .fill(
                            colorScheme == .dark
                                ? Color.white.opacity(isHovered ? 0.16 : 0.12)
                                : Color.black.opacity(isHovered ? 0.11 : 0.08)
                        )
                        .overlay(
                            Capsule()
                                .strokeBorder(Color.primary.opacity(0.11), lineWidth: 0.5)
                        )

                    // Bright Apple Control Center fill pill
                    Capsule()
                        .fill(
                            isAboveSafeLimit
                                ? Color.orange.opacity(0.92)
                                : (colorScheme == .dark ? Color.white.opacity(0.90) : Color.white.opacity(0.96))
                        )
                        .frame(width: fillWidth, height: height)
                        .shadow(
                            color: Color.black.opacity(colorScheme == .dark ? 0.18 : 0.14),
                            radius: 2,
                            x: 0,
                            y: 0.5
                        )

                    // High-contrast speaker glyph anchored cleanly inside leading circle
                    Image(systemName: speakerSymbol)
                        .font(.system(size: height * 0.46, weight: .semibold))
                        .foregroundStyle(isAboveSafeLimit ? Color.white : Color.black.opacity(0.62))
                        .frame(width: height, height: height)
                }
                .contentShape(Capsule())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { gesture in
                            let usable = max(trackWidth - height, 1)
                            let raw = (gesture.location.x - height * 0.5) / usable
                            dragValue = min(max(raw, 0), 1) * 100.0
                        }
                        .onEnded { gesture in
                            let usable = max(trackWidth - height, 1)
                            let raw = (gesture.location.x - height * 0.5) / usable
                            let finalVal = Int((min(max(raw, 0), 1) * 100.0).rounded())
                            dragValue = nil
                            onCommit(finalVal)
                        }
                )
            }
            .frame(height: height)
            .onHover { hovering in
                withAnimation(.easeOut(duration: 0.12)) {
                    isHovered = hovering
                }
            }

            Text("\(Int(displayed.rounded()))%")
                .font(.caption.monospacedDigit().weight(.medium))
                .foregroundStyle(isAboveSafeLimit ? .orange : .secondary)
                .frame(width: 34, alignment: .trailing)
        }
    }
}
