import Foundation

// MARK: - Update Configuration & Transport

public enum UpdateConfig {
    public static let publicReleaseRepo = "JasirVoriya/cozyting"
    public static let manifestPrimaryURL = URL(
        string: "https://raw.githubusercontent.com/JasirVoriya/cozyting/main/update.json"
    )!
    public static let releasesFallbackAPIURL = URL(
        string: "https://api.github.com/repos/JasirVoriya/cozyting/releases?per_page=30"
    )!
    public static let publicReleasesWebURL = URL(
        string: "https://github.com/JasirVoriya/cozyting/releases"
    )!

    public static let autoCheckCooldownMs: Int64 = 24 * 60 * 60 * 1000
    public static let userAgent = "CozyTing-Updater"
    public static let acceptHeader = "application/json, application/vnd.github+json"
    public static let connectTimeoutSeconds: TimeInterval = 8
    public static let readTimeoutSeconds: TimeInterval = 12
    public static let maxRedirects = 5
    public static let maxManifestBytes = 512 * 1024

    public static func isAllowedHTTPSURL(_ rawURL: String) -> Bool {
        let trimmed = rawURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              let host = components.host,
              !host.isEmpty else {
            return false
        }
        return scheme == "https"
    }

    public static func isAllowedHTTPSURL(_ url: URL) -> Bool {
        isAllowedHTTPSURL(url.absoluteString)
    }
}

public enum UpdateHTTPResponse: Sendable, Equatable {
    case ok(body: String, etag: String?)
    case notModified
    case httpError(code: Int, message: String)
}

public enum UpdateTransportError: Error, Sendable, Equatable {
    case nonHTTPSURL(String)
    case tooManyRedirects(Int)
    case responseTooLarge(Int)
    case invalidUTF8Response
    case networkError(String)
}

public protocol UpdateHTTPTransport: Sendable {
    func getText(
        url: URL,
        etag: String?,
        accept: String,
        maxBytes: Int
    ) async throws -> UpdateHTTPResponse
}

public enum UpdateCheckState: Sendable, Equatable {
    case idle
    case checking(manual: Bool)
    case ready(decision: UpdateDecision, checkedAtMs: Int64, fromCache: Bool)
    case failed(message: String, checkedAtMs: Int64)
}

// MARK: - Default URLSession HTTPS-Only Transport

public final class URLSessionUpdateTransport: UpdateHTTPTransport, @unchecked Sendable {
    private let configuration: URLSessionConfiguration

    public init(configuration: URLSessionConfiguration? = nil) {
        let cfg = (configuration?.copy() as? URLSessionConfiguration) ?? URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = UpdateConfig.connectTimeoutSeconds
        cfg.timeoutIntervalForResource = UpdateConfig.readTimeoutSeconds
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        self.configuration = cfg
    }

    public func getText(
        url: URL,
        etag: String? = nil,
        accept: String = UpdateConfig.acceptHeader,
        maxBytes: Int = UpdateConfig.maxManifestBytes
    ) async throws -> UpdateHTTPResponse {
        guard UpdateConfig.isAllowedHTTPSURL(url) else {
            throw UpdateTransportError.nonHTTPSURL(url.absoluteString)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = UpdateConfig.readTimeoutSeconds
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue(UpdateConfig.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(accept, forHTTPHeaderField: "Accept")
        if let cleanEtag = etag?.trimmingCharacters(in: .whitespacesAndNewlines), !cleanEtag.isEmpty {
            request.setValue(cleanEtag, forHTTPHeaderField: "If-None-Match")
        }

        let delegate = HTTPSRedirectGuardDelegate(maxRedirects: UpdateConfig.maxRedirects)
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            if let guardError = delegate.rejectedError {
                throw guardError
            }
            throw UpdateTransportError.networkError(error.localizedDescription)
        }

        if let guardError = delegate.rejectedError {
            throw guardError
        }

        guard let http = response as? HTTPURLResponse else {
            throw UpdateTransportError.networkError("Non-HTTP response")
        }

        switch http.statusCode {
        case 304:
            return .notModified
        case 200...299:
            if data.count > maxBytes {
                throw UpdateTransportError.responseTooLarge(maxBytes)
            }
            guard let body = String(data: data, encoding: .utf8) else {
                throw UpdateTransportError.invalidUTF8Response
            }
            let rawEtag = http.value(forHTTPHeaderField: "ETag")?.trimmingCharacters(in: .whitespacesAndNewlines)
            let newEtag = (rawEtag?.isEmpty == false) ? rawEtag : nil
            return .ok(body: body, etag: newEtag)
        default:
            return .httpError(code: http.statusCode, message: "HTTP \(http.statusCode)")
        }
    }
}

final class HTTPSRedirectGuardDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let maxRedirects: Int
    private let lock = NSLock()
    private var redirectCount = 0
    private var _rejectedError: UpdateTransportError?

