import SwiftUI

// MARK: - Design Tokens (Single Source of Truth: design/tokens.json v1.1.0)

public enum DesignTokens {
    public static let version = "1.1.0"
    public static let style = "style-b-calm-contrast"

    public enum Brand {
        public static let coralHex = "#E56D48"
        public static let coralDarkTextHex = "#C54E2A"
        public static let coralSoftBgHex = "#FFF2EB"
        public static let warmGoldHex = "#FFD166"
        public static let moonLightHex = "#F2B84B"
        public static let moonDarkHex = "#FFD269"
        public static let amberSillHex = "#EE9B48"
        public static let tileLightHex = "#FDF4E6"
        public static let tileDarkHex = "#1B1F2B"
        public static let wallLightHex = "#5E5665"
        public static let wallDarkHex = "#474D5F"
        public static let catInkHex = "#20232F"
        public static let catNoseHex = "#EF8B65"

        public static let coral = Color(tokenHex: coralHex)
        public static let coralDarkText = Color(tokenHex: coralDarkTextHex)
        public static let coralSoftBg = Color(tokenHex: coralSoftBgHex)
        public static let warmGold = Color(tokenHex: warmGoldHex)
        public static let moonLight = Color(tokenHex: moonLightHex)
        public static let moonDark = Color(tokenHex: moonDarkHex)
        public static let amberSill = Color(tokenHex: amberSillHex)
        public static let tileLight = Color(tokenHex: tileLightHex)
        public static let tileDark = Color(tokenHex: tileDarkHex)
        public static let wallLight = Color(tokenHex: wallLightHex)
        public static let wallDark = Color(tokenHex: wallDarkHex)
        public static let catInk = Color(tokenHex: catInkHex)
        public static let catNose = Color(tokenHex: catNoseHex)
    }

    public enum Radii {
        public static let panel: CGFloat = 24
        public static let card: CGFloat = 16
        public static let control: CGFloat = 12
        public static let pill: CGFloat = 999
    }

    public enum Spacing {
        public static let xs: CGFloat = 4
        public static let sm: CGFloat = 8
        public static let md: CGFloat = 12
        public static let lg: CGFloat = 16
        public static let xl: CGFloat = 20
    }

    public enum Typography {
        public static let pageTitle: CGFloat = 18
        public static let sectionTitle: CGFloat = 12
        public static let cardTitle: CGFloat = 15
        public static let deviceTitle: CGFloat = 13
        public static let deviceRoom: CGFloat = 11.5
        public static let body: CGFloat = 14
        public static let caption: CGFloat = 12
        public static let micro: CGFloat = 11
    }

    public struct ThemePalette: Sendable {
        public let bgCanvas: Color
        public let bgAmbientTop: Color
        public let bgAmbientBottom: Color
        public let artworkScrim: Color
        public let panelBg: Color
        public let panelBorder: Color
        public let rowIdleBg: Color
        public let rowIdleBorder: Color
        public let rowSelectedBg: Color
        public let rowSelectedBorder: Color
        public let onSelectedPrimary: Color
        public let onSelectedSecondary: Color
        public let textPrimary: Color
        public let textSecondary: Color
        public let textTertiary: Color
        public let accent: Color
        public let accentBright: Color
        public let accentSoftBg: Color
        public let selectedPillBg: Color
        public let selectedPillText: Color
        public let controlSurface: Color
        public let trackIdle: Color
        public let trackOnSelected: Color
        public let ripple: Color
        public let rippleOnSelected: Color
        public let statusSuccess: Color
        public let statusWarning: Color
        public let statusError: Color

