import CoreAudioTypes
import Foundation

/// Immutable telemetry snapshot produced by `PcmProbe`.
public struct PcmProbeSnapshot: Sendable, Equatable {
    public let peak: Float
    public let rms: Float
    public let dbFS: Float
    public let isSilent: Bool
    public let totalFrames: UInt64
    public let nonSilentFrames: UInt64
    public let silentFrames: UInt64
    public let sampleRate: Double
    public let channelCount: UInt32
    public let lastChunkFrames: UInt32

    public static let empty = PcmProbeSnapshot(
        peak: 0,
        rms: 0,
        dbFS: -120,
        isSilent: true,
        totalFrames: 0,
        nonSilentFrames: 0,
        silentFrames: 0,
        sampleRate: 0,
        channelCount: 0,
        lastChunkFrames: 0
    )
}

/// Thread-safe, zero-allocation PCM signal probe (peak, RMS, dBFS, silence vs active audio counters).
///
/// Used on the dedicated encoder thread to observe audio activity and honestly distinguish silent
/// DRM-protected streams or idle apps from active PCM audio without ever logging audio samples.
public final class PcmProbe: @unchecked Sendable {
    public static let defaultSilenceThreshold: Float = 1e-4 // -80 dBFS
    public static let minimumDbFS: Float = -120.0

    private let lock = NSLock()
    private let silenceThreshold: Float

    private var peak: Float = 0
    private var rms: Float = 0
    private var dbFS: Float = PcmProbe.minimumDbFS
    private var isSilent: Bool = true
    private var totalFrames: UInt64 = 0
    private var nonSilentFrames: UInt64 = 0
    private var silentFrames: UInt64 = 0
    private var sampleRate: Double = 0
    private var channelCount: UInt32 = 0
    private var lastChunkFrames: UInt32 = 0

    public init(silenceThreshold: Float = PcmProbe.defaultSilenceThreshold) {
        self.silenceThreshold = max(silenceThreshold, 0)
    }

    /// Analyzes raw PCM bytes described by `AudioChunkHeader` (Float32 or Int16, interleaved or planar).
    @discardableResult
    public func analyze(
        header: AudioChunkHeader,
        payload: UnsafePointer<UInt8>
    ) -> PcmProbeSnapshot {
        let frameCount = Int(header.frameCount)
        let channels = max(Int(header.channelsPerFrame), 1)
        guard frameCount > 0, header.payloadByteCount > 0 else {
            return snapshot()
        }

        let isFloat = (header.formatFlags & kAudioFormatFlagIsFloat) != 0
        let isSignedInt = (header.formatFlags & kAudioFormatFlagIsSignedInteger) != 0
        let sampleCount = frameCount * channels

        var localPeak: Float = 0
        var sumSquares: Double = 0
        var countedSamples = 0

        if isFloat && header.bitsPerChannel == 32 {
            let maxSamples = Int(header.payloadByteCount) / MemoryLayout<Float>.size
            let count = min(sampleCount, maxSamples)
            payload.withMemoryRebound(to: Float.self, capacity: count) { floatPtr in
                for i in 0..<count {
                    let s = floatPtr[i]
                    if s.isFinite {
                        let a = abs(s)
                        if a > localPeak { localPeak = a }
                        sumSquares += Double(s) * Double(s)
                        countedSamples += 1
                    }
                }
            }
        } else if isSignedInt && header.bitsPerChannel == 16 {
            let maxSamples = Int(header.payloadByteCount) / MemoryLayout<Int16>.size
            let count = min(sampleCount, maxSamples)
            let scale: Float = 1.0 / 32768.0
            payload.withMemoryRebound(to: Int16.self, capacity: count) { intPtr in
                for i in 0..<count {
                    let s = Float(intPtr[i]) * scale
                    let a = abs(s)
                    if a > localPeak { localPeak = a }
                    sumSquares += Double(s) * Double(s)
                    countedSamples += 1
                }
            }
        } else if isFloat && header.bitsPerChannel == 64 {
            let maxSamples = Int(header.payloadByteCount) / MemoryLayout<Double>.size
            let count = min(sampleCount, maxSamples)
            payload.withMemoryRebound(to: Double.self, capacity: count) { doublePtr in
                for i in 0..<count {
                    let v = doublePtr[i]
                    if v.isFinite {
                        let s = Float(v)
                        let a = abs(s)
                        if a > localPeak { localPeak = a }
                        sumSquares += v * v
                        countedSamples += 1
                    }
                }
            }
        }

        return recordMeasurement(
            localPeak: localPeak,
            sumSquares: sumSquares,
            countedSamples: countedSamples,
            frameCount: UInt32(frameCount),
            sampleRate: header.sampleRate,
            channelCount: header.channelsPerFrame
        )
    }