    var rejectedError: UpdateTransportError? {
        lock.withLock { _rejectedError }
    }

    init(maxRedirects: Int) {
        self.maxRedirects = maxRedirects
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let targetURL = request.url, UpdateConfig.isAllowedHTTPSURL(targetURL) else {
            let blocked = request.url?.absoluteString ?? ""
            lock.withLock {
                _rejectedError = .nonHTTPSURL(blocked)
            }
            completionHandler(nil)
            return
        }

        let countAfterIncrement: Int = lock.withLock {
            redirectCount += 1
            return redirectCount
        }
        if countAfterIncrement > maxRedirects {
            lock.withLock {
                _rejectedError = .tooManyRedirects(countAfterIncrement)
            }
            completionHandler(nil)
            return
        }

        // Send If-None-Match strictly on hop 0 only.
        var sanitized = request
        sanitized.setValue(nil, forHTTPHeaderField: "If-None-Match")
        sanitized.setValue(UpdateConfig.userAgent, forHTTPHeaderField: "User-Agent")
        completionHandler(sanitized)
    }
}

// MARK: - UpdateService Actor (24h Cooldown + ETag 304 + L1 Evaluation)

public actor UpdateService {
    public typealias Clock = @Sendable () -> Int64
    public typealias Evaluator = @Sendable (
        _ currentVersion: String,
        _ channel: String,
        _ platform: String,
        _ abi: String,
        _ osVersion: String,
        _ ignoredTag: String,
        _ rawResponseJSON: String
    ) throws -> UpdateDecision

    private let preferences: PreferencesStore
    private let transport: any UpdateHTTPTransport
    private let evaluator: Evaluator
    private let clockMs: Clock
    private let currentVersionProvider: @Sendable () -> String
    private let platform: String
    private let abi: String
    private let osVersion: String

    public private(set) var state: UpdateCheckState = .idle
    private var isInFlight = false

    public init(
        preferences: PreferencesStore,
        transport: any UpdateHTTPTransport = URLSessionUpdateTransport(),
        evaluator: @escaping Evaluator = { cur, ch, pl, ab, os, ig, raw in
            try CozyTingCoreBridge.evaluateUpdate(
                currentVersion: cur,
                channel: ch,
                platform: pl,
                abi: ab,
                osVersion: os,
                ignoredTag: ig,
                rawResponseJSON: raw
            )
        },
        clockMs: @escaping Clock = { Int64(Date().timeIntervalSince1970 * 1000) },
        currentVersionProvider: @escaping @Sendable () -> String = {
            Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.9.12"
        },
        platform: String = "macos",
        abi: String = UpdateService.hostArchitecture(),
        osVersion: String = UpdateService.hostOSVersion()
    ) {
        self.preferences = preferences
        self.transport = transport
        self.evaluator = evaluator
        self.clockMs = clockMs
        self.currentVersionProvider = currentVersionProvider
        self.platform = platform
        self.abi = abi
        self.osVersion = osVersion
    }

    public static func hostArchitecture() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machine = withUnsafePointer(to: &systemInfo.machine) { ptr in
            ptr.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) {
                String(cString: $0)
            }
        }
        return machine.isEmpty ? "arm64" : machine
    }

    public static func hostOSVersion() -> String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }

    public func currentDisplayVersion() -> String {
        UpdateChannel.stripBuildSuffixes(currentVersionProvider())
    }

    public func dismissBanner(for tag: String) {
        preferences.dismissedUpdateBannerTag = tag
    }

    public func isBannerDismissed(for tag: String) -> Bool {
        let dismissed = preferences.dismissedUpdateBannerTag.trimmingCharacters(in: .whitespacesAndNewlines)
        let target = tag.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !dismissed.isEmpty, !target.isEmpty else { return false }
        return dismissed.caseInsensitiveCompare(target) == .orderedSame
    }

    public func activeBannerDecision() -> UpdateDecision? {
        guard case .ready(let decision, _, _) = state,
              decision.status == .updateAvailable,
              !isBannerDismissed(for: decision.latestTag) else {
            return nil
        }
        return decision
    }

    public func shouldShowUpdateBadge() -> Bool {
        guard case .ready(let decision, _, _) = state else { return false }
        return decision.status == .updateAvailable
    }

    @discardableResult
    public func ignoreVersion(_ tag: String) -> UpdateCheckState {
        preferences.ignoredUpdateTag = tag
        return reevaluateFromCache(manual: false)
    }

    @discardableResult
    public func reevaluateFromCache(manual: Bool = false) -> UpdateCheckState {
        let cached = preferences.cachedUpdateManifestJSON
        guard !cached.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return state
        }
        guard let decision = try? evaluateRaw(cached, respectIgnored: !manual),
              decision.status != .error else {
            return state
        }
        let next = UpdateCheckState.ready(
            decision: decision,
            checkedAtMs: preferences.lastUpdateCheckMs,
            fromCache: true
        )
        state = next
        return next
    }

    @discardableResult
    public func maybeAutoCheck() async -> UpdateCheckState {
        guard preferences.updateAutoCheck else { return state }
        let now = clockMs()
        let lastCheck = preferences.lastUpdateCheckMs
        let elapsed = now - lastCheck
        if lastCheck > 0, elapsed >= 0, elapsed < UpdateConfig.autoCheckCooldownMs {
            if state == .idle,
               !preferences.cachedUpdateManifestJSON.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return reevaluateFromCache(manual: false)
            }
            return state
        }
        return await check(manual: false)
    }

    @discardableResult
    public func checkManually() async -> UpdateCheckState {
        await check(manual: true)
    }

    @discardableResult
    public func check(manual: Bool) async -> UpdateCheckState {
        guard !isInFlight else { return state }
        isInFlight = true
        defer { isInFlight = false }

        state = .checking(manual: manual)
        let now = clockMs()
        var primaryError: String?

        // 1. Primary: Static update.json with ETag 304 support
        let cachedJSON = preferences.cachedUpdateManifestJSON
        let savedEtag = preferences.updateEtag.trimmingCharacters(in: .whitespacesAndNewlines)
        let sendEtag = (!cachedJSON.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !savedEtag.isEmpty)
            ? savedEtag
            : nil

        do {
            let response = try await transport.getText(
                url: UpdateConfig.manifestPrimaryURL,
                etag: sendEtag,
                accept: UpdateConfig.acceptHeader,
                maxBytes: UpdateConfig.maxManifestBytes
            )
            switch response {
            case .notModified:
                if !cachedJSON.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    let decision = try evaluateRaw(cachedJSON, respectIgnored: !manual)
                    if decision.status != .error {
                        preferences.lastUpdateCheckMs = now
                        let next = UpdateCheckState.ready(decision: decision, checkedAtMs: now, fromCache: true)
                        state = next
                        return next
                    }
                }
            case .ok(let body, let etag):
                let decision = try evaluateRaw(body, respectIgnored: !manual)
                if decision.status != .error {
                    preferences.cachedUpdateManifestJSON = body
                    preferences.updateEtag = etag ?? ""
                    preferences.lastUpdateCheckMs = now
                    let next = UpdateCheckState.ready(decision: decision, checkedAtMs: now, fromCache: false)
                    state = next
                    return next
                } else {
                    primaryError = decision.error
                }
            case .httpError(_, let message):
                primaryError = message
            }
        } catch {
            primaryError = String(describing: error)
        }

        // 2. Fallback: GitHub Releases API (up to 30 releases)
        do {
            let response = try await transport.getText(
                url: UpdateConfig.releasesFallbackAPIURL,
                etag: nil,
                accept: UpdateConfig.acceptHeader,
                maxBytes: UpdateConfig.maxManifestBytes
            )
            switch response {
            case .ok(let body, _):
                let decision = try evaluateRaw(body, respectIgnored: !manual)
                if decision.status != .error {
                    preferences.cachedUpdateManifestJSON = body
                    preferences.updateEtag = ""
                    preferences.lastUpdateCheckMs = now
                    let next = UpdateCheckState.ready(decision: decision, checkedAtMs: now, fromCache: false)
                    state = next
                    return next
                } else {
                    let next = UpdateCheckState.failed(
                        message: decision.error.isEmpty ? decision.errorCode : decision.error,
                        checkedAtMs: now
                    )
                    state = next
                    return next
                }
            case .httpError(let code, let message):
                let next = UpdateCheckState.failed(message: "HTTP \(code): \(message)", checkedAtMs: now)
                state = next
                return next
            case .notModified:
                break
            }
        } catch {
            let next = UpdateCheckState.failed(message: String(describing: error), checkedAtMs: now)
            state = next
            return next
        }

        let failed = UpdateCheckState.failed(
            message: primaryError ?? "Update feed unreachable",
            checkedAtMs: now
        )
        state = failed
        return failed
    }

    private func evaluateRaw(_ rawJSON: String, respectIgnored: Bool) throws -> UpdateDecision {
        let currentVersion = currentVersionProvider()
        let channel = preferences.resolvedUpdateChannel(currentVersionName: currentVersion).rawValue
        let ignored = respectIgnored ? preferences.ignoredUpdateTag : ""
        return try evaluator(
            currentVersion,
            channel,
            platform,
            abi,
            osVersion,
            ignored,
            rawJSON
        )
    }
}
