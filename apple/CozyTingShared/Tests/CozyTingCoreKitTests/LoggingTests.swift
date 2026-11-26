import Foundation
import Testing
@testable import CozyTingCoreKit

@Suite("Logging & LogEntryParser Tests", .serialized)
struct LoggingTests {
    private func makeTempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cozyting-logging-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test("RuntimeLogger rotates to .1 when exceeding maxBytes")
    func runtimeLoggerRotation() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let logger = RuntimeLogger(directoryURL: dir, maxBytes: 80)
        logger.info(tag: "TestTag", String(repeating: "A", count: 70))
        logger.info(tag: "TestTag", String(repeating: "B", count: 70))
        logger.flush()

        #expect(FileManager.default.fileExists(atPath: logger.logFileURL.path))
        #expect(FileManager.default.fileExists(atPath: logger.backupFileURL.path))
        let backup = try String(contentsOf: logger.backupFileURL, encoding: .utf8)
        let current = try String(contentsOf: logger.logFileURL, encoding: .utf8)
        #expect(backup.contains("AAAA"))
        #expect(current.contains("BBBB"))
    }

    @Test("CastOpsLogger maintains 80-item ring, rotates, and mirrors to RuntimeLogger")
    func castOpsRingAndMirror() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let runtime = RuntimeLogger(directoryURL: dir)
        let ops = CastOpsLogger(directoryURL: dir, runtimeLogger: runtime, maxBytes: 120)

        for i in 1...85 {
            ops.record("op_\(i) speaker=192.0.2.34")
        }
        ops.flush()

        let recent = ops.recentEvents()
        #expect(recent.count == 80)
        #expect(recent.first?.contains("op_6 ") == true)
        #expect(recent.last?.contains("op_85 ") == true)

        let runtimeText = try String(contentsOf: runtime.logFileURL, encoding: .utf8)
        #expect(runtimeText.contains("I/CastOps: OP_EVENT"))
        #expect(FileManager.default.fileExists(atPath: ops.backupFileURL.path))
    }

    @Test("CozyTingLogManager initializes core.log, merges logs, exports, and clears")
    func logManagerLifecycle() async throws {
        try await CoreDiagLogTestLock.shared.withLock {
            let dir = try makeTempDir()
            defer {
                CozyTingCoreBridge.initLogFile(path: nil)
                try? FileManager.default.removeItem(at: dir)
            }

            let manager = CozyTingLogManager(baseDirectory: dir)
            manager.clearAll()
            manager.initializeCoreLog()
            manager.runtimeLogger.warn(tag: "Sync", "warn_event delay_ms=400")
            manager.castOpsLogger.record("CONNECT ip=192.0.2.34")
            manager.runtimeLogger.flush()

            let merged = manager.readMergedRuntimeLines()
            #expect(merged.count >= 2)
            let warnOnly = manager.readFilteredLines(filter: .warnError)
            #expect(warnOnly.allSatisfy { $0.contains(" W/") || $0.contains(" E/") })

            let exportURL = try manager.writeExportFile(to: dir, header: "CozyTing Test Export")
            let exportText = try String(contentsOf: exportURL, encoding: .utf8)
            #expect(exportText.contains("CozyTing Test Export"))
            #expect(exportText.contains("CONNECT ip=192.0.2.34"))

            #expect(manager.totalSizeBytes() > 0)
            manager.clearAll()
            #expect(manager.totalSizeBytes() == 0)
            #expect(manager.recentCoreLogs().isEmpty)
        }
    }

    @Test("LogEntryParser extracts metadata, key-value pairs, and collapses trailing JSON >= 48 chars")
    func logEntryParserFormatting() {
        let line = "2026-11-26 12:34:56.789 I/CozyTingCore: discovered name=Living Room Speaker count=2 {\"devices\":[{\"id\":\"192.0.2.34\",\"name\":\"Living Room\"}]}"
        let parsed = LogEntryParser.parseLine(id: 0, raw: line)

        #expect(parsed.date == "2026-11-26")
        #expect(parsed.time == "12:34:56.789")
        #expect(parsed.level == "I")
        #expect(parsed.tag == "CozyTingCore")
        #expect(parsed.isCore == true)
        #expect(parsed.eventTitle == "discovered")
        #expect(parsed.kvPairs == [
            LogKeyValuePair(key: "name", value: "Living Room Speaker"),
            LogKeyValuePair(key: "count", value: "2"),
        ])
        #expect(parsed.jsonSummary?.itemCount == 1)
        #expect(parsed.jsonSummary?.isArray == false)
        #expect((parsed.jsonRaw?.count ?? 0) >= LogEntryParser.minJSONCollapseLength)
        let pretty = LogEntryParser.prettyFormatJSON(parsed.jsonRaw ?? "")
        #expect(pretty.contains("\n"))
    }

    @Test("CastOpsLogger lines parse cleanly with LogEntryParser including bare HH:mm:ss.SSS and dated lines")
    func castOpsLoggerRoundTripParsing() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let manager = CozyTingLogManager(baseDirectory: dir)
        manager.castOpsLogger.record("ERROR OP_74 speaker=192.0.2.34")
        manager.castOpsLogger.record("WARN reconnect attempt=1")
        manager.castOpsLogger.record("CONNECT ip=192.0.2.34")
        manager.castOpsLogger.flush()

        let rawLines = manager.readCastOpsLines(maxLines: 10)
        #expect(rawLines.count == 3)

        let parsed = LogEntryParser.parseLines(rawLines, defaultIsOps: true)
        #expect(parsed.count == 3)

        #expect(parsed[0].date == nil)
        #expect(parsed[0].time != nil)
        #expect(parsed[0].level == "E")
        #expect(parsed[0].isOps == true)
        #expect(parsed[0].tag == "CastOps")

        #expect(parsed[1].date == nil)
        #expect(parsed[1].time != nil)
        #expect(parsed[1].level == "W")

        #expect(parsed[2].date == nil)
        #expect(parsed[2].time != nil)
        #expect(parsed[2].level == "I")

        let dated = LogEntryParser.parseLine(
            id: 99,
            raw: "2026-11-26 17:25:25.051 ERROR OP_74 speaker=192.0.2.34",
            defaultIsOps: true
        )
        #expect(dated.date == "2026-11-26")
        #expect(dated.time == "17:25:25.051")
        #expect(dated.level == "E")
    }

    @Test("readFilteredLines(.warnError) retains warnings/errors preceded by >3000 info lines")
    func warnErrorFilterRetainsErrorsAfterManyInfoLines() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let manager = CozyTingLogManager(baseDirectory: dir, runtimeMaxBytes: 1024 * 1024)
        let baseDate = Date(timeIntervalSince1970: 1_764_150_000)
        manager.runtimeLogger.error(tag: "Sync", "critical_failure code=42", date: baseDate)
        for i in 1...3100 {
            manager.runtimeLogger.info(
                tag: "Sync",
                "heartbeat_\(i)",
                date: baseDate.addingTimeInterval(Double(i) * 0.001)
            )
        }
        manager.runtimeLogger.flush()

        let warnErrors = manager.readFilteredLines(filter: .warnError, maxLines: 1500)
        #expect(warnErrors.count == 1)
        #expect(warnErrors.first?.contains("E/Sync: critical_failure code=42") == true)
    }

    @Test("RuntimeLogger sanitizes internal CR/LF in tag")
    func runtimeLoggerSanitizesTagNewlines() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let logger = RuntimeLogger(directoryURL: dir)
        logger.info(tag: "Bad\nTag\rName", "payload\nline")
        logger.flush()

        let content = try String(contentsOf: logger.logFileURL, encoding: .utf8)
        let lines = content.components(separatedBy: .newlines).filter { !$0.isEmpty }
        #expect(lines.count == 1)
        #expect(lines.first?.contains("I/Bad Tag Name: payload line") == true)
    }
}
