import CryptoKit
import Foundation

public enum DMGDownloadError: Error, Sendable, Equatable {
    case nonHTTPSURL(String)
    case httpStatus(Int)
    case missingExpectedChecksum
    case checksumMismatch(expected: String, actual: String)
    case fileWriteFailed(String)
}

/// HTTPS-only streaming DMG downloader & `CryptoKit.SHA256` verifier (`M9`).
public enum DMGDownloader {
    /// Computes lowercase hex SHA-256 of a file using streaming 64 KB chunks.
    public static func sha256Hex(ofFile url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = handle.readData(ofLength: 64 * 1024)
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        let digest = hasher.finalize()
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Computes lowercase hex SHA-256 of in-memory data.
    public static func sha256Hex(of data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Parses standard `SHA256SUMS.txt` lines (`<64-hex>  <filename>`) into `[filename: sha256]`.
    public static func parseSHA256Sums(_ text: String) -> [String: String] {
        var map: [String: String] = [:]
        for rawLine in text.components(separatedBy: .newlines) {
            let parts = rawLine.trimmingCharacters(in: .whitespaces).split(whereSeparator: \.isWhitespace)
            guard parts.count >= 2 else { continue }
            let digest = String(parts[0]).lowercased()
            let filename = String(parts[parts.count - 1]).trimmingCharacters(in: CharacterSet(charactersIn: "*"))
            if digest.count == 64 && digest.allSatisfy({ $0.isHexDigit }) && !filename.isEmpty {
                map[filename] = digest
            }
        }
        return map
    }

    /// Downloads an HTTPS DMG asset into `destinationDirectory`, enforces SHA-256 verification
    /// (from manifest `expectedSHA256` or HTTPS `sha256SumsURL`), and returns the verified local URL.
    public static func downloadAndVerify(
        downloadURLString: String,
        assetName: String,
        expectedSHA256: String,
        sha256SumsURLString: String,
        destinationDirectory: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory,
        transport: any UpdateHTTPTransport = URLSessionUpdateTransport(),
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> URL {
        guard UpdateConfig.isAllowedHTTPSURL(downloadURLString),
              let url = URL(string: downloadURLString) else {
            throw DMGDownloadError.nonHTTPSURL(downloadURLString)
        }

        // 1. Resolve authoritative 64-hex SHA-256 first (fail-closed if unavailable).
        var expected = expectedSHA256.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if expected.count != 64,
           UpdateConfig.isAllowedHTTPSURL(sha256SumsURLString),
           let sumsURL = URL(string: sha256SumsURLString) {
            let resp = try await transport.getText(
                url: sumsURL,
                etag: nil,
                accept: "text/plain",
                maxBytes: 128 * 1024
            )
            if case .ok(let body, _) = resp {
                let parsed = parseSHA256Sums(body)
                expected = parsed[assetName] ?? parsed[url.lastPathComponent] ?? ""
            }
        }
        guard expected.count == 64 && expected.allSatisfy({ $0.isHexDigit }) else {
            throw DMGDownloadError.missingExpectedChecksum
        }

        // 2. Stream download over HTTPS with redirect guard.
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 20
        cfg.timeoutIntervalForResource = 600
        let delegate = HTTPSRedirectGuardDelegate(maxRedirects: UpdateConfig.maxRedirects)
        let session = URLSession(configuration: cfg, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        var req = URLRequest(url: url)
        req.setValue(UpdateConfig.userAgent, forHTTPHeaderField: "User-Agent")

        let (bytes, response) = try await session.bytes(for: req)
        if let guardErr = delegate.rejectedError {
            throw guardErr
        }
        guard let http = response as? HTTPURLResponse else {
            throw DMGDownloadError.httpStatus(0)
        }
        guard (200...299).contains(http.statusCode) else {
            throw DMGDownloadError.httpStatus(http.statusCode)
        }

        let totalExpected = response.expectedContentLength
        let safeFileName = assetName.isEmpty ? url.lastPathComponent : assetName
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("cozyting-dl-\(UUID().uuidString)-\(safeFileName)")
        FileManager.default.createFile(atPath: tempURL.path, contents: nil)

        var hasher = SHA256()
        var received: Int64 = 0
        var buffer = Data()
        buffer.reserveCapacity(64 * 1024)

        do {
            let handle = try FileHandle(forWritingTo: tempURL)
            defer { try? handle.close() }

            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count >= 32 * 1024 {
                    hasher.update(data: buffer)
                    try handle.write(contentsOf: buffer)
                    received += Int64(buffer.count)
                    buffer.removeAll(keepingCapacity: true)
                    if totalExpected > 0 {
                        onProgress?(min(max(Double(received) / Double(totalExpected), 0), 1))
                    }
                }
            }
            if !buffer.isEmpty {
                hasher.update(data: buffer)
                try handle.write(contentsOf: buffer)
                received += Int64(buffer.count)
                onProgress?(1.0)
            }
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            throw DMGDownloadError.fileWriteFailed(error.localizedDescription)
        }

        let actual = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard actual == expected else {
            try? FileManager.default.removeItem(at: tempURL)
            throw DMGDownloadError.checksumMismatch(expected: expected, actual: actual)
        }

        let finalURL = destinationDirectory.appendingPathComponent(safeFileName)
        do {
            try FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: finalURL.path) {
                try FileManager.default.removeItem(at: finalURL)
            }
            try FileManager.default.moveItem(at: tempURL, to: finalURL)
            return finalURL
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            throw DMGDownloadError.fileWriteFailed(error.localizedDescription)
        }
    }
}
