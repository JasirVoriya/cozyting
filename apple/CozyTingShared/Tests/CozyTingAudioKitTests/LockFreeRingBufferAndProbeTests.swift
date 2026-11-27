import CoreAudioTypes
import Foundation
import Testing
@testable import CozyTingAudioKit

@Suite("LockFreeRingBuffer & PcmProbe Tests")
struct LockFreeRingBufferAndProbeTests {
    @Test("Ring buffer write/read handles wrap-around and overrun drop accounting")
    func ringWrapAroundAndOverrun() {
        let ring = LockFreeRingBuffer(capacityBytes: 128)
        #expect(ring.capacity == 128)

        let chunkA = Data((0..<80).map { UInt8($0 & 0xFF) })
        #expect(ring.write(chunkA) == true)
        #expect(ring.availableToRead == 80)

        var outA = [UInt8](repeating: 0, count: 60)
        let readA = outA.withUnsafeMutableBytes { ring.read(into: $0) }
        #expect(readA == 60)
        #expect(Data(outA) == chunkA.prefix(60))

        // Wrap-around write of 90 bytes into remaining 108 bytes.
        let chunkB = Data((100..<190).map { UInt8($0 & 0xFF) })
        #expect(ring.write(chunkB) == true)
        #expect(ring.availableToRead == 110)

        // Overrun: 40 bytes won't fit in 18 remaining bytes.
        let chunkOverflow = Data(repeating: 0xAA, count: 40)
        #expect(ring.write(chunkOverflow) == false)
        #expect(ring.overrunCount == 1)
        #expect(ring.droppedBytes == 40)

        var rest = [UInt8](repeating: 0, count: 110)
        let readRest = rest.withUnsafeMutableBytes { ring.read(into: $0) }
        #expect(readRest == 110)
        #expect(Data(rest.prefix(20)) == chunkA.suffix(20))
        #expect(Data(rest.suffix(90)) == chunkB)
        #expect(ring.availableToRead == 0)
    }

    @Test("Ring buffer AudioBufferList framed chunk round-trip preserves ASBD header")
    func ringAudioBufferListRoundTrip() {
        let ring = LockFreeRingBuffer(capacityBytes: 4096)
        let asbd = AudioStreamBasicDescription(
            mSampleRate: 48_000,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 8,
            mFramesPerPacket: 1,
            mBytesPerFrame: 8,
            mChannelsPerFrame: 2,
            mBitsPerChannel: 32,
            mReserved: 0
        )
        var samples: [Float] = (0..<128).map { Float($0) * 0.005 }
        let byteCount = samples.count * MemoryLayout<Float>.size
        let header = AudioChunkHeader(
            asbd: asbd,
            frameCount: 64,
            bufferCount: 1,
            payloadByteCount: UInt32(byteCount),
            sampleTime: 1024
        )

        let wrote = samples.withUnsafeMutableBytes { raw in
            var abl = AudioBufferList(
                mNumberBuffers: 1,
                mBuffers: AudioBuffer(
                    mNumberChannels: 2,
                    mDataByteSize: UInt32(byteCount),
                    mData: raw.baseAddress
                )
            )
            return ring.writeAudioBufferList(header: header, bufferList: &abl)
        }
        #expect(wrote == true)

        let scratch = UnsafeMutablePointer<UInt8>.allocate(capacity: 2048)
        defer { scratch.deallocate() }
        let decodedHeader = ring.readAudioChunk(into: scratch, maxPayloadBytes: 2048)
        #expect(decodedHeader == header)

        let recovered = scratch.withMemoryRebound(to: Float.self, capacity: samples.count) { ptr in
            Array(UnsafeBufferPointer(start: ptr, count: samples.count))
        }
        #expect(recovered == samples)
    }

    @Test("PcmProbe distinguishes silence from active 440Hz sine PCM")
    func pcmProbeSilenceAndSignal() {
        let probe = PcmProbe()
        let silence = [Float](repeating: 0, count: 1024 * 2)
        let s1 = silence.withUnsafeBufferPointer { buf in
            probe.analyzeInterleavedFloat32(samples: buf.baseAddress!, frameCount: 1024)
        }
        #expect(s1.isSilent == true)
        #expect(s1.silentFrames == 1024)
        #expect(s1.nonSilentFrames == 0)

        var sine = [Float](repeating: 0, count: 1024 * 2)
        for i in 0..<1024 {
            let v = sinf(Float(i) * 2.0 * .pi * 440.0 / 48_000.0) * 0.5
            sine[i * 2] = v
            sine[i * 2 + 1] = v
        }
        let s2 = sine.withUnsafeBufferPointer { buf in
            probe.analyzeInterleavedFloat32(samples: buf.baseAddress!, frameCount: 1024)
        }
        #expect(s2.isSilent == false)
        #expect(s2.peak > 0.45 && s2.peak <= 0.51)
        #expect(s2.rms > 0.30 && s2.rms < 0.40)
        #expect(s2.dbFS > -12.0 && s2.dbFS < -6.0)
        #expect(s2.totalFrames == 2048)
        #expect(s2.nonSilentFrames == 1024)
        #expect(s2.silentFrames == 1024)
    }
}
