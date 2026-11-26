import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Native `NSStatusItem` + `NSPopover` controller for macOS 14.2+.
///
/// Delivers `docs/MACOS_CLIENT_DESIGN.md` §2.1:
/// - Left-click toggles the `352pt` frosted-glass Style B popover directly above any Space/fullscreen app.
/// - Right-click opens a native context menu (Pause/Resume, Switch to Mac, Open Main Window, Settings, Quit).
/// - Scroll-wheel over the status bar icon adjusts master volume by ±5% with zero Accessibility permissions.
@MainActor
public final class MenuBarStatusController: NSObject {
    private let castCoordinator: CastCoordinator
    private let onOpenMainWindow: () -> Void
    private let onOpenSettings: () -> Void

    private var statusItem: NSStatusItem?
    private let popover = NSPopover()
    nonisolated(unsafe) private var scrollMonitor: Any?
    private var statusIconObservationTask: Task<Void, Never>?

    public init(
        castCoordinator: CastCoordinator,
        onOpenMainWindow: @escaping () -> Void,
        onOpenSettings: @escaping () -> Void
    ) {
        self.castCoordinator = castCoordinator
        self.onOpenMainWindow = onOpenMainWindow
        self.onOpenSettings = onOpenSettings
        super.init()
        setupStatusItem()
        setupPopover()
        installScrollWheelMonitor()
        startIconRefreshLoop()
    }

    deinit {
        statusIconObservationTask?.cancel()
        if let monitor = scrollMonitor {
            NSEvent.removeMonitor(monitor)
        }
    }

    public func invalidate() {
        statusIconObservationTask?.cancel()
        statusIconObservationTask = nil
        if let monitor = scrollMonitor {
            NSEvent.removeMonitor(monitor)
            scrollMonitor = nil
        }
        popover.performClose(nil)
    }

    public func closePopover() {
        popover.performClose(nil)
    }

    public func refreshStatusIcon() {
        guard let button = statusItem?.button else { return }
        button.image = PlatformAdapters.makeMenuBarBrandTemplateImage(active: castCoordinator.isCasting)
        let appName = BrandStrings.appDisplayName(for: castCoordinator.preferences.appLanguage)
        if castCoordinator.isCasting {
            button.toolTip = "\(appName) · \(castCoordinator.selectedSpeakerIds.count)"
        } else {
            button.toolTip = appName
        }
    }

    private func setupStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = item.button {
            button.image = PlatformAdapters.makeMenuBarBrandTemplateImage(active: false)
            button.imagePosition = .imageOnly
            button.target = self
            button.action = #selector(handleStatusItemClick(_:))
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }
        statusItem = item
    }

    private func setupPopover() {
        popover.behavior = .transient
        popover.animates = true
        popover.contentSize = NSSize(width: 352, height: 500)
        let rootView = PopoverControlCardView(
            coordinator: castCoordinator,
            onOpenMainWindow: { [weak self] in
                self?.closePopover()
                self?.onOpenMainWindow()
            },
            onOpenSettings: { [weak self] in
                self?.closePopover()
                self?.onOpenSettings()
            }
        )
        popover.contentViewController = NSHostingController(rootView: rootView)
    }

    @objc private func handleStatusItemClick(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else {
            togglePopover(relativeTo: sender)
            return
        }
        if event.type == .rightMouseUp || (event.type == .leftMouseUp && event.modifierFlags.contains(.control)) {
            showContextMenu(for: sender)
        } else {
            togglePopover(relativeTo: sender)
        }
    }

    private func togglePopover(relativeTo button: NSStatusBarButton) {
        if popover.isShown {
            popover.performClose(nil)
        } else {
            NSApp.activate(ignoringOtherApps: true)
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            Task { [weak self] in
                await self?.castCoordinator.discoverDevices(manual: false)
            }
        }
    }

    private func showContextMenu(for button: NSStatusBarButton) {
        let menu = NSMenu()
        let lang = castCoordinator.appLanguage
        let appName = BrandStrings.appDisplayName(for: lang)

        if castCoordinator.isCasting {
            let pauseTitle = CozyTingL10n.text(
                castCoordinator.isPaused ? .menuResumeCast : .menuPauseCast,
                language: lang
            )
            let pauseItem = NSMenuItem(title: pauseTitle, action: #selector(menuTogglePause), keyEquivalent: "")
            pauseItem.target = self
            menu.addItem(pauseItem)

            let stopItem = NSMenuItem(
                title: CozyTingL10n.text(.menuSwitchBackToMac, language: lang),
                action: #selector(menuStopCast),
                keyEquivalent: ""
            )
            stopItem.target = self
            menu.addItem(stopItem)
            menu.addItem(.separator())
        }

        let mainItem = NSMenuItem(
            title: CozyTingL10n.format(.menuOpenApp, language: lang, appName),
            action: #selector(menuOpenMainWindow),
            keyEquivalent: ""
        )
        mainItem.target = self
        menu.addItem(mainItem)

        let settingsItem = NSMenuItem(
            title: CozyTingL10n.text(.menuSettings, language: lang),
            action: #selector(menuOpenSettings),
            keyEquivalent: ","
        )
        settingsItem.target = self
        menu.addItem(settingsItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(
            title: CozyTingL10n.format(.menuQuitApp, language: lang, appName),
            action: #selector(menuQuit),
            keyEquivalent: "q"
        )
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem?.menu = menu
        button.performClick(nil)
        statusItem?.menu = nil
    }

    private func installScrollWheelMonitor() {
        scrollMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self,
                  let button = self.statusItem?.button,
                  let btnWindow = button.window,
                  event.window === btnWindow else {
                return event
            }
            let dy = event.scrollingDeltaY
            if abs(dy) > 0.5 {
                let delta = dy > 0 ? 5 : -5
                Task { @MainActor [weak self] in
                    await self?.castCoordinator.stepMasterVolume(by: delta)
                }
            }
            return event
        }
    }

    private func startIconRefreshLoop() {
        statusIconObservationTask = Task { @MainActor [weak self] in
            var lastCasting: Bool?
            var lastCount: Int = -1
            while !Task.isCancelled {
                guard let self else { return }
                let casting = self.castCoordinator.isCasting
                let count = self.castCoordinator.selectedSpeakerIds.count
                if casting != lastCasting || count != lastCount {
                    lastCasting = casting
                    lastCount = count
                    self.refreshStatusIcon()
                }
                try? await Task.sleep(for: .milliseconds(350))
            }
        }
    }

    @objc private func menuTogglePause() {
        Task { await castCoordinator.togglePauseResume() }
    }

    @objc private func menuStopCast() {
        Task { await castCoordinator.selectThisDevice() }
    }

    @objc private func menuOpenMainWindow() {
        onOpenMainWindow()
    }

    @objc private func menuOpenSettings() {
        onOpenSettings()
    }

    @objc private func menuQuit() {
        NSApp.terminate(nil)
    }
}
