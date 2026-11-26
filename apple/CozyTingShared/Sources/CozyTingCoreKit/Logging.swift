import Foundation

// MARK: - Log Filtering & Rotating File Store

public enum LogLevel: Character, Sendable, Equatable {
    case verbose = "V"
    case debug = "D"
    case info = "I"
    case warn = "W"
    case error = "E"
    case assert = "A"
}

public enum LogFilter: Sendable, Equatable {
    case all
    case warnError
    case core
    case ops
}

/// Asynchronous rotating logger writing `runtime.log` / `runtime.log.1` (default 256 KB × 2).
public final class RuntimeLogger: @unchecked Sendable {
    public static let defaultMaxBytes: UInt64 = 256 * 1024
    public static let fileName = "runtime.log"

    public let logFileURL: URL
    public let backupFileURL: URL
    public let maxBytes: UInt64

    private let queue = DispatchQueue(label: "com.cozyting.core.runtime-logger", qos: .utility)
    private let formatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return df
    }()

    public init(
        directoryURL: URL,
        fileName: String = RuntimeLogger.fileName,
        maxBytes: UInt64 = RuntimeLogger.defaultMaxBytes
    ) {
        self.logFileURL = directoryURL.appendingPathComponent(fileName, isDirectory: false)
        self.backupFileURL = directoryURL.appendingPathComponent("\(fileName).1", isDirectory: false)
        self.maxBytes = maxBytes == 0 ? Self.defaultMaxBytes : maxBytes
    }

    public func log(level: LogLevel, tag: String, message: String, date: Date = Date()) {
        let cleanMessage = message
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
        let cleanTag = tag
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedTag = cleanTag.isEmpty ? "CozyTing" : cleanTag
        queue.async { [self] in
            let ts = formatter.string(from: date)
            let line = "\(ts) \(level.rawValue)/\(resolvedTag): \(cleanMessage)"
            Self.appendRotatingLine(
                fileURL: logFileURL,
                backupURL: backupFileURL,
                maxBytes: maxBytes,
                line: line
            )
        }
    }

    public func info(tag: String = "CozyTing", _ message: String, date: Date = Date()) {
        log(level: .info, tag: tag, message: message, date: date)
    }

    public func warn(tag: String = "CozyTing", _ message: String, date: Date = Date()) {
        log(level: .warn, tag: tag, message: message, date: date)
    }

    public func error(tag: String = "CozyTing", _ message: String, date: Date = Date()) {
        log(level: .error, tag: tag, message: message, date: date)
    }

    public func flush() {
        queue.sync {}
    }

    public func clear() {
        queue.sync {
            try? FileManager.default.removeItem(at: logFileURL)
            try? FileManager.default.removeItem(at: backupFileURL)
        }
    }

    static func appendRotatingLine(
        fileURL: URL,
        backupURL: URL,
        maxBytes: UInt64,
        line: String
    ) {
        let fm = FileManager.default
        let dir = fileURL.deletingLastPathComponent()
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        if let attrs = try? fm.attributesOfItem(atPath: fileURL.path),
           let fileSize = attrs[.size] as? UInt64,
           fileSize >= maxBytes {
            try? fm.removeItem(at: backupURL)
            try? fm.copyItem(at: fileURL, to: backupURL)
            try? Data().write(to: fileURL, options: .atomic)
        }

        guard let lineData = (line + "\n").data(using: .utf8) else { return }
        if fm.fileExists(atPath: fileURL.path) {
            if let handle = try? FileHandle(forWritingTo: fileURL) {
                defer { try? handle.close() }
                _ = try? handle.seekToEnd()
                try? handle.write(contentsOf: lineData)
            }
        } else {
            try? lineData.write(to: fileURL, options: .atomic)
        }
    }
}

/// Asynchronous rotating logger for cast operation events (`cast_ops.log`, 64 KB × 2 + 80-item ring),
/// mirroring every event to `RuntimeLogger` as `OP_EVENT`.
public final class CastOpsLogger: @unchecked Sendable {
    public static let defaultMaxBytes: UInt64 = 64 * 1024
    public static let maxRingEvents = 80
    public static let fileName = "cast_ops.log"

    public let logFileURL: URL
    public let backupFileURL: URL
    public let maxBytes: UInt64

