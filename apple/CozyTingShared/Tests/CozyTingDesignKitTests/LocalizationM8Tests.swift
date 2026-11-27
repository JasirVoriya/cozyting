import CozyTingCoreKit
import Foundation
import Testing
@testable import CozyTingDesignKit

@Suite("M8 Trilingual i18n & Brand Decoupling Tests")
struct LocalizationM8Tests {
    @Test("Every L10nKey has non-empty zh-CN, en, and ja translations")
    func completeTrilingualCoverage() {
        #expect(!L10nKey.allCases.isEmpty)
        for key in L10nKey.allCases {
            let zh = CozyTingL10n.zhCN[key] ?? ""
            let en = CozyTingL10n.en[key] ?? ""
            let ja = CozyTingL10n.ja[key] ?? ""
            #expect(!zh.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "Missing zh-CN for \(key.rawValue)")
            #expect(!en.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "Missing en for \(key.rawValue)")
            #expect(!ja.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "Missing ja for \(key.rawValue)")
        }
    }

    @Test("System locale resolution maps zh / en / ja accurately and decouples brand vs MiPlay")
    func localeResolutionAndBrandDecoupling() {
        #expect(CozyTingL10n.resolve(.zhCN) == .zhCN)
        #expect(CozyTingL10n.resolve(.en) == .en)
        #expect(CozyTingL10n.resolve(.ja) == .ja)
        #expect(CozyTingL10n.resolve(.system, systemLocale: Locale(identifier: "zh-Hans-CN")) == .zhCN)
        #expect(CozyTingL10n.resolve(.system, systemLocale: Locale(identifier: "ja-JP")) == .ja)
        #expect(CozyTingL10n.resolve(.system, systemLocale: Locale(identifier: "en-US")) == .en)
        #expect(CozyTingL10n.resolve(.system, systemLocale: Locale(identifier: "fr-FR")) == .en)

        #expect(CozyTingL10n.text(.aboutTitle, language: .zhCN) == "关于窝爱听")
        #expect(CozyTingL10n.text(.aboutTitle, language: .en) == "About CozyTing")
        #expect(CozyTingL10n.text(.aboutTitle, language: .ja) == "CozyTing について")
        #expect(CozyTingL10n.text(.settingsShowAllSub, language: .zhCN).contains("MiPlay"))
        #expect(CozyTingL10n.text(.settingsShowAllSub, language: .en).contains("MiPlay"))
        #expect(CozyTingL10n.text(.settingsShowAllSub, language: .ja).contains("MiPlay"))
    }
}
