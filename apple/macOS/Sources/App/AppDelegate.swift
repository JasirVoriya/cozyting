import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let preferences = PreferencesStore()
    let logManager = CozyTingLogManager()
    private(set) lazy var castCoordinator = CastCoordinator(
        preferences: preferences,
        logManager: logManager
    )
    private(set) lazy var updateService = UpdateService(preferences: preferences)

    private var menuBarController: MenuBarStatusController?
    private var mainWindowController: NSWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        MuteGuard.shared.installTerminationHooks()
        logManager.initializeCoreLog()
        CozyTingCoreBridge.setVolumeGuardPolicy(
            enabled: preferences.volumeGuardEnabled,
            safeLimit: Int32(preferences.volumeGuardSafeLimit)
        )
        CozyTingCoreBridge.setAuthMode(preferences.authMode)
        applyAppAppearance(preferences.themeMode)

        menuBarController = MenuBarStatusController(
            castCoordinator: castCoordinator,
            onOpenMainWindow: { [weak self] in
                self?.openMainWindow()
            },
            onOpenSettings: { [weak self] in
                self?.openSettingsWindow()
            }
        )

        logManager.runtimeLogger.info(tag: "App", "CozyTingMac launched (MenuBar + Style B)")
        castCoordinator.refreshWifiFrequency()
        Task { [weak self] in
            guard let self else { return }
            await self.castCoordinator.discoverDevices(manual: false)
            _ = await self.updateService.maybeAutoCheck()
            await self.castCoordinator.syncUpdatePresentation(from: self.updateService)
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        castCoordinator.stopBackgroundMonitors()
        castCoordinator.audioEngine.stopCapture()
        MuteGuard.shared.emergencyTeardownAll()
        logManager.runtimeLogger.info(tag: "App", "CozyTingMac terminating (MuteGuard teardown complete)")
        logManager.runtimeLogger.flush()
    }

    func applyAppAppearance(_ mode: ThemeMode) {
        switch mode {
        case .system:
            NSApp.appearance = nil
        case .light:
            NSApp.appearance = NSAppearance(named: .aqua)
        case .dark:
            NSApp.appearance = NSAppearance(named: .darkAqua)
        }
    }

    func openMainWindow() {
        NSApp.activate(ignoringOtherApps: true)
        if let existing = mainWindowController?.window {
            existing.makeKeyAndOrderFront(nil)
            return
        }
        let title = BrandStrings.appDisplayName(for: castCoordinator.appLanguage)
        let content = MainWindowView(
            coordinator: castCoordinator,
            onOpenSettings: { [weak self] in
                self?.openSettingsWindow()
            },
            onAppearanceChanged: { [weak self] mode in
                self?.applyAppAppearance(mode)
            }
        )
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 600),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = title
        window.center()
        window.contentViewController = NSHostingController(rootView: content)
        let controller = NSWindowController(window: window)
        mainWindowController = controller
        controller.showWindow(nil)
    }

    func openSettingsWindow() {
        NSApp.activate(ignoringOtherApps: true)
        NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
    }
}
