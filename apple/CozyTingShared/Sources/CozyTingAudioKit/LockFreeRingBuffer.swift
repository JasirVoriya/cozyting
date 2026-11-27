import AudioToolbox
internal import CozyTingAudioAtomics
import CoreAudioTypes
import Foundation

/// Lock-free C11 atomic boolean flag safe for real-time audio callback threads (`macOS 14.2+` / `iOS 16+`).
public final class AtomicFlag: @unchecked Sendable {
    private let storage: UnsafeMutablePointer<CTAtomicBool>

    public init(_ initialValue: Bool = false) {
        let ptr = UnsafeMutablePointer<CTAtomicBool>.allocate(capacity: 1)
        ct_atomic_bool_init(ptr, initialValue)
        self.storage = ptr
    }

    deinit {
        storage.deallocate()
    }

    public var value: Bool {
        get { ct_atomic_bool_load_acquire(storage) }
        set { ct_atomic_bool_store_release(storage, newValue) }
    }
}

/// Lock-free C11 atomic 64-bit counter safe for real-time audio callback threads.
public final class AtomicCounter: @unchecked Sendable {
    private let storage: UnsafeMutablePointer<CTAtomicUInt64>

    public init(_ initialValue: UInt64 = 0) {
        let ptr = UnsafeMutablePointer<CTAtomicUInt64>.allocate(capacity: 1)
        ct_atomic_u64_init(ptr, initialValue)
        self.storage = ptr
    }

    deinit {
        storage.deallocate()
    }

    public var value: UInt64 {
        get { ct_atomic_u64_load_acquire(storage) }
        set { ct_atomic_u64_store_release(storage, newValue) }
    }

    @discardableResult
    public func increment(by delta: UInt64 = 1) -> UInt64 {
        ct_atomic_u64_fetch_add_relaxed(storage, delta) &+ delta
    }
}

/// Fixed-layout 48-byte framing header serialized into `LockFreeRingBuffer` alongside each `AudioBufferList` chunk.
///
/// Propagates the exact Core Audio `AudioStreamBasicDescription` per callback chunk so the background
/// encoder thread can detect mid-stream format changes deterministically with zero locks or allocations.
public struct AudioChunkHeader: Sendable, Equatable {
    public static let encodedByteCount = 48

    public let sampleRate: Double
    public let formatFlags: UInt32
    public let bytesPerFrame: UInt32
    public let channelsPerFrame: UInt32
    public let bitsPerChannel: UInt32
    public let frameCount: UInt32
    public let bufferCount: UInt32
    public let payloadByteCount: UInt32
    public let sampleTime: Int64

    public init(
        sampleRate: Double,
        formatFlags: UInt32,
        bytesPerFrame: UInt32,
        channelsPerFrame: UInt32,
        bitsPerChannel: UInt32,
        frameCount: UInt32,
        bufferCount: UInt32,
        payloadByteCount: UInt32,
        sampleTime: Int64
    ) {
        self.sampleRate = sampleRate
        self.formatFlags = formatFlags
        self.bytesPerFrame = bytesPerFrame
        self.channelsPerFrame = channelsPerFrame
        self.bitsPerChannel = bitsPerChannel
        self.frameCount = frameCount
        self.bufferCount = bufferCount
        self.payloadByteCount = payloadByteCount
        self.sampleTime = sampleTime
    }

    public init(
        asbd: AudioStreamBasicDescription,
        frameCount: UInt32,
        bufferCount: UInt32,
        payloadByteCount: UInt32,
        sampleTime: Int64
    ) {
        self.sampleRate = asbd.mSampleRate
        self.formatFlags = asbd.mFormatFlags
        self.bytesPerFrame = asbd.mBytesPerFrame
        self.channelsPerFrame = asbd.mChannelsPerFrame
        self.bitsPerChannel = asbd.mBitsPerChannel
        self.frameCount = frameCount
        self.bufferCount = bufferCount
        self.payloadByteCount = payloadByteCount
        self.sampleTime = sampleTime
    }

