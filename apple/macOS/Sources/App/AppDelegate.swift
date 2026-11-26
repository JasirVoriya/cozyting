import AppKit
import CozyTingCoreKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let preferences = PreferencesStore()
    let logManager = CozyTingLogManager()
    private(set) lazy var updateService = UpdateService(preferences: preferences)

    func applicationDidFinishLaunching(_ notification: Notification) {
        logManager.initializeCoreLog()
        CozyTingCoreBridge.setVolumeGuardPolicy(
            enabled: preferences.volumeGuardEnabled,
            safeLimit: Int32(preferences.volumeGuardSafeLimit)
        )
        CozyTingCoreBridge.setAuthMode(preferences.authMode)
        logManager.runtimeLogger.info(tag: "App", "CozyTingMac launched (M1 shell)")
    }

    func applicationWillTerminate(_ notification: Notification) {
        // M2 will hook ProcessTapAudioEngine / MuteGuard teardown before L1 engine stop here.
        logManager.runtimeLogger.info(tag: "App", "CozyTingMac terminating")
        logManager.runtimeLogger.flush()
    }
}
