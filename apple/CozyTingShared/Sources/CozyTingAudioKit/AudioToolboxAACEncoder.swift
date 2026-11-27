import AudioToolbox
import CoreAudioTypes
import CozyTingCoreKit
import Foundation

/// Immutable metrics snapshot of the background AAC encoding & L1 push pipeline.
public struct AudioPipelineStats: Sendable, Equatable {
    public let encodedFrames: UInt64
    public let pushedFrames: UInt64
    public let droppedQueueFullFrames: UInt64
    public let ringOverruns: UInt64
    public let ringDroppedBytes: UInt64
    public let formatChanges: UInt64
    public let encoderErrors: UInt64
    public let pcmProbe: PcmProbeSnapshot
}

/// Hardware/system `AudioToolbox` AAC-LC encoder producing raw 48 kHz stereo 256 kbps Access Units.
///
/// Architecture (zero `#if os(...)`, shared across macOS 14.2+ and iOS 16+):
/// - Stage 1 (dynamic PCM normalizer): converts arbitrary Linear PCM inputs (44.1/48/96 kHz,
///   mono/stereo/multi-channel, Float32/Int16/Float64, interleaved or planar) into canonical
///   48 kHz stereo Float32 interleaved PCM and buffers into exact 1024-frame blocks.
/// - Stage 2 (AAC-LC encoder): encodes each 1024-frame canonical block via `AudioConverterRef`
///   into a single raw AAC-LC Access Unit (256 kbps, NO 7-byte ADTS header) suitable for
///   `cozyting_engine_push_aac`.
/// - Mid-stream format changes automatically rebuild Stage 1 cleanly without dropping the AAC
///   encoder or leaking `AudioConverterRef` handles.
public final class AudioToolboxAACEncoder: @unchecked Sendable {
    public static let canonicalChannels: UInt32 = AudioStreamFormat.canonicalChannelCount
    public static let canonicalFramesPerAU: Int = Int(AudioStreamFormat.canonicalFramesPerAccessUnit)
    public static let canonicalSamplesPerAU: Int = Int(AudioStreamFormat.canonicalFramesPerAccessUnit) * Int(AudioStreamFormat.canonicalChannelCount)

    private static let canonicalPCMASBD = AudioStreamBasicDescription(
        mSampleRate: AudioStreamFormat.canonicalSampleRate,
        mFormatID: kAudioFormatLinearPCM,
        mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
        mBytesPerPacket: UInt32(MemoryLayout<Float>.size) * AudioStreamFormat.canonicalChannelCount,
        mFramesPerPacket: 1,
        mBytesPerFrame: UInt32(MemoryLayout<Float>.size) * AudioStreamFormat.canonicalChannelCount,
        mChannelsPerFrame: AudioStreamFormat.canonicalChannelCount,
        mBitsPerChannel: 32,
        mReserved: 0
    )

    private static let canonicalAACASBD = AudioStreamBasicDescription(
        mSampleRate: AudioStreamFormat.canonicalSampleRate,
        mFormatID: kAudioFormatMPEG4AAC,
        mFormatFlags: UInt32(MPEG4ObjectID.AAC_LC.rawValue),
        mBytesPerPacket: 0,
        mFramesPerPacket: AudioStreamFormat.canonicalFramesPerAccessUnit,
        mBytesPerFrame: 0,
        mChannelsPerFrame: AudioStreamFormat.canonicalChannelCount,
        mBitsPerChannel: 0,
        mReserved: 0
    )

    private let bitrateBps: UInt32
    private var aacConverter: AudioConverterRef?
    private var maxOutputPacketSize: UInt32 = 1536
    private var aacOutputScratch: UnsafeMutablePointer<UInt8>

    private var pcmConverter: AudioConverterRef?
    private var currentInputASBD: AudioStreamBasicDescription?
    private var formatChangeCount: UInt64 = 0

