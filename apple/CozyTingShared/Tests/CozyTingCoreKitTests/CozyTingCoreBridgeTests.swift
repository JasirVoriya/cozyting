import Foundation
import Testing
@testable import CozyTingCoreKit

actor CoreDiagLogTestLock {
    static let shared = CoreDiagLogTestLock()
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func withLock<T: Sendable>(_ operation: @Sendable () async throws -> T) async rethrows -> T {
        while busy {
            await withCheckedContinuation { continuation in
                waiters.append(continuation)
            }
        }
        busy = true
        defer {
            busy = false
            if !waiters.isEmpty {
                let next = waiters.removeFirst()
                next.resume()
            }
        }
        return try await operation()
    }
}

@Suite("CozyTingCoreBridge & Models Tests", .serialized)
struct CozyTingCoreBridgeTests {
    @Test("Latency & auth mode C ABI wrappers match L1 rules")
    func latencyAndAuthMode() {
        #expect(CozyTingCoreBridge.delayForFrequency(mhz: 5180) == 800)
        #expect(CozyTingCoreBridge.delayForFrequency(mhz: 2412) == 1000)
        #expect(CozyTingCoreBridge.delayForFrequency(mhz: 0) == 1000)

        #expect(CozyTingCoreBridge.startupRunwayAUs(delayMs: 0) == 14)
        #expect(CozyTingCoreBridge.startupRunwayAUs(delayMs: 400) == 8)
        #expect(CozyTingCoreBridge.startupRunwayAUs(delayMs: 250) == 5)
        #expect(CozyTingCoreBridge.startupRunwayAUs(delayMs: 150) == 3)

        #expect(CozyTingCoreBridge.parseAuthMode("auto") == .auto)
        #expect(CozyTingCoreBridge.parseAuthMode("LEGACY") == .legacy)
        #expect(CozyTingCoreBridge.parseAuthMode("safety") == .safety)
        #expect(CozyTingCoreBridge.parseAuthMode("unknown") == nil)

        CozyTingCoreBridge.setAuthMode(.safety)
        CozyTingCoreBridge.setAuthMode(.auto)
    }

    @Test("Volume guard policy evaluation crosses threshold cleanly via L1")
    func volumeGuardEvaluation() throws {
        CozyTingCoreBridge.setVolumeGuardPolicy(enabled: true, safeLimit: 60)

        let allowWithinSafe = try CozyTingCoreBridge.evaluateVolumeGuard(
            enabled: true,
            safeLimit: 60,
            currentVolume: 30,
            targetVolume: 50
        )
        #expect(allowWithinSafe == .allow(volume: 50, safeLimit: 60))

        let requireConfirm = try CozyTingCoreBridge.evaluateVolumeGuard(
            enabled: true,
            safeLimit: 60,
            currentVolume: 30,
            targetVolume: 85
        )
        #expect(requireConfirm == .requireConfirm(currentVolume: 30, targetVolume: 85, safeLimit: 60))

        let allowAlreadyHigh = try CozyTingCoreBridge.evaluateVolumeGuard(
            enabled: true,
            safeLimit: 60,
            currentVolume: 75,
            targetVolume: 85
        )
        #expect(allowAlreadyHigh == .allow(volume: 85, safeLimit: 60))
    }

    @Test("Route state machine transitions via cozyting_route_apply_json")
    func routeStateMachine() throws {
        let devices = [
            Device(
                id: "192.0.2.34",
                name: "Living Room Speaker",
                kind: .speaker,
                castable: true,
                selected: false,
                detail: "",
                protocol: .miplay,
                nameFromDeviceInfo: true,
                roomName: "Living Room",
                roomInfoQueried: true
            ),
            Device(
                id: "192.0.2.99",
                name: "DLNA Box",
                kind: .speaker,
                castable: false,
                selected: false,
                detail: "Unsupported protocol",
                protocol: .dlna,
                nameFromDeviceInfo: false,
                roomName: "",
                roomInfoQueried: false
            ),
        ]

        let checkResult = try CozyTingCoreBridge.routeApply(
            devices: devices,
            action: .check,
            target: "192.0.2.34"
        )
        #expect(checkResult.state.casting == true)
        #expect(checkResult.state.thisDeviceSelected == false)
        #expect(checkResult.state.selectedSpeakerIds == ["192.0.2.34"])
        #expect(checkResult.effects.contains(.startCast(speakerIds: ["192.0.2.34"])))

        let rejectResult = try CozyTingCoreBridge.routeApply(
            devices: checkResult.state.devices,
            action: .check,
            target: "192.0.2.99"
        )
        #expect(rejectResult.effects.contains(.rejectNonCastable(id: "192.0.2.99", reason: "Unsupported protocol")))

        let selectThis = try CozyTingCoreBridge.routeApply(
            devices: checkResult.state.devices,
            action: .selectThis
        )
        #expect(selectThis.state.casting == false)
        #expect(selectThis.state.thisDeviceSelected == true)
        #expect(selectThis.effects.contains(.stopCastToThisDevice))
    }

