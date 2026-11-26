import AppKit
import CozyTingCoreKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let preferences = PreferencesStore()
    let logManager = CozyTingLogManager()
    private(set) lazy var updateService = UpdateService(preferences: preferences)

    func applicationDidFinishLaunching(_ notification: Notification) {
        MuteGuard.shared.installTerminationHooks()
        logManager.initializeCoreLog()
        CozyTingCoreBridge.setVolumeGuardPolicy(
            enabled: preferences.volumeGuardEnabled,
            safeLimit: Int32(preferences.volumeGuardSafeLimit)
        )
        CozyTingCoreBridge.setAuthMode(preferences.authMode)
        logManager.runtimeLogger.info(tag: "App", "CozyTingMac launched")
    }

    func applicationWillTerminate(_ notification: Notification) {
        MuteGuard.shared.emergencyTeardownAll()
        logManager.runtimeLogger.info(tag: "App", "CozyTingMac terminating (MuteGuard teardown complete)")
        logManager.runtimeLogger.flush()
    }
}
