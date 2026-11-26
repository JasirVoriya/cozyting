import CozyTingAudioKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

@main
struct CozyTingMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.colorScheme) private var colorScheme

    var body: some Scene {
        let palette = DesignTokens.ThemePalette.palette(for: colorScheme)
        let title = BrandStrings.appDisplayName(for: appDelegate.preferences.appLanguage)
        let canonical = AudioStreamFormat.canonical

        MenuBarExtra(title, systemImage: "hifispeaker.2") {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                Text(title)
                    .font(.system(size: DesignTokens.Typography.pageTitle, weight: .bold))
                    .foregroundStyle(palette.textPrimary)
                Text("\(BrandStrings.protocolName) · \(Int(canonical.sampleRate)) Hz")
                    .font(.system(size: DesignTokens.Typography.caption))
                    .foregroundStyle(palette.textSecondary)
                Divider()
                Button("Quit \(title)") {
                    NSApplication.shared.terminate(nil)
                }
            }
            .padding(DesignTokens.Spacing.md)
            .frame(width: 240)
        }
        .menuBarExtraStyle(.window)
    }
}
