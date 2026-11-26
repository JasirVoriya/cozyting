import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

@main
struct CozyTingMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        let title = BrandStrings.appDisplayName(for: appDelegate.castCoordinator.appLanguage)

        Window(title, id: "main") {
            MainWindowView(
                coordinator: appDelegate.castCoordinator,
                onOpenSettings: {
                    appDelegate.openSettingsWindow()
                },
                onAppearanceChanged: { mode in
                    appDelegate.applyAppAppearance(mode)
                }
            )
        }
        .defaultSize(width: 480, height: 600)

        Settings {
            SettingsWindowView(
                coordinator: appDelegate.castCoordinator,
                updateService: appDelegate.updateService,
                onAppearanceChanged: { mode in
                    appDelegate.applyAppAppearance(mode)
                }
            )
        }
    }
}
