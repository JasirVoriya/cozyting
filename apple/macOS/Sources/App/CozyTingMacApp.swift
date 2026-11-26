import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

@main
struct CozyTingMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        let title = BrandStrings.appDisplayName(for: appDelegate.preferences.appLanguage)

        Window(title, id: "main") {
            MainWindowView(
                coordinator: appDelegate.castCoordinator,
                onOpenSettings: {
                    appDelegate.openSettingsWindow()
                }
            )
        }
        .defaultSize(width: 480, height: 600)

        Settings {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                Text("Settings")
                    .font(.headline)
                Toggle("Mute target app locally while casting", isOn: Binding(
                    get: { appDelegate.castCoordinator.muteLocalAudio },
                    set: { appDelegate.castCoordinator.setMuteLocalAudio($0) }
                ))
                Toggle("Show all discovered LAN devices", isOn: Binding(
                    get: { appDelegate.castCoordinator.showAllDiscovered },
                    set: { appDelegate.castCoordinator.setShowAllDiscovered($0) }
                ))
            }
            .padding(DesignTokens.Spacing.lg)
            .frame(width: 420)
        }
    }
}