    private weak var runtimeLogger: RuntimeLogger?
    private let queue = DispatchQueue(label: "com.cozyting.core.cast-ops-logger", qos: .utility)
    private let ringLock = NSLock()
    private var ring: [String] = []
    private let formatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "HH:mm:ss.SSS"
        return df
    }()

    public init(
        directoryURL: URL,
        runtimeLogger: RuntimeLogger? = nil,
        fileName: String = CastOpsLogger.fileName,
        maxBytes: UInt64 = CastOpsLogger.defaultMaxBytes
    ) {
        self.logFileURL = directoryURL.appendingPathComponent(fileName, isDirectory: false)
        self.backupFileURL = directoryURL.appendingPathComponent("\(fileName).1", isDirectory: false)
        self.runtimeLogger = runtimeLogger
        self.maxBytes = maxBytes == 0 ? Self.defaultMaxBytes : maxBytes
    }

    public func record(_ message: String, date: Date = Date()) {
        let clean = message
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
        let stamped: String = queue.sync {
            let ts = formatter.string(from: date)
            let entry = "\(ts) \(clean)"
            RuntimeLogger.appendRotatingLine(
                fileURL: logFileURL,
                backupURL: backupFileURL,
                maxBytes: maxBytes,
                line: entry
            )
            return entry
        }
        ringLock.withLock {
            ring.append(stamped)
            if ring.count > Self.maxRingEvents {
                ring.removeFirst(ring.count - Self.maxRingEvents)
            }
        }
        runtimeLogger?.info(tag: "CastOps", "OP_EVENT \(stamped)", date: date)
    }

    public func recentEvents() -> [String] {
        ringLock.withLock { ring }
    }

    public func flush() {
        queue.sync {}
        runtimeLogger?.flush()
    }

    public func clear() {
        ringLock.withLock { ring.removeAll() }
        queue.sync {
            try? FileManager.default.removeItem(at: logFileURL)
            try? FileManager.default.removeItem(at: backupFileURL)
        }
    }
}

/// Coordinates L1 `core.log`, `runtime.log`, and `cast_ops.log` under a single directory.
public final class CozyTingLogManager: @unchecked Sendable {
    public static let coreFileName = "core.log"
    public static let defaultMaxDisplayLines = 1500

    public let baseDirectory: URL
    public let runtimeLogger: RuntimeLogger
    public let castOpsLogger: CastOpsLogger
    public let coreLogURL: URL
    public let coreBackupURL: URL

    public init(
        baseDirectory: URL = CozyTingLogManager.defaultBaseDirectory(),
        runtimeMaxBytes: UInt64 = RuntimeLogger.defaultMaxBytes,
        opsMaxBytes: UInt64 = CastOpsLogger.defaultMaxBytes
    ) {
        self.baseDirectory = baseDirectory
        self.coreLogURL = baseDirectory.appendingPathComponent(Self.coreFileName, isDirectory: false)
        self.coreBackupURL = baseDirectory.appendingPathComponent("\(Self.coreFileName).1", isDirectory: false)
        self.runtimeLogger = RuntimeLogger(directoryURL: baseDirectory, maxBytes: runtimeMaxBytes)
        self.castOpsLogger = CastOpsLogger(
            directoryURL: baseDirectory,
            runtimeLogger: runtimeLogger,
            maxBytes: opsMaxBytes
        )
    }

