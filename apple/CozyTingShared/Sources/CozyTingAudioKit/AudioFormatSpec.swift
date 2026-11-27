import CozyTingCoreKit
import Foundation

/// Canonical MiPlay output audio format specification shared across macOS and iOS (`CozyTingAudioKit`).
///
/// L1 `cozyting_engine_push_aac` expects raw 48 kHz stereo AAC-LC Access Units (1024 PCM samples
/// per frame, without 7-byte ADTS headers; L1 prepends ADTS/PES/MPEG-TS/RTP headers).
public struct AudioStreamFormat: Sendable, Equatable {
    public static let canonicalSampleRate: Double = 48_000
    public static let canonicalChannelCount: UInt32 = 2
    public static let canonicalFramesPerAccessUnit: UInt32 = 1024
    public static let canonicalBitrateBps: UInt32 = 256_000

    public static let canonical = AudioStreamFormat(
        sampleRate: canonicalSampleRate,
        channelCount: canonicalChannelCount,
        framesPerAccessUnit: canonicalFramesPerAccessUnit,
        bitrateBps: canonicalBitrateBps
    )

    public let sampleRate: Double
    public let channelCount: UInt32
    public let framesPerAccessUnit: UInt32
    public let bitrateBps: UInt32

    public init(
        sampleRate: Double,
        channelCount: UInt32,
        framesPerAccessUnit: UInt32 = AudioStreamFormat.canonicalFramesPerAccessUnit,
        bitrateBps: UInt32 = AudioStreamFormat.canonicalBitrateBps
    ) {
        self.sampleRate = sampleRate
        self.channelCount = channelCount
        self.framesPerAccessUnit = framesPerAccessUnit
        self.bitrateBps = bitrateBps
    }

    public var accessUnitDurationSeconds: Double {
        guard sampleRate > 0 else { return 0 }
        return Double(framesPerAccessUnit) / sampleRate
    }

    public var accessUnitDurationMilliseconds: Double {
        accessUnitDurationSeconds * 1000.0
    }

    public var isCanonicalOutput: Bool {
        sampleRate == Self.canonicalSampleRate
            && channelCount == Self.canonicalChannelCount
            && framesPerAccessUnit == Self.canonicalFramesPerAccessUnit
            && bitrateBps == Self.canonicalBitrateBps
    }
}

public struct EncodedAudioFrame: Sendable, Equatable {
    public let rawAccessUnit: Data
    public let sampleTime: Int64

    public init(rawAccessUnit: Data, sampleTime: Int64) {
        self.rawAccessUnit = rawAccessUnit
        self.sampleTime = sampleTime
    }
}

public enum AudioEncodingError: Error, Sendable, Equatable {
    case invalidInputFormat(sampleRate: Double, channels: UInt32)
    case converterCreationFailed(status: Int32)
    case conversionFailed(status: Int32)
}
