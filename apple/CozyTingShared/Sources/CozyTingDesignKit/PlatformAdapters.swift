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
}
#endif