        public static let dark = ThemePalette(
            bgCanvas: Color(tokenHex: "#0E1117"),
            bgAmbientTop: Color(tokenHex: "#172030"),
            bgAmbientBottom: Color(tokenHex: "#0B0E14"),
            artworkScrim: Color(rgbaRed: 11, green: 15, blue: 23, alpha: 0.58),
            panelBg: Color(rgbaRed: 21, green: 26, blue: 35, alpha: 0.90),
            panelBorder: Color(rgbaRed: 255, green: 255, blue: 255, alpha: 0.10),
            rowIdleBg: Color(rgbaRed: 255, green: 255, blue: 255, alpha: 0.06),
            rowIdleBorder: Color(rgbaRed: 255, green: 255, blue: 255, alpha: 0.08),
            rowSelectedBg: Color(tokenHex: "#EAF0F7"),
            rowSelectedBorder: Color(tokenHex: "#EAF0F7"),
            onSelectedPrimary: Color(tokenHex: "#12161E"),
            onSelectedSecondary: Color(tokenHex: "#4B5563"),
            textPrimary: Color(tokenHex: "#F5F7FA"),
            textSecondary: Color(tokenHex: "#94A3B8"),
            textTertiary: Color(tokenHex: "#64748B"),
            accent: Color(tokenHex: "#2563EB"),
            accentBright: Color(tokenHex: "#4C8DFF"),
            accentSoftBg: Color(rgbaRed: 76, green: 141, blue: 255, alpha: 0.18),
            selectedPillBg: Color(rgbaRed: 37, green: 99, blue: 235, alpha: 0.14),
            selectedPillText: Color(tokenHex: "#1D4ED8"),
            controlSurface: Color(rgbaRed: 255, green: 255, blue: 255, alpha: 0.09),
            trackIdle: Color(rgbaRed: 255, green: 255, blue: 255, alpha: 0.18),
            trackOnSelected: Color(rgbaRed: 18, green: 22, blue: 30, alpha: 0.16),
            ripple: Color(rgbaRed: 255, green: 255, blue: 255, alpha: 0.14),
            rippleOnSelected: Color(rgbaRed: 18, green: 22, blue: 30, alpha: 0.10),
            statusSuccess: Color(tokenHex: "#34D399"),
            statusWarning: Color(tokenHex: "#FBBF24"),
            statusError: Color(tokenHex: "#F87171")
        )

        public static let light = ThemePalette(
            bgCanvas: Color(tokenHex: "#F4F6FA"),
            bgAmbientTop: Color(tokenHex: "#E4ECF7"),
            bgAmbientBottom: Color(tokenHex: "#F4F6FA"),
            artworkScrim: Color(rgbaRed: 244, green: 246, blue: 250, alpha: 0.80),
            panelBg: Color(rgbaRed: 255, green: 255, blue: 255, alpha: 0.94),
            panelBorder: Color(rgbaRed: 15, green: 23, blue: 42, alpha: 0.08),
            rowIdleBg: Color(tokenHex: "#FFFFFF"),
            rowIdleBorder: Color(rgbaRed: 15, green: 23, blue: 42, alpha: 0.07),
            rowSelectedBg: Color(tokenHex: "#EBF3FF"),
            rowSelectedBorder: Color(tokenHex: "#2563EB"),
            onSelectedPrimary: Color(tokenHex: "#0F172A"),
            onSelectedSecondary: Color(tokenHex: "#3B5998"),
            textPrimary: Color(tokenHex: "#0F172A"),
            textSecondary: Color(tokenHex: "#475569"),
            textTertiary: Color(tokenHex: "#94A3B8"),
            accent: Color(tokenHex: "#2563EB"),
            accentBright: Color(tokenHex: "#2563EB"),
            accentSoftBg: Color(rgbaRed: 37, green: 99, blue: 235, alpha: 0.11),
            selectedPillBg: Color(rgbaRed: 37, green: 99, blue: 235, alpha: 0.12),
            selectedPillText: Color(tokenHex: "#1D4ED8"),
            controlSurface: Color(rgbaRed: 15, green: 23, blue: 42, alpha: 0.06),
            trackIdle: Color(rgbaRed: 15, green: 23, blue: 42, alpha: 0.12),
            trackOnSelected: Color(rgbaRed: 37, green: 99, blue: 235, alpha: 0.22),
            ripple: Color(rgbaRed: 15, green: 23, blue: 42, alpha: 0.08),
            rippleOnSelected: Color(rgbaRed: 37, green: 99, blue: 235, alpha: 0.12),
            statusSuccess: Color(tokenHex: "#16A34A"),
            statusWarning: Color(tokenHex: "#D97706"),
            statusError: Color(tokenHex: "#DC2626")
        )

        public static func palette(for colorScheme: ColorScheme) -> ThemePalette {
            colorScheme == .dark ? .dark : .light
        }
    }
}

public extension Color {
    init(tokenHex hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var value: UInt64 = 0
        Scanner(string: cleaned).scanHexInt64(&value)
        let r = Double((value >> 16) & 0xFF) / 255.0
        let g = Double((value >> 8) & 0xFF) / 255.0
        let b = Double(value & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: 1.0)
    }

    init(rgbaRed red: Int, green: Int, blue: Int, alpha: Double) {
        self.init(
            .sRGB,
            red: Double(red) / 255.0,
            green: Double(green) / 255.0,
            blue: Double(blue) / 255.0,
            opacity: alpha
        )
    }
}
