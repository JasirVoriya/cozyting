import CoreAudioTypes
import CozyTingCoreKit
import Foundation
import Testing
@testable import CozyTingAudioKit

@Suite("AudioToolboxAACEncoder & Pipeline Tests")
struct AudioToolboxAACEncoderTests {
    private func makeSineFloat32Interleaved(
        sampleRate: Double,
        channels: Int,
        frameCount: Int,
        frequency: Double = 440.0
    ) -> [Float] {
        var out = [Float](repeating: 0, count: frameCount * channels)
        for i in 0..<frameCount {
            let sample = Float(sin(2.0 * .pi * frequency * Double(i) / sampleRate) * 0.4)
            for c in 0..<channels {
                out[i * channels + c] = sample
            }
        }
        return out
    }

    private func makeFloatASBD(sampleRate: Double, channels: UInt32, nonInterleaved: Bool = false) -> AudioStreamBasicDescription {
        let flags: AudioFormatFlags = nonInterleaved
            ? (kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked | kAudioFormatFlagIsNonInterleaved)
            : (kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked)
        let bytesPerFrame = UInt32(MemoryLayout<Float>.size) * (nonInterleaved ? 1 : channels)
        return AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: flags,
            mBytesPerPacket: bytesPerFrame,
            mFramesPerPacket: 1,
            mBytesPerFrame: bytesPerFrame,
            mChannelsPerFrame: channels,
            mBitsPerChannel: 32,
            mReserved: 0
        )
    }

    @Test("Encodes 48kHz stereo Float32 PCM into raw AAC-LC Access Units without ADTS headers")
    func encode48kStereoFloat32() throws {
        let encoder = try AudioToolboxAACEncoder()
        let asbd = makeFloatASBD(sampleRate: 48_000, channels: 2)
        let pcm = makeSineFloat32Interleaved(sampleRate: 48_000, channels: 2, frameCount: 4096)

        let frames = try pcm.withUnsafeBytes { raw in
            try encoder.encode(
                asbd: asbd,
                frameCount: 4096,
                payload: raw.baseAddress!.assumingMemoryBound(to: UInt8.self),
                payloadByteCount: raw.count
            )
        }

        #expect(frames.count == 4)
        for frame in frames {
            #expect(frame.rawAccessUnit.count >= 6)
            // Verify raw AAC AU (not prefixed with 7-byte ADTS syncword 0xFFFx).
            let b0 = frame.rawAccessUnit[0]
            let b1 = frame.rawAccessUnit[1]
            let looksLikeADTS = (b0 == 0xFF) && ((b1 & 0xF0) == 0xF0)
            #expect(looksLikeADTS == false)
        }
        // After AAC-LC priming frames, active 440 Hz sine produces non-silence compressed AUs.
        #expect((frames.last?.rawAccessUnit.count ?? 0) >= 32)
    }

    @Test("Resamples 44.1kHz stereo, 96kHz stereo, 48kHz mono, planar Float32, and Int16 PCM")
    func encodeDiverseInputFormats() throws {
        let encoder = try AudioToolboxAACEncoder()

        // 1. 44.1 kHz stereo Float32 interleaved
        let pcm441 = makeSineFloat32Interleaved(sampleRate: 44_100, channels: 2, frameCount: 4410)
        let frames441 = try pcm441.withUnsafeBytes { raw in
            try encoder.encode(
                asbd: makeFloatASBD(sampleRate: 44_100, channels: 2),
                frameCount: 4410,
                payload: raw.baseAddress!.assumingMemoryBound(to: UInt8.self),
                payloadByteCount: raw.count
            )
        }
        #expect(frames441.count >= 4)

        // 2. Dynamic mid-stream switch -> 96 kHz stereo Float32
        let pcm96 = makeSineFloat32Interleaved(sampleRate: 96_000, channels: 2, frameCount: 8192)
        let frames96 = try pcm96.withUnsafeBytes { raw in
            try encoder.encode(
                asbd: makeFloatASBD(sampleRate: 96_000, channels: 2),
                frameCount: 8192,
                payload: raw.baseAddress!.assumingMemoryBound(to: UInt8.self),
                payloadByteCount: raw.count
            )
        }
        #expect(frames96.count >= 3)

        // 3. 48 kHz mono Float32 -> stereo upmix
        let pcmMono = makeSineFloat32Interleaved(sampleRate: 48_000, channels: 1, frameCount: 2048)
        let framesMono = try pcmMono.withUnsafeBytes { raw in
            try encoder.encode(
                asbd: makeFloatASBD(sampleRate: 48_000, channels: 1),
                frameCount: 2048,
                payload: raw.baseAddress!.assumingMemoryBound(to: UInt8.self),
                payloadByteCount: raw.count
            )
        }
        #expect(framesMono.count >= 1)

        // 4. 48 kHz stereo non-interleaved (planar) Float32
        let left = makeSineFloat32Interleaved(sampleRate: 48_000, channels: 1, frameCount: 2048, frequency: 440)
        let right = makeSineFloat32Interleaved(sampleRate: 48_000, channels: 1, frameCount: 2048, frequency: 660)
        let planar = left + right
        let framesPlanar = try planar.withUnsafeBytes { raw in
            try encoder.encode(
                asbd: makeFloatASBD(sampleRate: 48_000, channels: 2, nonInterleaved: true),
                frameCount: 2048,
                bufferCount: 2,
                payload: raw.baseAddress!.assumingMemoryBound(to: UInt8.self),
                payloadByteCount: raw.count
            )
        }
        #expect(framesPlanar.count >= 1)

        // 5. 48 kHz stereo Int16 interleaved
        var pcmInt16 = [Int16](repeating: 0, count: 2048 * 2)
        for i in 0..<2048 {
            let s = Int16(sin(2.0 * .pi * 440.0 * Double(i) / 48_000.0) * 12000.0)
            pcmInt16[i * 2] = s
            pcmInt16[i * 2 + 1] = s
        }
        let int16ASBD = AudioStreamBasicDescription(
            mSampleRate: 48_000,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 4,
            mFramesPerPacket: 1,
            mBytesPerFrame: 4,
            mChannelsPerFrame: 2,
            mBitsPerChannel: 16,
            mReserved: 0
        )
        let framesInt16 = try pcmInt16.withUnsafeBytes { raw in
            try encoder.encode(
                asbd: int16ASBD,
                frameCount: 2048,
                payload: raw.baseAddress!.assumingMemoryBound(to: UInt8.self),
                payloadByteCount: raw.count
            )
        }
        #expect(framesInt16.count >= 1)
        #expect(encoder.formatChanges >= 4)

        let flushed = try encoder.flush()
        #expect(flushed.count >= 0)
    }

    @Test("AudioEncodingPipeline drains ring buffer, probes PCM, and counts -4 queueFull drops")
    func pipelineDrainsAndCountsQueueFullDrops() async throws {
        let dropEverySecond = AtomicCounter(0)
        let pipeline = try AudioEncodingPipeline { _ in
            let n = dropEverySecond.increment()
            return (n % 2 == 0) ? .queueFull : .ok
        }
        pipeline.start()

        let asbd = makeFloatASBD(sampleRate: 48_000, channels: 2)
        var pcm = makeSineFloat32Interleaved(sampleRate: 48_000, channels: 2, frameCount: 4096)
        let byteCount = pcm.count * MemoryLayout<Float>.size
        let header = AudioChunkHeader(
            asbd: asbd,
            frameCount: 4096,
            bufferCount: 1,
            payloadByteCount: UInt32(byteCount),
            sampleTime: 0
        )

        let enqueued = pcm.withUnsafeMutableBytes { raw in
            var abl = AudioBufferList(
                mNumberBuffers: 1,
                mBuffers: AudioBuffer(
                    mNumberChannels: 2,
                    mDataByteSize: UInt32(byteCount),
                    mData: raw.baseAddress
                )
            )
            return pipeline.enqueueAudioBufferList(header: header, bufferList: &abl)
        }
        #expect(enqueued == true)

        try await Task.sleep(for: .milliseconds(150))
        pipeline.stop()

        let stats = pipeline.stats()
        #expect(stats.encodedFrames == 4)
        #expect(stats.pushedFrames == 2)
        #expect(stats.droppedQueueFullFrames == 2)
        #expect(stats.pcmProbe.isSilent == false)
        #expect(stats.pcmProbe.nonSilentFrames == 4096)
    }
}
