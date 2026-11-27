import AppKit
import CoreAudio
import Darwin
import Foundation

/// Metadata describing an active Core Audio process object on macOS 14.2+.
public struct AudioProcessInfo: Sendable, Equatable {
    public let objectID: AudioObjectID
    public let pid: pid_t
    public let bundleIdentifier: String
    public let isRunning: Bool
    public let isRunningOutput: Bool
}

/// Resolves a single target application's Bundle Identifier into its full active Core Audio process tree.
///
/// Strictly Fail-Closed (`apple/AGENTS.md` §4.1):
/// - Aggregates main process + Electron / Chromium / WebKit `Helper (Audio)` / `Renderer` descendants
///   via BSD parent-PID traversal (`proc_pidinfo` `PROC_PIDTBSDINFO`) and helper bundle-ID prefix matching.
/// - Excludes CozyTing's own PID.
/// - NEVER falls back to global system capture or microphone input when no matching processes exist.
public enum ProcessTreeResolver {
    /// Returns all active Core Audio process objects currently known to `coreaudiod`.
    public static func listAudioProcesses() -> [AudioProcessInfo] {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyProcessObjectList,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )

        var dataSize: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            0,
            nil,
            &dataSize
        ) == noErr, dataSize > 0 else {
            return []
        }

        let count = Int(dataSize) / MemoryLayout<AudioObjectID>.size
        var objectIDs = [AudioObjectID](repeating: kAudioObjectUnknown, count: count)
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            0,
            nil,
            &dataSize,
            &objectIDs
        ) == noErr else {
            return []
        }

        return objectIDs.compactMap { objID -> AudioProcessInfo? in
            guard objID != kAudioObjectUnknown else { return nil }
            let pid = readPID(for: objID)
            guard pid > 0 else { return nil }
            let bundleID = readBundleID(for: objID)
            let isRunning = readBoolProperty(for: objID, selector: kAudioProcessPropertyIsRunning)
            let isRunningOutput = readBoolProperty(for: objID, selector: kAudioProcessPropertyIsRunningOutput)
            return AudioProcessInfo(
                objectID: objID,
                pid: pid,
                bundleIdentifier: bundleID,
                isRunning: isRunning,
                isRunningOutput: isRunningOutput
            )
        }
    }

    /// Returns `true` if at least one non-self process with `bundleIdentifier` is currently running.
    public static func isApplicationRunning(bundleIdentifier: String) -> Bool {
        let targetBundle = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !targetBundle.isEmpty else { return false }
        let selfPID = ProcessInfo.processInfo.processIdentifier
        return NSWorkspace.shared.runningApplications.contains { app in
            app.processIdentifier > 0
                && app.processIdentifier != selfPID
                && !app.isTerminated
                && app.bundleIdentifier?.caseInsensitiveCompare(targetBundle) == .orderedSame
        }
    }

    /// Resolves a target application's `bundleIdentifier` into all Core Audio process object IDs
    /// belonging to that application's process tree (including Chromium/Electron audio helpers).
    public static func resolveAudioObjectIDs(forBundleIdentifier bundleIdentifier: String) -> [AudioObjectID] {
        let targetBundle = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !targetBundle.isEmpty else { return [] }

        let selfPID = ProcessInfo.processInfo.processIdentifier
        let runningApps = NSWorkspace.shared.runningApplications
        let rootPIDs = Set(
            runningApps
                .filter { $0.bundleIdentifier?.caseInsensitiveCompare(targetBundle) == .orderedSame }
                .map(\.processIdentifier)
                .filter { $0 > 0 && $0 != selfPID }
        )

        let allProcesses = listAudioProcesses()
        var matched: [AudioObjectID] = []
        var seen: Set<AudioObjectID> = []

        for proc in allProcesses {
            guard proc.pid != selfPID else { continue }
            if matchesTarget(
                process: proc,
                targetBundle: targetBundle,
                rootPIDs: rootPIDs
            ) {
                if seen.insert(proc.objectID).inserted {
                    matched.append(proc.objectID)
                }
            }
        }

        // Fallback: translate root PIDs directly when app is running but has not yet created an audio node.
        if matched.isEmpty && !rootPIDs.isEmpty {
            for pid in rootPIDs {
                if let objID = translatePIDToAudioObject(pid), seen.insert(objID).inserted {
                    matched.append(objID)
                }
            }
        }

        return matched
    }

    /// Returns the set of parent-normalized app Bundle Identifiers currently outputting audio.
    /// Used for M6 debounced auto-detection via `kAudioProcessPropertyIsRunningOutput`.
    public static func activeOutputBundleIdentifiers() -> Set<String> {
        let selfPID = ProcessInfo.processInfo.processIdentifier
        let runningByPID: [pid_t: String] = Dictionary(
            uniqueKeysWithValues: NSWorkspace.shared.runningApplications.compactMap { app in
                guard app.processIdentifier > 0, let bid = app.bundleIdentifier, !bid.isEmpty else {
                    return nil
                }
                return (app.processIdentifier, bid)
            }
        )

        var activeBundles: Set<String> = []
        for proc in listAudioProcesses() where proc.isRunningOutput && proc.pid != selfPID {
            if let rootBundle = resolveOwnerBundleIdentifier(pid: proc.pid, runningByPID: runningByPID) {
                activeBundles.insert(rootBundle)
            } else if !proc.bundleIdentifier.isEmpty {
                activeBundles.insert(normalizeHelperBundleIdentifier(proc.bundleIdentifier))
            }
        }
        return activeBundles
    }

    /// Checks whether a given process belongs to `targetBundle` via direct bundle match,
    /// helper bundle prefix, or BSD parent-PID ancestry.
    static func matchesTarget(
        process: AudioProcessInfo,
        targetBundle: String,
        rootPIDs: Set<pid_t>
    ) -> Bool {
        if rootPIDs.contains(process.pid) {
            return true
        }
        if !process.bundleIdentifier.isEmpty {
            if process.bundleIdentifier.caseInsensitiveCompare(targetBundle) == .orderedSame {
                return true
            }
            let lowerProc = process.bundleIdentifier.lowercased()
            let lowerTarget = targetBundle.lowercased()
            if lowerProc.hasPrefix(lowerTarget + ".") {
                return true
            }
        }
        if !rootPIDs.isEmpty {
            return hasAncestor(pid: process.pid, in: rootPIDs)
        }
        return false
    }

    /// Walks up BSD parent PIDs (up to 8 levels) to match Chromium / Electron / WebKit helper sub-processes.
    public static func hasAncestor(pid: pid_t, in ancestorPIDs: Set<pid_t>) -> Bool {
        var current = pid
        for _ in 0..<8 {
            guard current > 1 else { return false }
            let parent = parentPID(of: current)
            guard parent > 1, parent != current else { return false }
            if ancestorPIDs.contains(parent) {
                return true
            }
            current = parent
        }
        return false
    }

    public static func resolveOwnerBundleIdentifier(
        pid: pid_t,
        runningByPID: [pid_t: String]
    ) -> String? {
        if let direct = runningByPID[pid] {
            return direct
        }
        var current = pid
        for _ in 0..<8 {
            guard current > 1 else { return nil }
            let parent = parentPID(of: current)
            guard parent > 1, parent != current else { return nil }
            if let bundle = runningByPID[parent] {
                return bundle
            }
            current = parent
        }
        return nil
    }

    private static func normalizeHelperBundleIdentifier(_ raw: String) -> String {
        let suffixes = [".helper", ".helper.renderer", ".helper.audio", ".helper.plugin", ".webcontent"]
        let lower = raw.lowercased()
        for suffix in suffixes {
            if lower.hasSuffix(suffix) {
                return String(raw.dropLast(suffix.count))
            }
        }
        return raw
    }

    private static func parentPID(of pid: pid_t) -> pid_t {
        var info = proc_bsdinfo()
        let size = Int32(MemoryLayout<proc_bsdinfo>.stride)
        let ret = proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, size)
        guard ret == size else { return 0 }
        return pid_t(info.pbi_ppid)
    }

    private static func translatePIDToAudioObject(_ pid: pid_t) -> AudioObjectID? {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyTranslatePIDToProcessObject,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var inPID = pid
        var outObjID = AudioObjectID(kAudioObjectUnknown)
        var outSize = UInt32(MemoryLayout<AudioObjectID>.size)
        let status = AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            UInt32(MemoryLayout<pid_t>.size),
            &inPID,
            &outSize,
            &outObjID
        )
        guard status == noErr, outObjID != kAudioObjectUnknown else { return nil }
        return outObjID
    }

    private static func readPID(for objectID: AudioObjectID) -> pid_t {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioProcessPropertyPID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var pid: pid_t = 0
        var size = UInt32(MemoryLayout<pid_t>.size)
        guard AudioObjectGetPropertyData(objectID, &address, 0, nil, &size, &pid) == noErr else {
            return 0
        }
        return pid
    }

    private static func readBundleID(for objectID: AudioObjectID) -> String {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioProcessPropertyBundleID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var cfStr: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(objectID, &address, 0, nil, &size, &cfStr) == noErr,
              let retained = cfStr?.takeRetainedValue() else {
            return ""
        }
        return retained as String
    }

    private static func readBoolProperty(
        for objectID: AudioObjectID,
        selector: AudioObjectPropertySelector
    ) -> Bool {
        var address = AudioObjectPropertyAddress(
            mSelector: selector,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(objectID, &address, 0, nil, &size, &value) == noErr else {
            return false
        }
        return value != 0
    }
}