    public var asbd: AudioStreamBasicDescription {
        AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: formatFlags,
            mBytesPerPacket: bytesPerFrame,
            mFramesPerPacket: 1,
            mBytesPerFrame: bytesPerFrame,
            mChannelsPerFrame: channelsPerFrame,
            mBitsPerChannel: bitsPerChannel,
            mReserved: 0
        )
    }

    fileprivate typealias PackedTuple = (
        UInt64, UInt64, UInt32, UInt32, UInt32, UInt32, UInt32, UInt32, UInt32, UInt32
    )

    fileprivate func pack(into tuple: inout PackedTuple) {
        tuple = (
            sampleRate.bitPattern.littleEndian,
            UInt64(bitPattern: sampleTime).littleEndian,
            formatFlags.littleEndian,
            bytesPerFrame.littleEndian,
            channelsPerFrame.littleEndian,
            bitsPerChannel.littleEndian,
            frameCount.littleEndian,
            bufferCount.littleEndian,
            payloadByteCount.littleEndian,
            0
        )
    }

    fileprivate init(unpacking tuple: PackedTuple) {
        self.sampleRate = Double(bitPattern: UInt64(littleEndian: tuple.0))
        self.sampleTime = Int64(bitPattern: UInt64(littleEndian: tuple.1))
        self.formatFlags = UInt32(littleEndian: tuple.2)
        self.bytesPerFrame = UInt32(littleEndian: tuple.3)
        self.channelsPerFrame = UInt32(littleEndian: tuple.4)
        self.bitsPerChannel = UInt32(littleEndian: tuple.5)
        self.frameCount = UInt32(littleEndian: tuple.6)
        self.bufferCount = UInt32(littleEndian: tuple.7)
        self.payloadByteCount = UInt32(littleEndian: tuple.8)
    }
}

/// Fixed-capacity single-producer single-consumer (SPSC) lock-free ring buffer.
///
/// Designed for Core Audio real-time `IOProc` callbacks:
/// - Pre-allocates all storage once in `init` and frees in `deinit`.
/// - Zero `malloc`, zero locks, zero Swift collection resizing during read/write.
/// - Uses C11 `<stdatomic.h>` acquire/release indices for memory-ordered SPSC handoff.
public final class LockFreeRingBuffer: @unchecked Sendable {
    public static let minimumCapacityBytes = 64
    public static let defaultCapacityBytes = 512 * 1024

    public let capacity: Int
    private let mask: UInt64
    private let buffer: UnsafeMutablePointer<UInt8>
    private let atomicBlock: UnsafeMutableRawPointer
    private let writeIndexPtr: UnsafeMutablePointer<CTAtomicUInt64>
    private let readIndexPtr: UnsafeMutablePointer<CTAtomicUInt64>
    private let overrunCountPtr: UnsafeMutablePointer<CTAtomicUInt64>
    private let droppedBytesPtr: UnsafeMutablePointer<CTAtomicUInt64>

    public init(capacityBytes: Int = LockFreeRingBuffer.defaultCapacityBytes) {
        let resolvedCapacity = Self.nextPowerOfTwo(max(capacityBytes, Self.minimumCapacityBytes))
        self.capacity = resolvedCapacity
        self.mask = UInt64(resolvedCapacity - 1)
        self.buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: resolvedCapacity)
        self.buffer.initialize(repeating: 0, count: resolvedCapacity)

        // 4 cache-line-isolated 64-bit atomic slots (64 bytes apart) in one allocation.
        let atomics = UnsafeMutableRawPointer.allocate(byteCount: 256, alignment: 64)
        atomics.initializeMemory(as: UInt8.self, repeating: 0, count: 256)
        self.atomicBlock = atomics
        self.writeIndexPtr = atomics.assumingMemoryBound(to: CTAtomicUInt64.self)
        self.readIndexPtr = atomics.advanced(by: 64).assumingMemoryBound(to: CTAtomicUInt64.self)
        self.overrunCountPtr = atomics.advanced(by: 128).assumingMemoryBound(to: CTAtomicUInt64.self)
        self.droppedBytesPtr = atomics.advanced(by: 192).assumingMemoryBound(to: CTAtomicUInt64.self)

