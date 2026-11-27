import AppKit
import CozyTingCoreKit
import Foundation
import Observation

/// Represents an installed or running macOS application eligible as a single-app audio source.
public struct InstalledAppItem: Identifiable, Hashable, Sendable {
    public var id: String { bundleIdentifier }
    public let bundleIdentifier: String
    public let displayName: String
    public let appURL: URL
    public let isRunning: Bool
}

/// Enumerates macOS audio source applications via `NSWorkspace`, caches native HD icons,
/// seeds default favorite music apps on first launch, and supports click-to-launch.
@MainActor
@Observable
public final class AppSourceCatalog {
    public static let knownMusicBundleIDs: [String] = [
        "com.netease.163music",
        "com.tencent.QQMusicMac",
        "com.spotify.client",
        "com.apple.Music",
        "com.apple.podcasts",
        "com.google.Chrome",
        "com.apple.Safari",
        "org.videolan.vlc",
        "com.colliderli.iina",
        "com.microsoft.edgemac",
        "org.mozilla.firefox",
    ]

    public private(set) var installedApps: [InstalledAppItem] = []
    private var iconCache: [String: NSImage] = [:]
    private let preferences: PreferencesStore

    public init(preferences: PreferencesStore) {
        self.preferences = preferences
    }

    public func refreshCatalog() {
        let selfBundleID = Bundle.main.bundleIdentifier?.lowercased() ?? "com.cozyting.app"
        let runningApps = NSWorkspace.shared.runningApplications
        let runningSet = Set(runningApps.compactMap { $0.bundleIdentifier?.lowercased() })

        var byBundle: [String: InstalledAppItem] = [:]

        // 1. Scan standard macOS application directories.
        let fm = FileManager.default
        var roots: [URL] = [
            URL(fileURLWithPath: "/Applications", isDirectory: true),
            URL(fileURLWithPath: "/System/Applications", isDirectory: true),
        ]
        if let userApps = fm.urls(for: .applicationDirectory, in: .userDomainMask).first {
            roots.append(userApps)
        }

        for root in roots {
            guard let items = try? fm.contentsOfDirectory(
                at: root,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            ) else { continue }
            for url in items where url.pathExtension.lowercased() == "app" {
                guard let bundle = Bundle(url: url),
                      let bid = bundle.bundleIdentifier?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !bid.isEmpty,
                      bid.lowercased() != selfBundleID else {
                    continue
                }
                let name = (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
                    ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
                    ?? url.deletingPathExtension().lastPathComponent
                let key = bid.lowercased()
                if byBundle[key] == nil {
                    byBundle[key] = InstalledAppItem(
                        bundleIdentifier: bid,
                        displayName: name,
                        appURL: url,
                        isRunning: runningSet.contains(key)
                    )
                }
            }
        }

        // 2. Include currently running regular GUI apps that may live outside /Applications.
        for app in runningApps where app.activationPolicy == .regular {
            guard let bid = app.bundleIdentifier?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !bid.isEmpty,
                  bid.lowercased() != selfBundleID,
                  let url = app.bundleURL else {
                continue
            }
            let key = bid.lowercased()
            if byBundle[key] == nil {
                byBundle[key] = InstalledAppItem(
                    bundleIdentifier: bid,
                    displayName: app.localizedName ?? url.deletingPathExtension().lastPathComponent,
                    appURL: url,
                    isRunning: true
                )
            }
        }

        installedApps = byBundle.values.sorted { lhs, rhs in
            if lhs.isRunning != rhs.isRunning { return lhs.isRunning }
            return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
        }

        seedDefaultFavoritesIfNeeded(byBundle: byBundle)
    }

    /// Resolves display name for a bundle ID from catalog or `NSWorkspace`.
    public func displayName(for bundleIdentifier: String) -> String {
        if let hit = installedApps.first(where: { $0.bundleIdentifier.caseInsensitiveCompare(bundleIdentifier) == .orderedSame }) {
            return hit.displayName
        }
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleIdentifier),
           let bundle = Bundle(url: url) {
            return (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
                ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
                ?? url.deletingPathExtension().lastPathComponent
        }
        return bundleIdentifier
    }

    /// Loads and caches the crisp native macOS app icon for `bundleIdentifier`.
    public func icon(for bundleIdentifier: String) -> NSImage? {
        let key = bundleIdentifier.lowercased()
        if let cached = iconCache[key] {
            return cached
        }
        let appURL = installedApps.first(where: { $0.bundleIdentifier.lowercased() == key })?.appURL
            ?? NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleIdentifier)
        guard let url = appURL else { return nil }
        let img = NSWorkspace.shared.icon(forFile: url.path)
        iconCache[key] = img
        return img
    }

    /// Returns `true` if `bundleIdentifier` is currently running on macOS.
    public func isRunning(bundleIdentifier: String) -> Bool {
        NSWorkspace.shared.runningApplications.contains {
            !$0.isTerminated && $0.bundleIdentifier?.caseInsensitiveCompare(bundleIdentifier) == .orderedSame
        }
    }

    /// Launches or activates the target audio application cleanly via `NSWorkspace`.
    public func launchApplication(bundleIdentifier: String) {
        let url = installedApps.first(where: { $0.bundleIdentifier.caseInsensitiveCompare(bundleIdentifier) == .orderedSame })?.appURL
            ?? NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleIdentifier)
        guard let appURL = url else { return }
        let config = NSWorkspace.OpenConfiguration()
        config.activates = true
        NSWorkspace.shared.openApplication(at: appURL, configuration: config) { _, _ in }
    }

    /// Launches the target audio application and waits up to `timeoutSeconds` for it to appear in `NSWorkspace`.
    @discardableResult
    public func launchApplicationAndWait(
        bundleIdentifier: String,
        timeoutSeconds: Double = 5.0
    ) async -> Bool {
        if isRunning(bundleIdentifier: bundleIdentifier) {
            return true
        }
        launchApplication(bundleIdentifier: bundleIdentifier)
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            try? await Task.sleep(for: .milliseconds(200))
            if isRunning(bundleIdentifier: bundleIdentifier) {
                refreshCatalog()
                return true
            }
        }
        refreshCatalog()
        return isRunning(bundleIdentifier: bundleIdentifier)
    }

    private func seedDefaultFavoritesIfNeeded(byBundle: [String: InstalledAppItem]) {
        guard !preferences.hasInitializedFavoriteSources else { return }
        var seeded: [FavoriteAudioSource] = []
        for known in Self.knownMusicBundleIDs {
            if let item = byBundle[known.lowercased()] {
                seeded.append(FavoriteAudioSource(bundleIdentifier: item.bundleIdentifier, autoDetectEnabled: true))
            }
        }
        preferences.saveFavoriteAudioSources(seeded)

        // Pre-select a currently running favorite app first so casting works immediately on first click;
        // otherwise fall back to the first installed favorite.
        if preferences.loadSelectedAudioSource() == nil {
            let preferred = seeded.first(where: { byBundle[$0.bundleIdentifier.lowercased()]?.isRunning == true })
                ?? seeded.first
            if let preferred {
                preferences.saveSelectedAudioSource(
                    AudioSourceSelection(
                        bundleIdentifier: preferred.bundleIdentifier,
                        displayName: displayName(for: preferred.bundleIdentifier)
                    )
                )
            }
        }
    }
}
