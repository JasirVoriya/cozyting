import Foundation
import Testing
@testable import CozyTingCoreKit

private final class MockUpdateTransport: UpdateHTTPTransport, @unchecked Sendable {
    struct RecordedRequest: Sendable, Equatable {
        let url: URL
        let etag: String?
        let accept: String
        let maxBytes: Int
    }

    private let lock = NSLock()
    private var _requests: [RecordedRequest] = []
    private let handler: @Sendable (URL, String?) throws -> UpdateHTTPResponse

    init(handler: @escaping @Sendable (URL, String?) throws -> UpdateHTTPResponse) {
        self.handler = handler
    }

    var requests: [RecordedRequest] {
        lock.withLock { _requests }
    }

    func getText(
        url: URL,
        etag: String?,
        accept: String,
        maxBytes: Int
    ) async throws -> UpdateHTTPResponse {
        lock.withLock {
            _requests.append(RecordedRequest(url: url, etag: etag, accept: accept, maxBytes: maxBytes))
        }
        return try handler(url, etag)
    }
}

@Suite("UpdateService & HTTPS Guard Tests")
struct UpdateServiceTests {
    private func makeStore() -> (PreferencesStore, UserDefaults, String) {
        let suite = "com.cozyting.update.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return (PreferencesStore(userDefaults: defaults), defaults, suite)
    }

    private let sampleManifest = """
    {
      "schema_version": 1,
      "channels": {
        "stable": {
          "tag": "v0.9.13",
          "prerelease": false,
          "published_at": "2026-11-26T00:00:00Z",
          "release_url": "https://github.com/JasirVoriya/cozyting/releases/tag/v0.9.13",
          "notes": "### Added\\n- macOS M1",
          "ios": { "min_os": "16.0" }
        }
      }
    }
    """

    @Test("UpdateConfig enforces HTTPS-only URLs")
    func httpsOnlyURLCheck() {
        #expect(UpdateConfig.isAllowedHTTPSURL("https://raw.githubusercontent.com/JasirVoriya/cozyting/main/update.json"))
        #expect(!UpdateConfig.isAllowedHTTPSURL("http://raw.githubusercontent.com/JasirVoriya/cozyting/main/update.json"))
        #expect(!UpdateConfig.isAllowedHTTPSURL("file:///tmp/update.json"))
        #expect(!UpdateConfig.isAllowedHTTPSURL(""))
    }

    @Test("200 OK caches manifest + ETag; subsequent 304 re-evaluates cache")
    func okThenNotModifiedETag304() async {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        let transport = MockUpdateTransport { url, etag in
            #expect(url == UpdateConfig.manifestPrimaryURL)
            if etag == nil {
                return .ok(body: self.sampleManifest, etag: "\"etag-v1\"")
            }
            #expect(etag == "\"etag-v1\"")
            return .notModified
        }

        let service = UpdateService(
            preferences: store,
            transport: transport,
            clockMs: { 1_000_000 },
            currentVersionProvider: { "0.9.12" },
            platform: "ios",
            abi: "",
            osVersion: "17.4"
        )

        let first = await service.checkManually()
        guard case .ready(let decision1, _, let fromCache1) = first else {
            Issue.record("Expected ready state on 200 OK")
            return
        }
        #expect(fromCache1 == false)
        #expect(decision1.status == .updateAvailable)
        #expect(store.updateEtag == "\"etag-v1\"")
        #expect(store.cachedUpdateManifestJSON == sampleManifest)

        let second = await service.checkManually()
        guard case .ready(let decision2, _, let fromCache2) = second else {
            Issue.record("Expected ready state on 304")
            return
        }
        #expect(fromCache2 == true)
        #expect(decision2.latestTag == "v0.9.13")
        #expect(transport.requests.count == 2)
        #expect(transport.requests[1].etag == "\"etag-v1\"")
    }

    @Test("24h cooldown skips network and re-evaluates cached manifest")
    func cooldownSkipsNetwork() async {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        store.lastUpdateCheckMs = 1_000_000
        store.cachedUpdateManifestJSON = sampleManifest

        let transport = MockUpdateTransport { _, _ in
            Issue.record("Network should not be called within 24h cooldown")
            return .httpError(code: 500, message: "unexpected")
        }

        let service = UpdateService(
            preferences: store,
            transport: transport,
            clockMs: { 1_000_000 + 3_600_000 }, // 1h later (< 24h)
            currentVersionProvider: { "0.9.12" },
            platform: "ios",
            abi: "",
            osVersion: "17.4"
        )

        let state = await service.maybeAutoCheck()
        guard case .ready(let decision, _, let fromCache) = state else {
            Issue.record("Expected cache-evaluated ready state")
            return
        }
        #expect(fromCache == true)
        #expect(decision.latestTag == "v0.9.13")
        #expect(transport.requests.isEmpty)
    }