    public static func defaultBaseDirectory() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return appSupport
            .appendingPathComponent("CozyTing", isDirectory: true)
            .appendingPathComponent("Logs", isDirectory: true)
    }

    public func initializeCoreLog(
        maxBytes: UInt64 = 256 * 1024,
        tzOffsetSecs: Int32 = Int32(TimeZone.current.secondsFromGMT())
    ) {
        try? FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        CozyTingCoreBridge.initLogFile(path: coreLogURL.path, maxBytes: maxBytes, tzOffsetSecs: tzOffsetSecs)
    }

    public func recentCoreLogs() -> String {
        CozyTingCoreBridge.recentLogs()
    }

    public func totalSizeBytes() -> UInt64 {
        let urls = [
            runtimeLogger.logFileURL,
            runtimeLogger.backupFileURL,
            coreLogURL,
            coreBackupURL,
            castOpsLogger.logFileURL,
            castOpsLogger.backupFileURL,
        ]
        let fm = FileManager.default
        return urls.reduce(0) { sum, url in
            let size = (try? fm.attributesOfItem(atPath: url.path)[.size] as? UInt64) ?? 0
            return sum + size
        }
    }

    public static func formatSize(_ bytes: UInt64) -> String {
        if bytes == 0 { return "0 B" }
        if bytes < 1024 { return "\(bytes) B" }
        let kb = Double(bytes) / 1024.0
        if kb < 1024.0 {
            return String(format: "%.1f KB", locale: Locale(identifier: "en_US_POSIX"), kb)
        }
        return String(format: "%.2f MB", locale: Locale(identifier: "en_US_POSIX"), kb / 1024.0)
    }

    private func readAllMergedRuntimeLines() -> [String] {
        runtimeLogger.flush()
        let swiftLines = readLines(runtimeLogger.backupFileURL) + readLines(runtimeLogger.logFileURL)
        let coreLines = readLines(coreBackupURL) + readLines(coreLogURL)
        let combined = swiftLines + coreLines
        guard !combined.isEmpty else { return [] }

        let keyed = combined.enumerated().map { (idx, line) in
            (index: idx, key: Self.extractTimestampKey(line) ?? "\u{FFFF}", line: line)
        }
        return keyed.sorted { lhs, rhs in
            if lhs.key != rhs.key {
                return lhs.key < rhs.key
            }
            return lhs.index < rhs.index
        }.map(\.line)
    }

    public func readMergedRuntimeLines(maxLines: Int = CozyTingLogManager.defaultMaxDisplayLines) -> [String] {
        guard maxLines > 0 else { return [] }
        let sorted = readAllMergedRuntimeLines()
        if sorted.count > maxLines {
            return Array(sorted.suffix(maxLines))
        }
        return sorted
    }

    public func readCastOpsLines(maxLines: Int = 500) -> [String] {
        guard maxLines > 0 else { return [] }
        castOpsLogger.flush()
        let lines = readLines(castOpsLogger.backupFileURL) + readLines(castOpsLogger.logFileURL)
        if lines.count > maxLines {
            return Array(lines.suffix(maxLines))
        }
        return lines
    }

    public func readFilteredLines(
        filter: LogFilter,
        maxLines: Int = CozyTingLogManager.defaultMaxDisplayLines
    ) -> [String] {
        guard maxLines > 0 else { return [] }
        switch filter {
        case .all:
            return readMergedRuntimeLines(maxLines: maxLines)
        case .warnError:
            let lines = readAllMergedRuntimeLines()
                .filter { $0.contains(" W/") || $0.contains(" E/") }
            return Array(lines.suffix(maxLines))
        case .core:
            let lines = readAllMergedRuntimeLines()
                .filter { $0.contains("/CozyTingCore:") }
            return Array(lines.suffix(maxLines))
        case .ops:
            return readCastOpsLines(maxLines: maxLines)
        }
    }

    public func clearAll() {
        runtimeLogger.clear()
        castOpsLogger.clear()
        CozyTingCoreBridge.clearLogs()
        let fm = FileManager.default
        for url in [
            runtimeLogger.logFileURL,
            runtimeLogger.backupFileURL,
            coreLogURL,
            coreBackupURL,
            castOpsLogger.logFileURL,
            castOpsLogger.backupFileURL,
        ] {
            try? fm.removeItem(at: url)
        }
    }

    public func buildExportText(header: String) -> String {
        let runtime = readMergedRuntimeLines(maxLines: 4000)
        let ops = readCastOpsLines(maxLines: 1000)
        var lines: [String] = []
        lines.append(header.trimmingCharacters(in: .newlines))
        lines.append("")
        lines.append("=== Runtime & Core Logs (\(runtime.count) lines) ===")
        if runtime.isEmpty {
            lines.append("(empty)")
        } else {
            lines.append(contentsOf: runtime)
        }
        lines.append("")
        lines.append("=== Cast Operations (cast_ops.log, \(ops.count) lines) ===")
        if ops.isEmpty {
            lines.append("(empty)")
        } else {
            lines.append(contentsOf: ops)
        }
        return lines.joined(separator: "\n") + "\n"
    }

    public func writeExportFile(
        to directoryURL: URL,
        header: String,
        date: Date = Date()
    ) throws -> URL {
        let exportDir = directoryURL.appendingPathComponent("logs", isDirectory: true)
        let fm = FileManager.default
        try fm.createDirectory(at: exportDir, withIntermediateDirectories: true)
        if let existing = try? fm.contentsOfDirectory(at: exportDir, includingPropertiesForKeys: nil) {
            for url in existing where url.lastPathComponent.hasPrefix("cozyting-logs-") {
                try? fm.removeItem(at: url)
            }
        }
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyyMMdd-HHmmss"
        let target = exportDir.appendingPathComponent("cozyting-logs-\(df.string(from: date)).txt")
        let content = buildExportText(header: header)
        try content.write(to: target, atomically: true, encoding: .utf8)
        return target
    }

    private func readLines(_ url: URL) -> [String] {
        guard let content = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return content
            .components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private static func extractTimestampKey(_ line: String) -> String? {
        guard let range = line.range(
            of: #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\b"#,
            options: .regularExpression
        ) else {
            return nil
        }
        return String(line[range])
    }
}

