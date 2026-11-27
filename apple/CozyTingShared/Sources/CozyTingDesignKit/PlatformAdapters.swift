import CozyTingCoreKit
import SwiftUI

#if os(macOS)
import AppKit

public typealias PlatformColor = NSColor
public typealias PlatformImage = NSImage

public enum PlatformAdapters {
    public static func copyToPasteboard(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }

    public static func templateImage(from image: NSImage) -> Image {
        image.isTemplate = true
        return Image(nsImage: image)
    }

    public static func preferredColorScheme(for themeMode: ThemeMode) -> ColorScheme? {
        switch themeMode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    /// Renders the F2 monochrome brand mark as a crisp macOS menu bar Template `NSImage` (18×18 pt).
    public static func makeMenuBarBrandTemplateImage(active: Bool) -> NSImage {
        let size = NSSize(width: 18, height: 18)
        let image = NSImage(size: size, flipped: true) { rect in
            let cgPath = CozyHouseBrandShape().path(in: rect).cgPath
            guard let ctx = NSGraphicsContext.current?.cgContext else { return false }
            ctx.saveGState()
            ctx.setFillColor(NSColor.black.cgColor)
            ctx.addPath(cgPath)
            ctx.fillPath(using: .evenOdd)

            if active {
                // Subtle solid status dot in bottom-right corner when actively casting.
                let dotRect = CGRect(x: rect.maxX - 4.5, y: rect.maxY - 4.5, width: 4.0, height: 4.0)
                ctx.fillEllipse(in: dotRect)
            }
            ctx.restoreGState()
            return true
        }
        image.isTemplate = true
        return image
    }
}
#elseif os(iOS)
import UIKit

public typealias PlatformColor = UIColor
public typealias PlatformImage = UIImage

public enum PlatformAdapters {
    public static func copyToPasteboard(_ text: String) {
        UIPasteboard.general.string = text
    }

    public static func templateImage(from image: UIImage) -> Image {
        Image(uiImage: image.withRenderingMode(.alwaysTemplate))
    }

    public static func preferredColorScheme(for themeMode: ThemeMode) -> ColorScheme? {
        switch themeMode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}
#endif