    @Test("Engine lifecycle, pre-session stats, pause non-JSON, pushAac codes, and stop")
    func engineLifecycleAndStats() async throws {
        let silentEngine = CozyTingEngine(silent: true)
        #expect(!silentEngine.isStopped)

        // Silent engine returns -2 (silentEngineNoFeed) on pushAac
        let samplePayload = Data([0x21, 0x10, 0x04, 0x60])
        #expect(silentEngine.pushAac(samplePayload) == .silentEngineNoFeed)
        #expect(silentEngine.pushAac(nil, count: 0) == .invalidBuffer)

        // Pre-session pause returns plain text ("no session")
        let pauseText = try await silentEngine.pause()
        #expect(pauseText == "no session")

        // Pre-session resume / switchSource / setPlaybackDelay return L1 error JSON decoded as CoreBridgeError
        do {
            _ = try await silentEngine.setPlaybackDelay(delayMs: 400)
            Issue.record("Expected error when setting playback delay with no session")
        } catch let error as CoreBridgeError {
            #expect(error == .l1Error("no session"))
        }

        // Queue receiver -> stats returns preSessionQueued partial JSON
        let addRes = try await silentEngine.addReceiver(ip: "192.0.2.88")
        #expect(addRes.queued == true)
        #expect(addRes.ip == "192.0.2.88")

        let preStats = try await silentEngine.stats()
        guard case .preSessionQueued(let receivers) = preStats else {
            Issue.record("Expected preSessionQueued envelope, got \(preStats)")
            return
        }
        #expect(receivers.count == 1)
        #expect(receivers.first?.ip == "192.0.2.88")
        #expect(receivers.first?.uiState == .connecting)

        let volRes = try await silentEngine.setVolume(ip: "192.0.2.88", volume: 85, confirmed: true)
        #expect(volRes.volume == 85)
        #expect(volRes.clamped == false)

        let removed = await silentEngine.removeReceiver(ip: "192.0.2.88")
        #expect(removed == true)

        await silentEngine.stop()
        #expect(silentEngine.isStopped)
        #expect(silentEngine.pushAac(samplePayload) == .invalidOrStopped)

        // Non-silent engine accepts pushAac before stop
        let feedEngine = CozyTingEngine(silent: false)
        #expect(feedEngine.pushAac(samplePayload) == .ok)
        #expect(feedEngine.pushAac(nil, count: 0) == .invalidBuffer)
        await feedEngine.stop()
    }

    @Test("Version comparison and update evaluation JSON decoding via L1")
    func versionComparisonAndUpdateDecision() throws {
        #expect(CozyTingCoreBridge.compareVersions("0.9.11-release", "v0.9.12") == .lessThan)
        #expect(CozyTingCoreBridge.compareVersions("v0.9.12", "0.9.12") == .equal)
        #expect(CozyTingCoreBridge.compareVersions("0.9.13", "0.9.12") == .greaterThan)
        #expect(CozyTingCoreBridge.compareVersions("not-a-version", "0.9.12") == .invalid)

        let manifestJSON = """
        {
          "schema_version": 1,
          "channels": {
            "stable": {
              "tag": "v0.9.13",
              "prerelease": false,
              "published_at": "2026-11-26T00:00:00Z",
              "release_url": "https://github.com/JasirVoriya/cozyting/releases/tag/v0.9.13",
              "notes": "### Added\\n- Feature A",
              "ios": { "min_os": "16.0" }
            }
          }
        }
        """
        let decision = try CozyTingCoreBridge.evaluateUpdate(
            currentVersion: "0.9.12",
            channel: "stable",
            platform: "ios",
            abi: "",
            osVersion: "17.4",
            ignoredTag: "",
            rawResponseJSON: manifestJSON
        )
        #expect(decision.status == .updateAvailable)
        #expect(decision.latestTag == "v0.9.13")
        #expect(decision.latestVersion == "0.9.13")
        #expect(decision.versionsBehind == 1)
        #expect(decision.changelogEntries.count == 1)
    }

