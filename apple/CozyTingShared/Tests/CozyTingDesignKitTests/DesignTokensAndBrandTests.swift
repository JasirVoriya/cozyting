import CozyTingCoreKit
import Foundation
import SwiftUI
import Testing
@testable import CozyTingDesignKit

@Suite("DesignTokens & BrandStrings Tests")
struct DesignTokensAndBrandTests {
    @Test("Design tokens match design/tokens.json v1.1.0 constants")
    func designTokenConstants() {
        #expect(DesignTokens.version == "1.1.0")
        #expect(DesignTokens.style == "style-b-calm-contrast")
        #expect(DesignTokens.Brand.coralHex == "#E56D48")
        #expect(DesignTokens.Brand.warmGoldHex == "#FFD166")
        #expect(DesignTokens.Radii.panel == 24)
        #expect(DesignTokens.Radii.card == 16)
        #expect(DesignTokens.Radii.control == 12)
        #expect(DesignTokens.Radii.pill == 999)
        #expect(DesignTokens.Spacing.lg == 16)
        #expect(DesignTokens.Typography.deviceTitle == 13)
        #expect(DesignTokens.Typography.deviceRoom == 11.5)
    }

    @Test("BrandStrings decouples brand name and MiPlay protocol across languages")
    func brandAndProtocolLocalization() {
        #expect(BrandStrings.protocolName == "MiPlay")
        #expect(BrandStrings.appDisplayName(for: .zhCN) == "窝爱听")
        #expect(BrandStrings.appDisplayName(for: .en) == "CozyTing")
        #expect(BrandStrings.appDisplayName(for: .ja) == "CozyTing")
        #expect(BrandStrings.appDisplayName(for: .system, locale: Locale(identifier: "zh-Hans-CN")) == "窝爱听")
        #expect(BrandStrings.appDisplayName(for: .system, locale: Locale(identifier: "en-US")) == "CozyTing")
        #expect(BrandStrings.appDisplayName(for: .system, locale: Locale(identifier: "ja-JP")) == "CozyTing")
    }

    @Test("CozyTingIcon catalog covers all 28 semantic icons with non-empty symbol names")
    func iconCatalogCompleteness() {
        #expect(CozyTingIcon.allCases.count == 28)
        for icon in CozyTingIcon.allCases {
            #expect(!icon.rawValue.isEmpty)
            #expect(!icon.sfSymbolName.isEmpty)
        }
    }

    @Test("CozyHouseBrandShape produces non-empty vector path within bounds")
    func cozyHouseBrandShapePath() {
        let shape = CozyHouseBrandShape()
        let path = shape.path(in: CGRect(x: 0, y: 0, width: 24, height: 24))
        #expect(!path.isEmpty)
        #expect(path.boundingRect.width > 12)
        #expect(path.boundingRect.height > 12)
    }

    @Test("PlatformAdapters maps theme modes and renders 18x18 template menu bar icon")
    func platformAdaptersThemeAndMenuBarIcon() {
        #expect(PlatformAdapters.preferredColorScheme(for: .system) == nil)
        #expect(PlatformAdapters.preferredColorScheme(for: .light) == .light)
        #expect(PlatformAdapters.preferredColorScheme(for: .dark) == .dark)

        #if os(macOS)
        let idle = PlatformAdapters.makeMenuBarBrandTemplateImage(active: false)
        let active = PlatformAdapters.makeMenuBarBrandTemplateImage(active: true)
        #expect(idle.isTemplate)
        #expect(active.isTemplate)
        #expect(idle.size == NSSize(width: 18, height: 18))
        #expect(active.size == NSSize(width: 18, height: 18))
        #endif
    }
}
