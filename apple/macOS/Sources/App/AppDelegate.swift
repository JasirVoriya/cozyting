import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    let preferences = PreferencesStore()
    let logManager = CozyTingLogManager()
    private(set) lazy var castCoordinator = CastCoordinator(
        preferences: preferences,
        logManager: logManager
    )
    private(set) lazy var updateService = UpdateService(preferences: preferences)

    private var menuBarController: MenuBarStatusController?
    private var mainWindowController: NSWindowController?
    private var settingsWindowController: NSWindowController?

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

        logManager.runtimeLogger.info(tag: "App", "CozyTingMac launched (Native macOS Frosted Glass)")
        castCoordinator.refreshWifiFrequency()

        openMainWindow()

        Task { [weak self] in
            guard let self else { return }
            await self.castCoordinator.discoverDevices(manual: false)
            _ = await self.updateService.maybeAutoCheck()
            await self.castCoordinator.syncUpdatePresentation(from: self.updateService)
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        openMainWindow()
        return true
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
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
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)

        if let existing = mainWindowController?.window {
            existing.title = BrandStrings.appDisplayName(for: castCoordinator.appLanguage)
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
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 650),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        let hosting = NSHostingController(rootView: content)
        hosting.sizingOptions = [.minSize]
        window.title = title
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.isOpaque = false
        window.backgroundColor = .clear
        window.contentViewController = hosting
        window.setContentSize(NSSize(width: 420, height: 650))
        window.contentMinSize = NSSize(width: 390, height: 540)
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        let controller = NSWindowController(window: window)
        mainWindowController = controller
        controller.showWindow(nil)
        window.makeKeyAndOrderFront(nil)
    }

    func openSettingsWindow() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)

        if let existing = settingsWindowController?.window {
            existing.title = CozyTingL10n.text(.settingsTitle, language: castCoordinator.appLanguage)
            existing.makeKeyAndOrderFront(nil)
            return
        }

        let content = SettingsWindowView(
            coordinator: castCoordinator,
            updateService: updateService,
            onAppearanceChanged: { [weak self] mode in
                self?.applyAppAppearance(mode)
            },
            onLanguageChanged: { [weak self] lang in
                self?.refreshWindowTitles(for: lang)
            }
        )
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 540, height: 640),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        let hosting = NSHostingController(rootView: content)
        hosting.sizingOptions = [.minSize]
        window.title = CozyTingL10n.text(.settingsTitle, language: castCoordinator.appLanguage)
        window.titlebarAppearsTransparent = true
        window.isOpaque = false
        window.backgroundColor = .clear
        window.contentViewController = hosting
        window.setContentSize(NSSize(width: 540, height: 640))
        window.contentMinSize = NSSize(width: 500, height: 520)
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        let controller = NSWindowController(window: window)
        settingsWindowController = controller
        controller.showWindow(nil)
        window.makeKeyAndOrderFront(nil)
    }

    func refreshWindowTitles(for language: AppLanguage) {
        mainWindowController?.window?.title = BrandStrings.appDisplayName(for: language)
        settingsWindowController?.window?.title = CozyTingL10n.text(.settingsTitle, language: language)
    }

    // MARK: - NSWindowDelegate

    func windowWillClose(_ notification: Notification) {
        let closingWindow = notification.object as? NSWindow
        Task { @MainActor [weak self] in
            guard let self else { return }
            let mainVisible = (self.mainWindowController?.window != closingWindow)
                && (self.mainWindowController?.window?.isVisible == true)
            let settingsVisible = (self.settingsWindowController?.window != closingWindow)
                && (self.settingsWindowController?.window?.isVisible == true)
            if !mainVisible && !settingsVisible {
                NSApp.setActivationPolicy(.accessory)
            }
        }
    }
}
