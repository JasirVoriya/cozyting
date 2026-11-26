import CozyTingCoreKit
import Foundation

/// Brand-name vs protocol-name decoupling shared across Apple platforms.
///
/// - Simplified Chinese (`zh-CN` / `zh-Hans`): `"窝爱听"`
/// - Non-Chinese (`en`, `ja`, etc.): `"CozyTing"`
/// - Underlying LAN casting protocol identifier: `"MiPlay"`
public enum BrandStrings {
    public static let protocolName = "MiPlay"
    public static let chineseBrandName = "窝爱听"
    public static let internationalBrandName = "CozyTing"

    public static func appDisplayName(for language: AppLanguage, locale: Locale = .current) -> String {
        switch language {
        case .zhCN:
            return chineseBrandName
        case .en, .ja:
            return internationalBrandName
        case .system:
            let code = locale.language.languageCode?.identifier.lowercased() ?? ""
            let script = locale.language.script?.identifier.lowercased() ?? ""
            if code == "zh" && (script.isEmpty || script == "hans") {
                return chineseBrandName
            }
            return internationalBrandName
        }
    }
}