        ct_atomic_u64_init(writeIndexPtr, 0)
        ct_atomic_u64_init(readIndexPtr, 0)
        ct_atomic_u64_init(overrunCountPtr, 0)
        ct_atomic_u64_init(droppedBytesPtr, 0)
    }

    deinit {
        buffer.deinitialize(count: capacity)
        buffer.deallocate()
        atomicBlock.deallocate()
    }

    public var availableToRead: Int {
        let w = ct_atomic_u64_load_acquire(writeIndexPtr)
        let r = ct_atomic_u64_load_acquire(readIndexPtr)
        return Int(w &- r)
    }

    public var availableToWrite: Int {
        capacity - availableToRead
    }

    public var overrunCount: UInt64 {
        ct_atomic_u64_load_relaxed(overrunCountPtr)
    }

    public var droppedBytes: UInt64 {
        ct_atomic_u64_load_relaxed(droppedBytesPtr)
    }

    /// Resets indices and overrun counters. Caller must ensure both producer and consumer are quiescent.
    public func reset() {
        ct_atomic_u64_store_relaxed(writeIndexPtr, 0)
        ct_atomic_u64_store_relaxed(readIndexPtr, 0)
        ct_atomic_u64_store_relaxed(overrunCountPtr, 0)
        ct_atomic_u64_store_relaxed(droppedBytesPtr, 0)
    }

    /// All-or-nothing SPSC write. Returns `false` and increments overrun counters if insufficient space remains.
    @discardableResult
    public func write(_ source: UnsafeRawBufferPointer) -> Bool {
        let count = source.count
        if count == 0 { return true }
        guard let base = source.baseAddress else { return false }

        let w = ct_atomic_u64_load_relaxed(writeIndexPtr)
        let r = ct_atomic_u64_load_acquire(readIndexPtr)
        let freeSpace = capacity - Int(w &- r)

        guard count <= freeSpace else {
            _ = ct_atomic_u64_fetch_add_relaxed(overrunCountPtr, 1)
            _ = ct_atomic_u64_fetch_add_relaxed(droppedBytesPtr, UInt64(count))
            return false
        }

        copyIntoRing(source: base, byteCount: count, logicalStart: w)
        ct_atomic_u64_store_release(writeIndexPtr, w &+ UInt64(count))
        return true
    }

    @discardableResult
    public func write(_ data: Data) -> Bool {
        data.withUnsafeBytes { rawBuffer in
            write(rawBuffer)
        }
    }

    /// Reads up to `destination.count` bytes into `destination`. Returns actual bytes read.
    public func read(into destination: UnsafeMutableRawBufferPointer) -> Int {
        guard destination.count > 0, let dstBase = destination.baseAddress else { return 0 }

        let r = ct_atomic_u64_load_relaxed(readIndexPtr)
        let w = ct_atomic_u64_load_acquire(writeIndexPtr)
        let available = Int(w &- r)
        let toRead = min(available, destination.count)
        guard toRead > 0 else { return 0 }

        copyFromRing(destination: dstBase, byteCount: toRead, logicalStart: r)
        ct_atomic_u64_store_release(readIndexPtr, r &+ UInt64(toRead))
        return toRead
    }

    /// Reads exact `destination.count` bytes, or returns `false` without consuming any bytes.
    public func readExact(into destination: UnsafeMutableRawBufferPointer) -> Bool {
        guard destination.count > 0 else { return true }
        guard let dstBase = destination.baseAddress else { return false }

        let r = ct_atomic_u64_load_relaxed(readIndexPtr)
        let w = ct_atomic_u64_load_acquire(writeIndexPtr)
        let available = Int(w &- r)
        guard available >= destination.count else { return false }

        copyFromRing(destination: dstBase, byteCount: destination.count, logicalStart: r)
        ct_atomic_u64_store_release(readIndexPtr, r &+ UInt64(destination.count))
        return true
    }

    /// Zero-allocation atomic chunk write for Core Audio `AudioBufferList` (interleaved or non-interleaved).
    ///
    /// Writes `[48B AudioChunkHeader][buffer0][buffer1]...` all-or-nothing before a single release store
    /// to `writeIndex`. On overrun, atomically increments `overrunCount` and `droppedBytes` and returns `false`.
    @discardableResult
    public func writeAudioBufferList(
        header: AudioChunkHeader,
        bufferList: UnsafePointer<AudioBufferList>
    ) -> Bool {
        let abl = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: bufferList))
        var actualPayloadBytes = 0
        for audioBuffer in abl {
            let size = Int(audioBuffer.mDataByteSize)
            if size > 0 && audioBuffer.mData == nil {
                return false
            }
            actualPayloadBytes += size
        }

        guard abl.count == Int(header.bufferCount),
              actualPayloadBytes == Int(header.payloadByteCount) else {
            return false
        }

        let totalBytes = AudioChunkHeader.encodedByteCount + actualPayloadBytes
        let w = ct_atomic_u64_load_relaxed(writeIndexPtr)
        let r = ct_atomic_u64_load_acquire(readIndexPtr)
        let freeSpace = capacity - Int(w &- r)

        guard totalBytes <= freeSpace else {
            _ = ct_atomic_u64_fetch_add_relaxed(overrunCountPtr, 1)
            _ = ct_atomic_u64_fetch_add_relaxed(droppedBytesPtr, UInt64(totalBytes))
            return false
        }

        var packed: AudioChunkHeader.PackedTuple = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        header.pack(into: &packed)
        withUnsafeBytes(of: &packed) { headerBytes in
            if let base = headerBytes.baseAddress {
                copyIntoRing(source: base, byteCount: AudioChunkHeader.encodedByteCount, logicalStart: w)
            }
        }

        var offset = UInt64(AudioChunkHeader.encodedByteCount)
        for audioBuffer in abl {
            let size = Int(audioBuffer.mDataByteSize)
            if size > 0, let src = audioBuffer.mData {
                copyIntoRing(source: src, byteCount: size, logicalStart: w &+ offset)
                offset &+= UInt64(size)
            }
        }

        ct_atomic_u64_store_release(writeIndexPtr, w &+ UInt64(totalBytes))
        return true
    }

    /// Reads one complete framed audio chunk into a caller-owned scratch buffer.
    /// Returns `nil` if no complete chunk is currently buffered.
    public func readAudioChunk(
        into payloadScratch: UnsafeMutablePointer<UInt8>,
        maxPayloadBytes: Int
    ) -> AudioChunkHeader? {
        let r = ct_atomic_u64_load_relaxed(readIndexPtr)
        let w = ct_atomic_u64_load_acquire(writeIndexPtr)
        let available = Int(w &- r)
        guard available >= AudioChunkHeader.encodedByteCount else { return nil }

        var packed: AudioChunkHeader.PackedTuple = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        withUnsafeMutableBytes(of: &packed) { headerBytes in
            if let base = headerBytes.baseAddress {
                copyFromRing(destination: base, byteCount: AudioChunkHeader.encodedByteCount, logicalStart: r)
            }
        }
        let header = AudioChunkHeader(unpacking: packed)
        let payloadBytes = Int(header.payloadByteCount)
        let totalBytes = AudioChunkHeader.encodedByteCount + payloadBytes
        guard available >= totalBytes else { return nil }

        if payloadBytes > maxPayloadBytes {
            // Skip oversized chunk instead of wedging the ring forever.
            ct_atomic_u64_store_release(readIndexPtr, r &+ UInt64(totalBytes))
            _ = ct_atomic_u64_fetch_add_relaxed(overrunCountPtr, 1)
            _ = ct_atomic_u64_fetch_add_relaxed(droppedBytesPtr, UInt64(totalBytes))
            return nil
        }

        if payloadBytes > 0 {
            copyFromRing(
                destination: payloadScratch,
                byteCount: payloadBytes,
                logicalStart: r &+ UInt64(AudioChunkHeader.encodedByteCount)
            )
        }

        ct_atomic_u64_store_release(readIndexPtr, r &+ UInt64(totalBytes))
        return header
    }

    // MARK: - Private Ring Copy Helpers

    private func copyIntoRing(source: UnsafeRawPointer, byteCount: Int, logicalStart: UInt64) {
        let physicalStart = Int(logicalStart & mask)
        let firstLen = min(byteCount, capacity - physicalStart)
        UnsafeMutableRawPointer(buffer.advanced(by: physicalStart))
            .copyMemory(from: source, byteCount: firstLen)
        let remainder = byteCount - firstLen
        if remainder > 0 {
            UnsafeMutableRawPointer(buffer)
                .copyMemory(from: source.advanced(by: firstLen), byteCount: remainder)
        }
    }

    private func copyFromRing(destination: UnsafeMutableRawPointer, byteCount: Int, logicalStart: UInt64) {
        let physicalStart = Int(logicalStart & mask)
        let firstLen = min(byteCount, capacity - physicalStart)
        destination.copyMemory(from: buffer.advanced(by: physicalStart), byteCount: firstLen)
        let remainder = byteCount - firstLen
        if remainder > 0 {
            destination.advanced(by: firstLen).copyMemory(from: buffer, byteCount: remainder)
        }
    }

    private static func nextPowerOfTwo(_ value: Int) -> Int {
        var n = 1
        while n < value {
            n <<= 1
        }
        return n
    }
}