    @Test("EngineStatsEnvelope decodes full active session JSON")
    func fullActiveEngineStatsDecoding() throws {
        let json = """
        {
          "uptime_ms": 1234,
          "aus_sent": 50,
          "timeline_corrections": 1,
          "time_offset_us": 1700000000,
          "playback_delay_us": 400000,
          "paused": false,
          "latency": {
            "kind": "estimate",
            "definition": "sender_pipeline_estimate",
            "playback_delay_ms": 400,
            "startup_runway_ms": 170,
            "feed_flushed_aus_at_start": 0,
            "feed_flushed_est_ms": 0,
            "e2e_est_ms": 420,
            "schedule_ahead_ms": 395,
            "segments": ["capture_stamp", "encode_to_au"],
            "unknown_segments": ["speaker_playout_buffer"]
          },
          "receivers": [
            {
              "ip": "192.0.2.34",
              "auth": "legacy",
              "device_channel": null,
              "phase": "streaming",
              "ui_state": "streaming",
              "display_latency_ms": 420,
              "volume_original": 30,
              "volume_premute_ack": null,
              "volume_now": 35,
              "mirror_mode": 2,
              "state": 0,
              "setup_transport": "UDP",
              "time_offset_us": 1700000000,
              "timer_requests": 10,
              "video_latency_msgs": 0,
              "rtsp_runtime_requests": 1,
              "heartbeat_acks": 2,
              "aus_written": 50,
              "bytes_written": 12000,
              "runtime_time_offsets_sent": 0,
              "runtime_time_offsets_acked": 0,
              "pauses_acked": 0,
              "resumes_acked": 0,
              "volume_after_play_ack": null,
              "reconnects": 0,
              "joined_late": false,
              "aus_blackholed": 0,
              "channel_ack": null,
              "latency_e2e_est_ms": 420,
              "latency_schedule_ahead_ms": 395,
              "notifies": ["READY"],
              "error": null
            }
          ]
        }
        """
        let envelope = try JSONDecoder().decode(EngineStatsEnvelope.self, from: Data(json.utf8))
        guard case .active(let stats) = envelope else {
            Issue.record("Expected .active envelope")
            return
        }
        #expect(stats.uptimeMs == 1234)
        #expect(stats.latency.playbackDelayMs == 400)
        #expect(stats.receivers.first?.uiState == .streaming)
        #expect(stats.receivers.first?.displayLatencyMs == 420)
    }

    @Test("Non-silent engine connect to refused localhost throws promptly instead of hanging")
    func nonSilentConnectRefusedThrowsPromptly() async {
        await CoreDiagLogTestLock.shared.withLock {
            let engine = CozyTingEngine(silent: false)
            let start = ContinuousClock.now
            do {
                _ = try await engine.connect(ips: ["127.0.0.1"])
                Issue.record("Expected CoreBridgeError.l1Error when connecting to closed localhost port")
            } catch let error as CoreBridgeError {
                guard case .l1Error(let message) = error else {
                    Issue.record("Expected .l1Error, got \(error)")
                    await engine.stop()
                    return
                }
                #expect(!message.isEmpty)
            } catch {
                Issue.record("Unexpected error: \(error)")
            }
            let elapsed = ContinuousClock.now - start
            #expect(elapsed < .seconds(3))
            await engine.stop()
        }
    }

    @Test("stats() and stop() complete promptly while connect() is in flight")
    func statsAndStopCompletePromptlyDuringInFlightConnect() async throws {
        try await CoreDiagLogTestLock.shared.withLock {
            let engine = CozyTingEngine(silent: false)
            let connectTask = Task {
                try await engine.connect(ips: ["192.0.2.1"])
            }
            try await Task.sleep(for: .milliseconds(100))

            let statsStart = ContinuousClock.now
            let envelope = try await engine.stats()
            let statsElapsed = ContinuousClock.now - statsStart
            #expect(statsElapsed < .seconds(1))
            switch envelope {
            case .preSessionQueued, .active, .error:
                break
            }

            let stopStart = ContinuousClock.now
            await engine.stop()
            let stopElapsed = ContinuousClock.now - stopStart
            #expect(stopElapsed < .seconds(1))
            #expect(engine.isStopped)

            _ = connectTask
        }
    }
}
