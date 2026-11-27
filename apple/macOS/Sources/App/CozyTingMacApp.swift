import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

@main
struct CozyTingMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        // Window lifecycle (Main Window, Settings Window, and Menu Bar Popover) is managed
        // through a single unified NSWindowController layer in `AppDelegate`, preventing
        // duplicate windows and guaranteeing reliable Settings opening under LSUIElement.
        Settings {
            SettingsWindowView(
                coordinator: appDelegate.castCoordinator,
                updateService: appDelegate.updateService,
                onAppearanceChanged: { mode in
                    appDelegate.applyAppAppearance(mode)
                }
            )
        }
        .commands {
            CommandGroup(replacing: .appSettings) {
                Button(CozyTingL10n.text(.menuSettings, language: appDelegate.castCoordinator.appLanguage)) {
                    appDelegate.openSettingsWindow()
                }
                .keyboardShortcut(",", modifiers: .command)
            }
        }
    }
}
