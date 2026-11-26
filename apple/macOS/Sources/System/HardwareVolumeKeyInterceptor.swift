import AppKit
import ApplicationServices
import Foundation

/// Opt-in global F11 / F12 hardware volume key interceptor via `CGEventTap` (`M7`).
///
/// Privacy & Permission Discipline (`docs/MACOS_CLIENT_DESIGN.md` §5.4):
/// - Strictly OFF by default (scroll-wheel over menu bar icon & panel shortcuts work with zero permissions).
/// - NEVER triggers silent Accessibility prompts on startup (`AXIsProcessTrusted()` without prompt option).
/// - Only intercepts `NX_KEYTYPE_SOUND_UP` / `NX_KEYTYPE_SOUND_DOWN` while actively casting;
///   passes all other keys straight through to macOS unchanged.
@MainActor
public final class HardwareVolumeKeyInterceptor {
    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var isEnabled: Bool = false

    private let isCastingProvider: @MainActor () -> Bool
    private let onVolumeStep: @MainActor (Int) async -> Void

    public init(
        isCastingProvider: @escaping @MainActor () -> Bool,
        onVolumeStep: @escaping @MainActor (Int) async -> Void
    ) {
        self.isCastingProvider = isCastingProvider
        self.onVolumeStep = onVolumeStep
    }

    public static func isAccessibilityGranted() -> Bool {
        AXIsProcessTrusted()
    }

    public static func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    @discardableResult
    public func setEnabled(_ enabled: Bool) -> Bool {
        isEnabled = enabled
        guard enabled else {
            stopTap()
            return true
        }
        guard Self.isAccessibilityGranted() else {
            stopTap()
            return false
        }
        return startTap()
    }

    public func stopTap() {
        if let source = runLoopSource {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes)
            runLoopSource = nil
        }
        if let tap = eventTap {
            CGEvent.tapEnable(tap: tap, enable: false)
            CFMachPortInvalidate(tap)
            eventTap = nil
        }
    }

    private func startTap() -> Bool {
        guard eventTap == nil else { return true }
        // System-defined event type = 14 (NX_SYSDEFINED)
        let mask = CGEventMask(1 << 14)
        let refcon = Unmanaged.passUnretained(self).toOpaque()
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: mask,
            callback: { _, type, cgEvent, userInfo in
                guard let userInfo else { return Unmanaged.passUnretained(cgEvent) }
                let interceptor = Unmanaged<HardwareVolumeKeyInterceptor>.fromOpaque(userInfo).takeUnretainedValue()
                return interceptor.handleCGEvent(type: type, cgEvent: cgEvent)
            },
            userInfo: refcon
        ) else {
            return false
        }
        let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
        eventTap = tap
        runLoopSource = source
        return true
    }

    private nonisolated func handleCGEvent(
        type: CGEventType,
        cgEvent: CGEvent
    ) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            return Unmanaged.passUnretained(cgEvent)
        }
        guard let nsEvent = NSEvent(cgEvent: cgEvent),
              nsEvent.type == .systemDefined,
              nsEvent.subtype.rawValue == 8 else {
            return Unmanaged.passUnretained(cgEvent)
        }

        let keyCode = Int32((nsEvent.data1 & 0xFFFF_0000) >> 16)
        let keyFlags = nsEvent.data1 & 0x0000_FFFF
        let keyState = ((keyFlags & 0xFF00) >> 8) == 0xA // key down

        // NX_KEYTYPE_SOUND_UP = 0, NX_KEYTYPE_SOUND_DOWN = 1
        guard keyCode == 0 || keyCode == 1 else {
            return Unmanaged.passUnretained(cgEvent)
        }

        let shouldConsume = MainActor.assumeIsolated { [weak self] () -> Bool in
            guard let self, self.isEnabled, self.isCastingProvider() else { return false }
            if keyState {
                let delta = (keyCode == 0) ? 5 : -5
                Task { @MainActor [weak self] in
                    await self?.onVolumeStep(delta)
                }
            }
            return true
        }

        return shouldConsume ? nil : Unmanaged.passUnretained(cgEvent)
    }
}
