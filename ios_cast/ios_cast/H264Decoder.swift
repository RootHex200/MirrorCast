import Foundation
import CoreVideo
import CoreMedia
import VideoToolbox

/// H.264 hardware decoder wrapping VTDecompressionSession.
/// Feeds access units (one picture each); emits decoded CMSampleBuffers via `onSampleBuffer`.
final class H264Decoder {
    private var session: VTDecompressionSession?
    private var formatDescription: CMVideoFormatDescription?
    private var currentSPS: Data?
    private var currentPPS: Data?
    private let queue: DispatchQueue
    private(set) var decodedFrameCount: Int = 0
    var onSampleBuffer: ((CMSampleBuffer) -> Void)?

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    func decode(accessUnit: AccessUnit) {
        let au = accessUnit
        queue.async { [weak self] in
            self?.syncDecode(accessUnit: au)
        }
    }

    /// Provides SPS/PPS out-of-band (e.g. from RTSP SDP sprop-parameter-sets) so the
    /// decoder can be constructed before the first in-band IDR.
    func prime(sps: Data, pps: Data) {
        queue.async { [weak self] in
            guard let self else { return }
            self.currentSPS = sps
            self.currentPPS = pps
            if self.formatDescription == nil {
                self.rebuildFormatDescription(sps: sps, pps: pps)
            }
        }
    }

    private func syncDecode(accessUnit: AccessUnit) {
        let types = accessUnit.nals.map { nal -> String in
            guard let first = nal.first else { return "?" }
            return String(first & 0x1F)
        }
        print("[dec] syncDecode nals=\(accessUnit.nals.count) types=\(types) sizes=\(accessUnit.nals.map { $0.count }) spsSet=\(currentSPS != nil) ppsSet=\(currentPPS != nil) fmt=\(formatDescription != nil) session=\(session != nil)")
        for nal in accessUnit.nals {
            guard !nal.isEmpty else { continue }
            let type = nal[0] & 0x1F
            if type == 7 { currentSPS = nal }
            if type == 8 { currentPPS = nal }
        }
        guard let sps = currentSPS, let pps = currentPPS else {
            print("[dec] no SPS/PPS yet, skipping")
            return
        }
        if formatDescription == nil {
            rebuildFormatDescription(sps: sps, pps: pps)
        }
        guard let formatDescription else { return }
        if session == nil {
            buildSession(formatDescription: formatDescription)
        }
        guard let session else { return }

        let vclNALs = accessUnit.nals.filter { nal in
            guard !nal.isEmpty else { return false }
            let type = nal[0] & 0x1F
            return type == 1 || type == 5
        }
        guard !vclNALs.isEmpty else { return }

        // Decode each VCL NAL as its own sample buffer. Multi-slice pictures come
        // through as several NALs in one AU; VideoToolbox rejects sample buffers
        // that contain multiple slices stitched together as one AVC frame.
        for nal in vclNALs {
            guard let sampleBuffer = buildSampleBuffer(nals: [nal],
                                                       formatDescription: formatDescription,
                                                       timestamp: accessUnit.timestamp) else { continue }
            decodeOne(session: session, sampleBuffer: sampleBuffer)
        }
    }