    /// Convenience analyzer for canonical interleaved Float32 samples.
    @discardableResult
    public func analyzeInterleavedFloat32(
        samples: UnsafePointer<Float>,
        frameCount: Int,
        channelCount: UInt32 = AudioStreamFormat.canonicalChannelCount,
        sampleRate: Double = AudioStreamFormat.canonicalSampleRate
    ) -> PcmProbeSnapshot {
        guard frameCount > 0, channelCount > 0 else { return snapshot() }
        let count = frameCount * Int(channelCount)
        var localPeak: Float = 0
        var sumSquares: Double = 0
        for i in 0..<count {
            let s = samples[i]
            if s.isFinite {
                let a = abs(s)
                if a > localPeak { localPeak = a }
                sumSquares += Double(s) * Double(s)
            }
        }
        return recordMeasurement(
            localPeak: localPeak,
            sumSquares: sumSquares,
            countedSamples: count,
            frameCount: UInt32(frameCount),
            sampleRate: sampleRate,
            channelCount: channelCount
        )
    }

    public func snapshot() -> PcmProbeSnapshot {
        lock.withLock {
            PcmProbeSnapshot(
                peak: peak,
                rms: rms,
                dbFS: dbFS,
                isSilent: isSilent,
                totalFrames: totalFrames,
                nonSilentFrames: nonSilentFrames,
                silentFrames: silentFrames,
                sampleRate: sampleRate,
                channelCount: channelCount,
                lastChunkFrames: lastChunkFrames
            )
        }
    }

    public func reset() {
        lock.withLock {
            peak = 0
            rms = 0
            dbFS = Self.minimumDbFS
            isSilent = true
            totalFrames = 0
            nonSilentFrames = 0
            silentFrames = 0
            sampleRate = 0
            channelCount = 0
            lastChunkFrames = 0
        }
    }

    private func recordMeasurement(
        localPeak: Float,
        sumSquares: Double,
        countedSamples: Int,
        frameCount: UInt32,
        sampleRate: Double,
        channelCount: UInt32
    ) -> PcmProbeSnapshot {
        let localRms: Float = countedSamples > 0
            ? Float(sqrt(sumSquares / Double(countedSamples)))
            : 0
        let localDbFS: Float = localRms > 1e-6
            ? max(Self.minimumDbFS, 20.0 * log10f(localRms))
            : Self.minimumDbFS
        let chunkSilent = localPeak <= silenceThreshold

        return lock.withLock {
            self.peak = localPeak
            self.rms = localRms
            self.dbFS = localDbFS
            self.isSilent = chunkSilent
            self.totalFrames &+= UInt64(frameCount)
            if chunkSilent {
                self.silentFrames &+= UInt64(frameCount)
            } else {
                self.nonSilentFrames &+= UInt64(frameCount)
            }
            self.sampleRate = sampleRate
            self.channelCount = channelCount
            self.lastChunkFrames = frameCount
            return PcmProbeSnapshot(
                peak: peak,
                rms: rms,
                dbFS: dbFS,
                isSilent: isSilent,
                totalFrames: totalFrames,
                nonSilentFrames: nonSilentFrames,
                silentFrames: silentFrames,
                sampleRate: sampleRate,
                channelCount: channelCount,
                lastChunkFrames: lastChunkFrames
            )
        }
    }
}