// MARK: - Structured Log Entry Parser (1:1 Android LogEntryParser Parity)

public struct LogJSONSummary: Sendable, Equatable {
    public let byteCount: Int
    public let itemCount: Int?
    public let isArray: Bool
}

public struct LogKeyValuePair: Sendable, Equatable {
    public let key: String
    public let value: String

    public init(key: String, value: String) {
        self.key = key
        self.value = value
    }
}

public struct ParsedLogEntry: Sendable, Equatable, Identifiable {
    public let id: Int
    public let raw: String
    public let date: String?
    public let time: String?
    public let level: Character
    public let tag: String
    public let isCore: Bool
    public let isOps: Bool
    public let eventTitle: String
    public let kvPairs: [LogKeyValuePair]
    public let plainBody: String?
    public let jsonRaw: String?
    public let jsonSummary: LogJSONSummary?
}

public enum LogEntryParser {
    public static let minJSONCollapseLength = 48

    private static let stdLineRegex = try! NSRegularExpression(
        pattern: #"^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEA])/([^:\s]+):\s*(.*)$"#
    )
    private static let opsTsRegex = try! NSRegularExpression(
        pattern: #"^(?:(\d{4}-\d{2}-\d{2})[T\s])?(\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\s+(.*)$"#
    )
    private static let kvKeyRegex = try! NSRegularExpression(
        pattern: #"(?:^|\s)([A-Za-z_][A-Za-z0-9_.-]*)=+"#
    )

    public static func parseLines(_ lines: [String], defaultIsOps: Bool = false) -> [ParsedLogEntry] {
        lines.enumerated().map { index, raw in
            parseLine(id: index, raw: raw, defaultIsOps: defaultIsOps)
        }
    }

    public static func parseLine(id: Int, raw: String, defaultIsOps: Bool = false) -> ParsedLogEntry {
        let trimmed = raw.replacingOccurrences(of: #"\s+$"#, with: "", options: .regularExpression)
        let nsRange = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)

        if let m = stdLineRegex.firstMatch(in: trimmed, range: nsRange),
           let dateR = Range(m.range(at: 1), in: trimmed),
           let timeR = Range(m.range(at: 2), in: trimmed),
           let lvlR = Range(m.range(at: 3), in: trimmed),
           let tagR = Range(m.range(at: 4), in: trimmed),
           let payloadR = Range(m.range(at: 5), in: trimmed) {
            let tag = String(trimmed[tagR])
            let levelChar = trimmed[lvlR].first ?? "I"
            return buildEntry(
                id: id,
                raw: raw,
                date: String(trimmed[dateR]),
                time: String(trimmed[timeR]),
                level: levelChar,
                tag: tag,
                isCore: tag == "CozyTingCore",
                isOps: defaultIsOps || tag.caseInsensitiveCompare("CastOps") == .orderedSame,
                payload: String(trimmed[payloadR])
            )
        }

        if let m = opsTsRegex.firstMatch(in: trimmed, range: nsRange),
           let timeR = Range(m.range(at: 2), in: trimmed),
           let payloadR = Range(m.range(at: 3), in: trimmed) {
            let dateStr = Range(m.range(at: 1), in: trimmed).map { String(trimmed[$0]) }
            let payload = String(trimmed[payloadR])
            let upper = payload.uppercased()
            let inferredLevel: Character
            if upper.contains("ERROR") || upper.contains("FAIL") {
                inferredLevel = "E"
            } else if upper.contains("WARN") {
                inferredLevel = "W"
            } else {
                inferredLevel = "I"
            }
            return buildEntry(
                id: id,
                raw: raw,
                date: dateStr,
                time: String(trimmed[timeR]),
                level: inferredLevel,
                tag: defaultIsOps ? "CastOps" : "Log",
                isCore: false,
                isOps: defaultIsOps,
                payload: payload
            )
        }

        return buildEntry(
            id: id,
            raw: raw,
            date: nil,
            time: nil,
            level: "I",
            tag: defaultIsOps ? "CastOps" : "",
            isCore: false,
            isOps: defaultIsOps,
            payload: trimmed
        )
    }