    // Pre-allocated canonical 48 kHz stereo Float32 staging FIFO (avoids per-chunk reallocation).
    private var canonicalFIFO: [Float] = []
    private var canonicalReadOffset: Int = 0
    private var resampledScratch: [Float] = []
    private var outputSampleTime: Int64 = 0

    public init(bitrateBps: UInt32 = AudioStreamFormat.canonicalBitrateBps) throws {
        self.bitrateBps = bitrateBps
        self.aacOutputScratch = UnsafeMutablePointer<UInt8>.allocate(capacity: 4096)
        self.canonicalFIFO.reserveCapacity(Self.canonicalSamplesPerAU * 8)
        self.resampledScratch.reserveCapacity(Self.canonicalSamplesPerAU * 8)
        try createAACConverter()
    }

    deinit {
        destroyConverters()
        aacOutputScratch.deallocate()
    }

    public var formatChanges: UInt64 {
        formatChangeCount
    }

    /// Resets PCM resampler, staging FIFO, and AAC converter state (used on hot source switch).
    public func reset() {
        if let pcm = pcmConverter {
            AudioConverterReset(pcm)
        }
        if let aac = aacConverter {
            AudioConverterReset(aac)
        }
        canonicalFIFO.removeAll(keepingCapacity: true)
        canonicalReadOffset = 0
        outputSampleTime = 0
    }

    /// Encodes a framed PCM audio chunk (interleaved or non-interleaved) into 0..N raw AAC-LC AUs.
    public func encode(
        header: AudioChunkHeader,
        payload: UnsafePointer<UInt8>
    ) throws -> [EncodedAudioFrame] {
        try encode(
            asbd: header.asbd,
            frameCount: Int(header.frameCount),
            bufferCount: Int(header.bufferCount),
            payload: payload,
            payloadByteCount: Int(header.payloadByteCount)
        )
    }

    /// Encodes a Linear PCM buffer described by `asbd` into 0..N raw 48 kHz stereo AAC-LC AUs.
    public func encode(
        asbd: AudioStreamBasicDescription,
        frameCount: Int,
        bufferCount: Int = 1,
        payload: UnsafePointer<UInt8>,
        payloadByteCount: Int
    ) throws -> [EncodedAudioFrame] {
        guard frameCount > 0, payloadByteCount > 0 else { return [] }
        guard asbd.mSampleRate > 0, asbd.mChannelsPerFrame > 0, asbd.mBytesPerFrame > 0 else {
            throw AudioEncodingError.invalidInputFormat(
                sampleRate: asbd.mSampleRate,
                channels: asbd.mChannelsPerFrame
            )
        }

        if isCanonicalPCM(asbd) {
            let expectedBytes = frameCount * Int(Self.canonicalPCMASBD.mBytesPerFrame)
            let copyBytes = min(payloadByteCount, expectedBytes)
            let floatCount = copyBytes / MemoryLayout<Float>.size
            payload.withMemoryRebound(to: Float.self, capacity: floatCount) { floatPtr in
                canonicalFIFO.append(contentsOf: UnsafeBufferPointer(start: floatPtr, count: floatCount))
            }
        } else {
            try normalizeToCanonicalPCM(
                asbd: asbd,
                frameCount: frameCount,
                bufferCount: max(bufferCount, 1),
                payload: payload,
                payloadByteCount: payloadByteCount
            )
        }

        return try drainCanonicalFIFOToAAC()
    }

    /// Pads any remaining partial canonical PCM frame (< 1024 frames) with trailing silence and encodes it.
    public func flush() throws -> [EncodedAudioFrame] {
        let remainingSamples = canonicalFIFO.count - canonicalReadOffset
        guard remainingSamples > 0 else { return [] }
        let remainder = remainingSamples % Self.canonicalSamplesPerAU
        if remainder > 0 {
            canonicalFIFO.append(contentsOf: repeatElement(Float(0), count: Self.canonicalSamplesPerAU - remainder))
        }
        return try drainCanonicalFIFOToAAC()
    }

