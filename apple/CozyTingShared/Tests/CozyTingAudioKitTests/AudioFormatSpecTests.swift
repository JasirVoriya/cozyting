import Foundation
import Testing
@testable import CozyTingAudioKit

@Suite("AudioFormatSpec Tests")
struct AudioFormatSpecTests {
    @Test("Canonical MiPlay audio format matches 48kHz stereo 1024-sample 256kbps AAC-LC spec")
    func canonicalFormatConstants() {
        let format = AudioStreamFormat.canonical
        #expect(format.sampleRate == 48_000)
        #expect(format.channelCount == 2)
        #expect(format.framesPerAccessUnit == 1024)
        #expect(format.bitrateBps == 256_000)
        #expect(format.isCanonicalOutput == true)
        #expect(abs(format.accessUnitDurationMilliseconds - 21.3333333333) < 0.001)
    }

    @Test("Non-canonical sample rate reports false for isCanonicalOutput")
    func nonCanonicalFormatDetection() {
        let cdRate = AudioStreamFormat(sampleRate: 44_100, channelCount: 2)
        #expect(cdRate.isCanonicalOutput == false)
        #expect(cdRate != AudioStreamFormat.canonical)
    }
}