    @Test("Primary failure falls back to GitHub Releases API and clears ETag")
    func fallbackToGitHubReleases() async {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        store.updateEtag = "\"old-etag\""
        store.cachedUpdateManifestJSON = sampleManifest

        let ghReleasesJSON = """
        [
          {
            "tag_name": "v0.9.14",
            "prerelease": false,
            "draft": false,
            "published_at": "2026-11-26T01:00:00Z",
            "html_url": "https://github.com/JasirVoriya/cozyting/releases/tag/v0.9.14",
            "body": "### Fixed\\n- Fallback release",
            "assets": []
          }
        ]
        """

        let transport = MockUpdateTransport { url, _ in
            if url == UpdateConfig.manifestPrimaryURL {
                return .httpError(code: 503, message: "Service Unavailable")
            }
            #expect(url == UpdateConfig.releasesFallbackAPIURL)
            return .ok(body: ghReleasesJSON, etag: "\"gh-etag\"")
        }

        let service = UpdateService(
            preferences: store,
            transport: transport,
            clockMs: { 2_000_000 },
            currentVersionProvider: { "0.9.12" },
            platform: "ios",
            abi: "",
            osVersion: "17.4"
        )

        let state = await service.checkManually()
        guard case .ready(let decision, _, _) = state else {
            Issue.record("Expected ready state from fallback")
            return
        }
        #expect(decision.latestTag == "v0.9.14")
        #expect(store.updateEtag == "")
        #expect(transport.requests.count == 2)
    }

    @Test("Banner dismissal and ignore version update UI visibility flags")
    func bannerDismissAndIgnoreVersion() async {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        let transport = MockUpdateTransport { _, _ in
            .ok(body: self.sampleManifest, etag: nil)
        }

        let service = UpdateService(
            preferences: store,
            transport: transport,
            clockMs: { 5_000_000 },
            currentVersionProvider: { "0.9.12" },
            platform: "ios",
            abi: "",
            osVersion: "17.4"
        )

        _ = await service.checkManually()
        #expect(await service.shouldShowUpdateBadge() == true)
        #expect(await service.activeBannerDecision()?.latestTag == "v0.9.13")

        await service.dismissBanner(for: "v0.9.13")
        #expect(await service.activeBannerDecision() == nil)
        #expect(await service.shouldShowUpdateBadge() == true)

        let ignoredState = await service.ignoreVersion("v0.9.13")
        guard case .ready(let decision, _, _) = ignoredState else {
            Issue.record("Expected ready state after ignore")
            return
        }
        #expect(decision.status == .ignored)
        #expect(await service.shouldShowUpdateBadge() == false)
    }

    @Test("URLSessionUpdateTransport rejects http:// URLs immediately")
    func transportRejectsCleartextHTTP() async {
        let transport = URLSessionUpdateTransport()
        let httpURL = URL(string: "http://example.com/update.json")!
        do {
            _ = try await transport.getText(url: httpURL)
            Issue.record("Expected nonHTTPSURL error")
        } catch let error as UpdateTransportError {
            #expect(error == .nonHTTPSURL("http://example.com/update.json"))
        } catch {
            Issue.record("Unexpected error: \(error)")
        }
    }

    @Test("maybeAutoCheck skips network calls when updateAutoCheck is disabled")
    func maybeAutoCheckSkipsWhenDisabled() async {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removePersistentDomain(forName: suite) }

        store.updateAutoCheck = false
        let transport = MockUpdateTransport { _, _ in
            Issue.record("Network should not be called when updateAutoCheck is false")
            return .httpError(code: 500, message: "unexpected")
        }

        let service = UpdateService(
            preferences: store,
            transport: transport,
            clockMs: { 1_000_000 },
            currentVersionProvider: { "0.9.12" },
            platform: "ios",
            abi: "",
            osVersion: "17.4"
        )

        let state = await service.maybeAutoCheck()
        #expect(state == .idle)
        #expect(transport.requests.isEmpty)
    }

    @Test("URLSessionUpdateTransport copies caller-provided URLSessionConfiguration without mutating it")
    func transportCopiesConfiguration() {
        let custom = URLSessionConfiguration.default
        custom.timeoutIntervalForRequest = 42
        custom.requestCachePolicy = .returnCacheDataElseLoad
        _ = URLSessionUpdateTransport(configuration: custom)
        #expect(custom.timeoutIntervalForRequest == 42)
        #expect(custom.requestCachePolicy == .returnCacheDataElseLoad)
    }
}