    // MARK: - Stage 1: Dynamic PCM Resampling & Channel Normalization

    private func isCanonicalPCM(_ asbd: AudioStreamBasicDescription) -> Bool {
        let isNonInterleaved = (asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0
        let isFloat = (asbd.mFormatFlags & kAudioFormatFlagIsFloat) != 0
        return abs(asbd.mSampleRate - AudioStreamFormat.canonicalSampleRate) < 0.01
            && asbd.mChannelsPerFrame == Self.canonicalChannels
            && asbd.mBitsPerChannel == 32
            && isFloat
            && !isNonInterleaved
            && asbd.mBytesPerFrame == Self.canonicalPCMASBD.mBytesPerFrame
    }

    private func isSameInputASBD(_ a: AudioStreamBasicDescription, _ b: AudioStreamBasicDescription) -> Bool {
        abs(a.mSampleRate - b.mSampleRate) < 0.01
            && a.mFormatID == b.mFormatID
            && a.mFormatFlags == b.mFormatFlags
            && a.mBytesPerFrame == b.mBytesPerFrame
            && a.mChannelsPerFrame == b.mChannelsPerFrame
            && a.mBitsPerChannel == b.mBitsPerChannel
    }

    private func ensurePCMConverter(for inputASBD: AudioStreamBasicDescription) throws -> AudioConverterRef {
        if let existing = pcmConverter, let prev = currentInputASBD, isSameInputASBD(prev, inputASBD) {
            return existing
        }

        if let existing = pcmConverter {
            AudioConverterDispose(existing)
            pcmConverter = nil
            formatChangeCount &+= 1
        } else if currentInputASBD != nil {
            formatChangeCount &+= 1
        }

        var inDesc = inputASBD
        var outDesc = Self.canonicalPCMASBD
        var converter: AudioConverterRef?
        let status = AudioConverterNew(&inDesc, &outDesc, &converter)
        guard status == noErr, let converter else {
            throw AudioEncodingError.converterCreationFailed(status: status)
        }

        var quality = kAudioConverterQuality_High
        _ = AudioConverterSetProperty(
            converter,
            kAudioConverterSampleRateConverterQuality,
            UInt32(MemoryLayout<UInt32>.size),
            &quality
        )

        // Configure explicit channel map for mono -> stereo or >2ch -> stereo downmix.
        if inputASBD.mChannelsPerFrame == 1 {
            var channelMap: [Int32] = [0, 0]
            _ = AudioConverterSetProperty(
                converter,
                kAudioConverterChannelMap,
                UInt32(channelMap.count * MemoryLayout<Int32>.size),
                &channelMap
            )
        } else if inputASBD.mChannelsPerFrame > 2 {
            var channelMap: [Int32] = [0, 1]
            _ = AudioConverterSetProperty(
                converter,
                kAudioConverterChannelMap,
                UInt32(channelMap.count * MemoryLayout<Int32>.size),
                &channelMap
            )
        }

        pcmConverter = converter
        currentInputASBD = inputASBD
        return converter
    }

    private final class PCMInputContext {
        let payload: UnsafePointer<UInt8>
        let payloadByteCount: Int
        let bytesPerFrame: Int
        let channelsPerFrame: UInt32
        let isNonInterleaved: Bool
        let bufferCount: Int
        let totalFrames: Int
        var consumed: Bool = false

        init(
            payload: UnsafePointer<UInt8>,
            payloadByteCount: Int,
            bytesPerFrame: Int,
            channelsPerFrame: UInt32,
            isNonInterleaved: Bool,
            bufferCount: Int,
            totalFrames: Int
        ) {
            self.payload = payload
            self.payloadByteCount = payloadByteCount
            self.bytesPerFrame = bytesPerFrame
            self.channelsPerFrame = channelsPerFrame
            self.isNonInterleaved = isNonInterleaved
            self.bufferCount = bufferCount
            self.totalFrames = totalFrames
        }
    }

    private func normalizeToCanonicalPCM(
        asbd: AudioStreamBasicDescription,
        frameCount: Int,
        bufferCount: Int,
        payload: UnsafePointer<UInt8>,
        payloadByteCount: Int
    ) throws {
        let converter = try ensurePCMConverter(for: asbd)
        let ratio = AudioStreamFormat.canonicalSampleRate / asbd.mSampleRate
        let maxOutFrames = max(Int(ceil(Double(frameCount) * ratio)) + 64, 256)
        let neededSamples = maxOutFrames * Int(Self.canonicalChannels)
        if resampledScratch.count < neededSamples {
            resampledScratch = [Float](repeating: 0, count: neededSamples)
        }

        let isNonInterleaved = (asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0
        let context = PCMInputContext(
            payload: payload,
            payloadByteCount: payloadByteCount,
            bytesPerFrame: Int(asbd.mBytesPerFrame),
            channelsPerFrame: asbd.mChannelsPerFrame,
            isNonInterleaved: isNonInterleaved,
            bufferCount: isNonInterleaved ? max(bufferCount, Int(asbd.mChannelsPerFrame)) : 1,
            totalFrames: frameCount
        )

        var ioOutputPackets = UInt32(maxOutFrames)
        let status: OSStatus = resampledScratch.withUnsafeMutableBytes { outRaw in
            var outBufferList = AudioBufferList(
                mNumberBuffers: 1,
                mBuffers: AudioBuffer(
                    mNumberChannels: Self.canonicalChannels,
                    mDataByteSize: UInt32(maxOutFrames) * Self.canonicalPCMASBD.mBytesPerFrame,
                    mData: outRaw.baseAddress
                )
            )
            return withExtendedLifetime(context) {
                let ctxPtr = Unmanaged.passUnretained(context).toOpaque()
                return AudioConverterFillComplexBuffer(
                    converter,
                    Self.pcmInputDataProc,
                    ctxPtr,
                    &ioOutputPackets,
                    &outBufferList,
                    nil
                )
            }
        }

        // `kAudioConverterErr_NoDataNow` (1000) is expected when our single chunk is exhausted.
        if status != noErr && status != Self.noMoreDataStatus {
            throw AudioEncodingError.conversionFailed(status: status)
        }

        let producedFrames = Int(ioOutputPackets)
        if producedFrames > 0 {
            let producedSamples = producedFrames * Int(Self.canonicalChannels)
            canonicalFIFO.append(contentsOf: resampledScratch[0..<producedSamples])
        }
    }

    private static let noMoreDataStatus: OSStatus = 1000

    private static let pcmInputDataProc: AudioConverterComplexInputDataProc = {
        _, ioNumberDataPackets, ioData, _, inUserData in
        guard let inUserData else {
            ioNumberDataPackets.pointee = 0
            return noMoreDataStatus
        }
        let ctx = Unmanaged<PCMInputContext>.fromOpaque(inUserData).takeUnretainedValue()
        if ctx.consumed || ctx.totalFrames <= 0 {
            ioNumberDataPackets.pointee = 0
            return noMoreDataStatus
        }
        ctx.consumed = true

        let abl = UnsafeMutableAudioBufferListPointer(ioData)
        if ctx.isNonInterleaved {
            let planes = min(abl.count, ctx.bufferCount)
            let bytesPerPlane = ctx.totalFrames * ctx.bytesPerFrame
            guard planes > 0, bytesPerPlane * planes <= ctx.payloadByteCount else {
                ioNumberDataPackets.pointee = 0
                return noMoreDataStatus
            }
            for i in 0..<planes {
                let offset = i * bytesPerPlane
                abl[i].mNumberChannels = 1
                abl[i].mDataByteSize = UInt32(bytesPerPlane)
                abl[i].mData = UnsafeMutableRawPointer(mutating: ctx.payload.advanced(by: offset))
            }
        } else {
            guard abl.count >= 1 else {
                ioNumberDataPackets.pointee = 0
                return noMoreDataStatus
            }
            let totalBytes = min(ctx.totalFrames * ctx.bytesPerFrame, ctx.payloadByteCount)
            abl[0].mNumberChannels = ctx.channelsPerFrame
            abl[0].mDataByteSize = UInt32(totalBytes)
            abl[0].mData = UnsafeMutableRawPointer(mutating: ctx.payload)
        }

        ioNumberDataPackets.pointee = UInt32(ctx.totalFrames)
        return noErr
    }

    // MARK: - Stage 2: Canonical 48 kHz Stereo Float32 -> Raw AAC-LC AU

    private func createAACConverter() throws {
        var inDesc = Self.canonicalPCMASBD
        var outDesc = Self.canonicalAACASBD
        var converter: AudioConverterRef?
        let status = AudioConverterNew(&inDesc, &outDesc, &converter)
        guard status == noErr, let converter else {
            throw AudioEncodingError.converterCreationFailed(status: status)
        }

        var bitrate = bitrateBps
        _ = AudioConverterSetProperty(
            converter,
            kAudioConverterEncodeBitRate,
            UInt32(MemoryLayout<UInt32>.size),
            &bitrate
        )

        var maxPktSize: UInt32 = 0
        var propSize = UInt32(MemoryLayout<UInt32>.size)
        if AudioConverterGetProperty(
            converter,
            kAudioConverterPropertyMaximumOutputPacketSize,
            &propSize,
            &maxPktSize
        ) == noErr, maxPktSize > 0 {
            self.maxOutputPacketSize = maxPktSize
            if maxPktSize > 4096 {
                aacOutputScratch.deallocate()
                aacOutputScratch = UnsafeMutablePointer<UInt8>.allocate(capacity: Int(maxPktSize))
            }
        }

        self.aacConverter = converter
    }

    private final class AACInputContext {
        let basePtr: UnsafePointer<Float>
        let byteCount: Int
        var consumed: Bool = false

        init(basePtr: UnsafePointer<Float>, byteCount: Int) {
            self.basePtr = basePtr
            self.byteCount = byteCount
        }
    }

    private static let aacInputDataProc: AudioConverterComplexInputDataProc = {
        _, ioNumberDataPackets, ioData, _, inUserData in
        guard let inUserData else {
            ioNumberDataPackets.pointee = 0
            return noMoreDataStatus
        }
        let ctx = Unmanaged<AACInputContext>.fromOpaque(inUserData).takeUnretainedValue()
        if ctx.consumed {
            ioNumberDataPackets.pointee = 0
            return noMoreDataStatus
        }
        ctx.consumed = true

        let abl = UnsafeMutableAudioBufferListPointer(ioData)
        guard abl.count >= 1 else {
            ioNumberDataPackets.pointee = 0
            return noMoreDataStatus
        }
        abl[0].mNumberChannels = AudioToolboxAACEncoder.canonicalChannels
        abl[0].mDataByteSize = UInt32(ctx.byteCount)
        abl[0].mData = UnsafeMutableRawPointer(mutating: ctx.basePtr)
        ioNumberDataPackets.pointee = UInt32(AudioToolboxAACEncoder.canonicalFramesPerAU)
        return noErr
    }

    private func drainCanonicalFIFOToAAC() throws -> [EncodedAudioFrame] {
        guard let aacConverter else {
            throw AudioEncodingError.converterCreationFailed(status: -1)
        }

        var frames: [EncodedAudioFrame] = []
        let blockSamples = Self.canonicalSamplesPerAU
        let blockBytes = blockSamples * MemoryLayout<Float>.size

        while (canonicalFIFO.count - canonicalReadOffset) >= blockSamples {
            let startIdx = canonicalReadOffset
            canonicalReadOffset += blockSamples

            var ioOutputPackets: UInt32 = 1
            var packetDesc = AudioStreamPacketDescription()
            var outBufferList = AudioBufferList(
                mNumberBuffers: 1,
                mBuffers: AudioBuffer(
                    mNumberChannels: Self.canonicalChannels,
                    mDataByteSize: maxOutputPacketSize,
                    mData: aacOutputScratch
                )
            )

            let status: OSStatus = canonicalFIFO.withUnsafeBufferPointer { buf in
                guard let base = buf.baseAddress else { return -1 }
                let ctx = AACInputContext(basePtr: base.advanced(by: startIdx), byteCount: blockBytes)
                return withExtendedLifetime(ctx) {
                    let ctxPtr = Unmanaged.passUnretained(ctx).toOpaque()
                    return AudioConverterFillComplexBuffer(
                        aacConverter,
                        Self.aacInputDataProc,
                        ctxPtr,
                        &ioOutputPackets,
                        &outBufferList,
                        &packetDesc
                    )
                }
            }

            if status != noErr && status != Self.noMoreDataStatus {
                throw AudioEncodingError.conversionFailed(status: status)
            }

            if ioOutputPackets > 0 {
                let byteLen = Int(packetDesc.mDataByteSize > 0 ? packetDesc.mDataByteSize : outBufferList.mBuffers.mDataByteSize)
                let startOffset = Int(packetDesc.mStartOffset)
                if byteLen > 0, startOffset >= 0, startOffset + byteLen <= Int(maxOutputPacketSize) {
                    let rawAU = Data(bytes: aacOutputScratch.advanced(by: startOffset), count: byteLen)
                    frames.append(EncodedAudioFrame(rawAccessUnit: rawAU, sampleTime: outputSampleTime))
                    outputSampleTime += Int64(Self.canonicalFramesPerAU)
                }
            }
        }

        // Compact staging FIFO when read offset advances substantially.
        if canonicalReadOffset > 0 {
            if canonicalReadOffset >= canonicalFIFO.count {
                canonicalFIFO.removeAll(keepingCapacity: true)
                canonicalReadOffset = 0
            } else if canonicalReadOffset >= blockSamples * 4 {
                canonicalFIFO.removeFirst(canonicalReadOffset)
                canonicalReadOffset = 0
            }
        }

        return frames
    }

    private func destroyConverters() {
        if let pcm = pcmConverter {
            AudioConverterDispose(pcm)
            pcmConverter = nil
        }
        if let aac = aacConverter {
            AudioConverterDispose(aac)
            aacConverter = nil
        }
        currentInputASBD = nil
    }
}

/// Dedicated background encoding worker draining `LockFreeRingBuffer` -> `PcmProbe` -> `AudioToolboxAACEncoder` -> L1 sink.
///
/// Enforces real-time discipline: producers call `enqueueAudioBufferList` (zero malloc, zero lock)
/// and this pipeline consumes chunks on a dedicated high-priority serial `DispatchQueue`.
public final class AudioEncodingPipeline: @unchecked Sendable {
    public typealias FrameSink = @Sendable (EncodedAudioFrame) -> PushAacStatus

    public let ringBuffer: LockFreeRingBuffer
    public let pcmProbe: PcmProbe

    private let encoder: AudioToolboxAACEncoder
    private let sink: FrameSink
    private let enableSilentKeepalive: Bool
    private let silentKeepaliveAU: Data?
    private let workerQueue = DispatchQueue(label: "com.cozyting.audio.aac-encoder", qos: .userInteractive)
    private let wakeupSemaphore = DispatchSemaphore(value: 0)
    private let isRunningFlag = AtomicFlag(false)
    private let isStoppingFlag = AtomicFlag(false)
    private let resetRequestedFlag = AtomicFlag(false)
    private let wakeupPendingFlag = AtomicFlag(false)

    private let encodedFramesCounter = AtomicCounter(0)
    private let pushedFramesCounter = AtomicCounter(0)
    private let droppedQueueFullCounter = AtomicCounter(0)
    private let encoderErrorsCounter = AtomicCounter(0)

    private let scratchCapacityBytes: Int
    private let scratchPayload: UnsafeMutablePointer<UInt8>

    public init(
        ringCapacityBytes: Int = LockFreeRingBuffer.defaultCapacityBytes,
        scratchCapacityBytes: Int = 256 * 1024,
        bitrateBps: UInt32 = AudioStreamFormat.canonicalBitrateBps,
        enableSilentKeepalive: Bool = true,
        sink: @escaping FrameSink
    ) throws {
        self.ringBuffer = LockFreeRingBuffer(capacityBytes: ringCapacityBytes)
        self.pcmProbe = PcmProbe()
        self.encoder = try AudioToolboxAACEncoder(bitrateBps: bitrateBps)
        self.enableSilentKeepalive = enableSilentKeepalive
        self.silentKeepaliveAU = enableSilentKeepalive ? Self.makeSilentKeepaliveAU(bitrateBps: bitrateBps) : nil
        self.sink = sink
        self.scratchCapacityBytes = max(scratchCapacityBytes, 64 * 1024)
        self.scratchPayload = UnsafeMutablePointer<UInt8>.allocate(capacity: self.scratchCapacityBytes)
    }

    private static func makeSilentKeepaliveAU(bitrateBps: UInt32) -> Data? {
        guard let tempEncoder = try? AudioToolboxAACEncoder(bitrateBps: bitrateBps) else {
            return nil
        }
        let silentASBD = AudioStreamBasicDescription(
            mSampleRate: AudioStreamFormat.canonicalSampleRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 8,
            mFramesPerPacket: 1,
            mBytesPerFrame: 8,
            mChannelsPerFrame: 2,
            mBitsPerChannel: 32,
            mReserved: 0
        )
        let zeros = [Float](repeating: 0, count: AudioToolboxAACEncoder.canonicalSamplesPerAU)
        let produced: [EncodedAudioFrame]? = zeros.withUnsafeBytes { raw in
            guard let ptr = raw.bindMemory(to: UInt8.self).baseAddress else { return nil }
            return try? tempEncoder.encode(
                asbd: silentASBD,
                frameCount: AudioToolboxAACEncoder.canonicalFramesPerAU,
                bufferCount: 1,
                payload: ptr,
                payloadByteCount: raw.count
            )
        }
        return produced?.first?.rawAccessUnit
    }

    deinit {
        stop()
        scratchPayload.deallocate()
    }

    public var isStopping: Bool {
        isStoppingFlag.value
    }

    /// Starts the dedicated encoder worker loop.
    public func start() {
        guard !isRunningFlag.value else { return }
        isStoppingFlag.value = false
        isRunningFlag.value = true
        workerQueue.async { [weak self] in
            self?.runLoop()
        }
    }

    /// Requests an asynchronous encoder + ring reset on source hot-switch (does not disconnect L1).
    public func requestReset() {
        resetRequestedFlag.value = true
        signalConsumer()
    }

    /// Real-time safe producer entry point (zero heap allocations, zero locks, zero I/O).
    @discardableResult
    public func enqueueAudioBufferList(
        header: AudioChunkHeader,
        bufferList: UnsafePointer<AudioBufferList>
    ) -> Bool {
        if isStoppingFlag.value { return false }
        let ok = ringBuffer.writeAudioBufferList(header: header, bufferList: bufferList)
        if ok {
            signalConsumer()
        }
        return ok
    }

    /// Marks stopping and synchronously drains/stops the background encoder loop (deadlock-free).
    public func stop() {
        isStoppingFlag.value = true
        signalConsumer()
        if isRunningFlag.value {
            workerQueue.sync {}
            isRunningFlag.value = false
        }
    }

    public func stats() -> AudioPipelineStats {
        AudioPipelineStats(
            encodedFrames: encodedFramesCounter.value,
            pushedFrames: pushedFramesCounter.value,
            droppedQueueFullFrames: droppedQueueFullCounter.value,
            ringOverruns: ringBuffer.overrunCount,
            ringDroppedBytes: ringBuffer.droppedBytes,
            formatChanges: encoder.formatChanges,
            encoderErrors: encoderErrorsCounter.value,
            pcmProbe: pcmProbe.snapshot()
        )
    }

    private func signalConsumer() {
        if !wakeupPendingFlag.value {
            wakeupPendingFlag.value = true
            wakeupSemaphore.signal()
        }
    }

    private func runLoop() {
        let auIntervalNs: UInt64 = 21_333_333 // 1024 / 48000 s
        let idleThresholdNs: UInt64 = 45_000_000 // ~2 AUs without real PCM triggers silent keepalive
        var lastRealPcmNs: UInt64 = 0
        var lastKeepaliveNs: UInt64 = 0
        var keepaliveSampleTime: Int64 = 0

        while !isStoppingFlag.value {
            wakeupPendingFlag.value = false

            if resetRequestedFlag.value {
                resetRequestedFlag.value = false
                ringBuffer.reset()
                encoder.reset()
                pcmProbe.reset()
                lastRealPcmNs = 0
                lastKeepaliveNs = 0
            }

            var drainedAny = false
            while !isStoppingFlag.value,
                  let header = ringBuffer.readAudioChunk(
                      into: scratchPayload,
                      maxPayloadBytes: scratchCapacityBytes
                  ) {
                drainedAny = true
                lastRealPcmNs = DispatchTime.now().uptimeNanoseconds
                lastKeepaliveNs = lastRealPcmNs
                _ = pcmProbe.analyze(header: header, payload: scratchPayload)
                do {
                    let frames = try encoder.encode(header: header, payload: scratchPayload)
                    for frame in frames {
                        encodedFramesCounter.increment()
                        let pushStatus = sink(frame)
                        switch pushStatus {
                        case .ok:
                            pushedFramesCounter.increment()
                        case .queueFull:
                            // Backpressure drop per apple/AGENTS.md §4.3 — never accumulate unbounded memory.
                            droppedQueueFullCounter.increment()
                        case .invalidOrStopped, .silentEngineNoFeed, .invalidBuffer, .unknown:
                            break
                        }
                    }
                } catch {
                    encoderErrorsCounter.increment()
                }
            }

            if !drainedAny && !isStoppingFlag.value {
                if enableSilentKeepalive, let silentAU = silentKeepaliveAU {
                    let nowNs = DispatchTime.now().uptimeNanoseconds
                    let sinceReal = lastRealPcmNs == 0 ? idleThresholdNs : nowNs &- lastRealPcmNs
                    let sinceKeepalive = lastKeepaliveNs == 0 ? auIntervalNs : nowNs &- lastKeepaliveNs
                    if sinceReal >= idleThresholdNs && sinceKeepalive >= auIntervalNs {
                        lastKeepaliveNs = nowNs
                        let frame = EncodedAudioFrame(rawAccessUnit: silentAU, sampleTime: keepaliveSampleTime)
                        keepaliveSampleTime &+= Int64(AudioToolboxAACEncoder.canonicalFramesPerAU)
                        encodedFramesCounter.increment()
                        let pushStatus = sink(frame)
                        switch pushStatus {
                        case .ok:
                            pushedFramesCounter.increment()
                        case .queueFull:
                            droppedQueueFullCounter.increment()
                        case .invalidOrStopped, .silentEngineNoFeed, .invalidBuffer, .unknown:
                            break
                        }
                        continue
                    }
                    _ = wakeupSemaphore.wait(timeout: .now() + .milliseconds(10))
                } else {
                    _ = wakeupSemaphore.wait(timeout: .now() + .milliseconds(20))
                }
            }
        }
    }
}