    private func decodeOne(session: VTDecompressionSession, sampleBuffer: CMSampleBuffer) {
        let outputHandler: VTDecompressionOutputHandler = { [weak self] status, _, imageBuffer, presentationTimeStamp, duration in
            print("[dec] callback status=\(status) imageBuf=\(imageBuffer != nil) pts=\(presentationTimeStamp.value)")
            guard let self else { return }
            guard status == noErr, let imageBuffer else { return }
            guard let fmt = self.formatDescription else { return }

            var timing = CMSampleTimingInfo(
                duration: duration,
                presentationTimeStamp: presentationTimeStamp,
                decodeTimeStamp: .invalid
            )
            var sb: CMSampleBuffer?
            var createStatus: OSStatus = -1
            var imageFmt: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(
                allocator: kCFAllocatorDefault,
                imageBuffer: imageBuffer,
                formatDescriptionOut: &imageFmt
            )
            let useFmt = imageFmt ?? fmt
            createStatus = withUnsafePointer(to: &timing) { timingPtr in
                CMSampleBufferCreateForImageBuffer(
                    allocator: kCFAllocatorDefault,
                    imageBuffer: imageBuffer,
                    dataReady: true,
                    makeDataReadyCallback: nil,
                    refcon: nil,
                    formatDescription: useFmt,
                    sampleTiming: timingPtr,
                    sampleBufferOut: &sb
                )
            }
            if createStatus != noErr { print("[dec] sampleBufferCreateForImage status=\(createStatus)") }
            guard createStatus == noErr, let sb else { return }
            self.decodedFrameCount += 1
            self.onSampleBuffer?(sb)
        }

        let decodeStatus = VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sampleBuffer,
            flags: [],
            infoFlagsOut: nil,
            outputHandler: outputHandler
        )
        if decodeStatus != noErr {
            print("[dec] VTDecompressionSessionDecodeFrame status=\(decodeStatus)")
        }
    }

    private func rebuildFormatDescription(sps: Data, pps: Data) {
        session = nil
        var formatDesc: CMVideoFormatDescription?
        var createStatus: OSStatus = -1
        sps.withUnsafeBytes { (spsPtr: UnsafeRawBufferPointer) in
            pps.withUnsafeBytes { (ppsPtr: UnsafeRawBufferPointer) in
                guard let spsBase = spsPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let ppsBase = ppsPtr.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
                let pointers: [UnsafePointer<UInt8>] = [spsBase, ppsBase]
                let sizes: [Int] = [sps.count, pps.count]
                pointers.withUnsafeBufferPointer { ptrBuf in
                    sizes.withUnsafeBufferPointer { sizeBuf in
                        createStatus = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: 2,
                            parameterSetPointers: ptrBuf.baseAddress!,
                            parameterSetSizes: sizeBuf.baseAddress!,
                            nalUnitHeaderLength: 4,
                            formatDescriptionOut: &formatDesc
                        )
                    }
                }
            }
        }
        print("[dec] rebuildFormatDescription spsSize=\(sps.count) ppsSize=\(pps.count) status=\(createStatus) fmt=\(formatDesc != nil)")
        self.formatDescription = formatDesc
    }

    private func buildSession(formatDescription: CMVideoFormatDescription) {
        var sessionRef: VTDecompressionSession?
        let attrs: CFDictionary = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary
        ] as CFDictionary

        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: formatDescription,
            decoderSpecification: nil,
            imageBufferAttributes: attrs,
            outputCallback: nil,
            decompressionSessionOut: &sessionRef
        )
        print("[dec] VTDecompressionSessionCreate status=\(status)")
        if status == noErr {
            session = sessionRef
        }
    }

    /// Builds a CMSampleBuffer holding AVCC-encoded NAL units ready for VideoToolbox.
    /// NALs supplied without start codes; we prepend 4-byte big-endian lengths.
    private func buildSampleBuffer(
        nals: [Data],
        formatDescription: CMVideoFormatDescription,
        timestamp: UInt32
    ) -> CMSampleBuffer? {
        var avcc = Data()
        for nal in nals {
            var lengthBE = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &lengthBE) { avcc.append(contentsOf: $0) }
            avcc.append(nal)
        }

        var blockBuffer: CMBlockBuffer?
        let total = avcc.count
        let createStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: total,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: total,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard createStatus == kCMBlockBufferNoErr, let blockBuffer else { return nil }
        let replaceStatus = avcc.withUnsafeBytes { raw -> OSStatus in
            guard let base = raw.baseAddress else { return kCMBlockBufferStructureAllocationFailedErr }
            return CMBlockBufferReplaceDataBytes(
                with: base,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: total
            )
        }
        guard replaceStatus == kCMBlockBufferNoErr else { return nil }

        var sampleSize = avcc.count
        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: 30),
            presentationTimeStamp: CMTime(value: CMTimeValue(timestamp), timescale: 90000),
            decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        let ok: Bool = withUnsafePointer(to: &timing) { timingPtr in
            withUnsafeMutablePointer(to: &sampleSize) { sizePtr in
                CMSampleBufferCreate(
                    allocator: kCFAllocatorDefault,
                    dataBuffer: blockBuffer,
                    dataReady: true,
                    makeDataReadyCallback: nil,
                    refcon: nil,
                    formatDescription: formatDescription,
                    sampleCount: 1,
                    sampleTimingEntryCount: 1,
                    sampleTimingArray: timingPtr,
                    sampleSizeEntryCount: 1,
                    sampleSizeArray: sizePtr,
                    sampleBufferOut: &sampleBuffer
                ) == noErr
            }
        }
        return ok ? sampleBuffer : nil
    }
}
