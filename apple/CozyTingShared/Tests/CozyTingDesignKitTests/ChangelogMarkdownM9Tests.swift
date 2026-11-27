import CozyTingCoreKit
import Foundation
import Testing
@testable import CozyTingDesignKit

@Suite("M9 Changelog Markdown & DMG SHA-256 Tests")
struct ChangelogMarkdownM9Tests {
    @Test("Parses callout, 5 semantic sections, nested bullets, and paragraphs cleanly")
    func structuredMarkdownParsing() {
        let md = """
        > Stable release summary for macOS 14.2+.

        ### Added
        - **Process Tap**: Native single-app capture
          - Supports Electron helper trees
        ### Changed
        - Faster latency switching
        ### Fixed
        - Clean teardown on exit
        ### Removed
        - Legacy workaround
        ### Security
        - SHA-256 DMG check
        """
        let blocks = ChangelogMarkdownParser.parse(md)
        #expect(blocks.contains(.callout("Stable release summary for macOS 14.2+.")))
        #expect(blocks.contains(.section(kind: .added, rawTitle: "Added")))
        #expect(blocks.contains(.section(kind: .changed, rawTitle: "Changed")))
        #expect(blocks.contains(.section(kind: .fixed, rawTitle: "Fixed")))
        #expect(blocks.contains(.section(kind: .removed, rawTitle: "Removed")))
        #expect(blocks.contains(.section(kind: .security, rawTitle: "Security")))
        #expect(blocks.contains(.bullet(level: 0, text: "**Process Tap**: Native single-app capture")))
        #expect(blocks.contains(.bullet(level: 1, text: "Supports Electron helper trees")))
    }

    @Test("DMGDownloader computes SHA-256 and parses SHA256SUMS.txt accurately")
    func sha256AndSumsParsing() throws {
        let sample = Data("cozyting-macos-dmg-test".utf8)
        let expected = DMGDownloader.sha256Hex(of: sample)
        #expect(expected.count == 64)

        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("cozyting-sha-\(UUID().uuidString).bin")
        try sample.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        #expect(try DMGDownloader.sha256Hex(ofFile: tmp) == expected)

        let sumsText = """
        \(expected)  cozyting-v0.10.0-macos-arm64.dmg
        not-a-hash  ignored.txt
        """
        let parsed = DMGDownloader.parseSHA256Sums(sumsText)
        #expect(parsed["cozyting-v0.10.0-macos-arm64.dmg"] == expected)
        #expect(parsed["ignored.txt"] == nil)
    }
}