    public static func prettyFormatJSON(_ rawJSON: String) -> String {
        let s = rawJSON.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (s.hasPrefix("{") || s.hasPrefix("[")),
              let data = s.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data, options: []),
              let pretty = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys]),
              let result = String(data: pretty, encoding: .utf8) else {
            return s
        }
        return result
    }

    private static func buildEntry(
        id: Int,
        raw: String,
        date: String?,
        time: String?,
        level: Character,
        tag: String,
        isCore: Bool,
        isOps: Bool,
        payload: String
    ) -> ParsedLogEntry {
        let (prefixWithoutJSON, jsonRaw, jsonSummary) = extractTrailingJSON(payload)
        let (eventTitle, kvPairs, plainBody) = parsePayloadBody(prefixWithoutJSON, hasJSON: jsonRaw != nil)
        return ParsedLogEntry(
            id: id,
            raw: raw,
            date: date,
            time: time,
            level: level,
            tag: tag,
            isCore: isCore,
            isOps: isOps,
            eventTitle: eventTitle,
            kvPairs: kvPairs,
            plainBody: plainBody,
            jsonRaw: jsonRaw,
            jsonSummary: jsonSummary
        )
    }

    private static func extractTrailingJSON(_ payload: String) -> (String, String?, LogJSONSummary?) {
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= minJSONCollapseLength,
              let last = trimmed.last,
              last == "}" || last == "]" else {
            return (trimmed, nil, nil)
        }
        let expectedOpen: Character = (last == "}") ? "{" : "["
        var searchStart = trimmed.startIndex
        while let openIdx = trimmed[searchStart...].firstIndex(of: expectedOpen) {
            let candidate = String(trimmed[openIdx...]).trimmingCharacters(in: .whitespacesAndNewlines)
            if candidate.count >= minJSONCollapseLength,
               let summary = trySummarizeJSON(candidate) {
                var prefix = String(trimmed[..<openIdx])
                while let tail = prefix.last, tail == " " || tail == ":" || tail == "=" {
                    prefix.removeLast()
                }
                return (prefix, candidate, summary)
            }
            searchStart = trimmed.index(after: openIdx)
        }
        return (trimmed, nil, nil)
    }

    private static func trySummarizeJSON(_ candidate: String) -> LogJSONSummary? {
        guard let data = candidate.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data, options: []) else {
            return nil
        }
        let bytes = data.count
        if let arr = parsed as? [Any] {
            return LogJSONSummary(byteCount: bytes, itemCount: arr.count, isArray: true)
        }
        if let dict = parsed as? [String: Any] {
            let preferredKeys = ["devices", "items", "cacheable", "sinks", "entries"]
            let preferredCount = preferredKeys.compactMap { (dict[$0] as? [Any])?.count }.first
            return LogJSONSummary(
                byteCount: bytes,
                itemCount: preferredCount ?? dict.count,
                isArray: false
            )
        }
        return nil
    }

    private static func parsePayloadBody(
        _ body: String,
        hasJSON: Bool
    ) -> (String, [LogKeyValuePair], String?) {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return (hasJSON ? "json_payload" : "", [], nil)
        }

        let nsRange = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
        let matches = kvKeyRegex.matches(in: trimmed, range: nsRange)
        guard !matches.isEmpty else {
            return (trimmed, [], nil)
        }

        guard let firstKeyRange = Range(matches[0].range(at: 1), in: trimmed) else {
            return (trimmed, [], nil)
        }
        let prefix = String(trimmed[..<firstKeyRange.lowerBound])
            .trimmingCharacters(in: .whitespacesAndNewlines)

        var pairs: [LogKeyValuePair] = []
        for i in matches.indices {
            guard let keyRange = Range(matches[i].range(at: 1), in: trimmed),
                  let fullRange = Range(matches[i].range, in: trimmed) else {
                continue
            }
            let key = String(trimmed[keyRange])
            let valStart = fullRange.upperBound
            let valEnd: String.Index
            if i + 1 < matches.count,
               let nextFull = Range(matches[i + 1].range, in: trimmed) {
                valEnd = nextFull.lowerBound
            } else {
                valEnd = trimmed.endIndex
            }
            let value = String(trimmed[valStart..<valEnd]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty {
                pairs.append(LogKeyValuePair(key: key, value: value))
            }
        }

        if pairs.isEmpty {
            return (trimmed, [], nil)
        }

        if !prefix.isEmpty && prefix.count <= 64 {
            return (prefix, pairs, nil)
        } else if !prefix.isEmpty {
            return (String(prefix.prefix(64)), pairs, prefix)
        } else {
            let title = hasJSON ? "json_payload" : "\(pairs[0].key)=\(pairs[0].value)"
            let remaining = hasJSON ? pairs : Array(pairs.dropFirst())
            return (title, remaining, nil)
        }
    }
}
