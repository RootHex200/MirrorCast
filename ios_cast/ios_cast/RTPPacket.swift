import Foundation

struct RTPPacket {
    let version: UInt8
    let padding: Bool
    let extensionFlag: Bool
    let csrcCount: UInt8
    let marker: Bool
    let payloadType: UInt8
    let sequenceNumber: UInt16
    let timestamp: UInt32
    let ssrc: UInt32
    let payload: Data

    init?(_ data: Data) {
        guard data.count >= 12 else { return nil }
        let byte0 = data[0]
        let byte1 = data[1]
        self.version = (byte0 >> 6) & 0x03
        guard version == 2 else { return nil }
        self.padding = (byte0 & 0x20) != 0
        self.extensionFlag = (byte0 & 0x10) != 0
        self.csrcCount = byte0 & 0x0F
        self.marker = (byte1 & 0x80) != 0
        self.payloadType = byte1 & 0x7F
        self.sequenceNumber = (UInt16(data[2]) << 8) | UInt16(data[3])
        self.timestamp = (UInt32(data[4]) << 24) | (UInt32(data[5]) << 16) | (UInt32(data[6]) << 8) | UInt32(data[7])
        self.ssrc = (UInt32(data[8]) << 24) | (UInt32(data[9]) << 16) | (UInt32(data[10]) << 8) | UInt32(data[11])

        var headerLen = 12 + Int(csrcCount) * 4
        guard data.count >= headerLen else { return nil }
        if extensionFlag {
            guard data.count >= headerLen + 4 else { return nil }
            let extLen = (Int(data[headerLen + 2]) << 8) | Int(data[headerLen + 3])
            headerLen += 4 + extLen * 4
            // Defensive: extension length lies — clamp to actual buffer.
            if headerLen > data.count {
                RTPMalformedLog.note("RTP extension header overruns buffer; clamping")
                headerLen = data.count
            }
        }
        guard data.count >= headerLen else { return nil }

        var payloadEnd = data.count
        if padding {
            let padLen = Int(data[data.count - 1])
            guard padLen > 0 && padLen <= data.count - headerLen else { return nil }
            payloadEnd = data.count - padLen
        }
        self.payload = data.subdata(in: headerLen..<payloadEnd)
    }
}

/// Side-channel for malformed-packet telemetry. Captured here instead of crashing
/// the receiver so a flaky sender can't take down the pipeline. Wired into the
/// HUD/logs by [CastReceiver].
enum RTPMalformedLog {
    private static var count: Int = 0
    private static let lock = NSLock()
    static func note(_ reason: String) {
        lock.lock(); defer { lock.unlock() }
        count += 1
        #if DEBUG_CAST
        print("[cast] malformed: \(reason) (total=\(count))")
        #endif
    }
    static var total: Int { lock.lock(); defer { lock.unlock() }; return count }
    static func reset() { lock.lock(); count = 0; lock.unlock() }
}

/// H.264 RTP depacketizer per RFC 6184.
/// Emits complete access units (one per picture) tagged with their RTP timestamp.
final class H264Depacketizer {
    private var sps: Data?
    private var pps: Data?
    private var currentNALs: [Data] = []
    private var currentTimestamp: UInt32?
    private var lastSequence: UInt16?

    /// Updates the depacketizer with one RTP packet's payload.
    /// Returns a completed access unit (array of NAL units, without start codes) when the
    /// packet's marker bit demarcates the end of a frame, or when the timestamp changes.
    func consume(packet: RTPPacket) -> AccessUnit? {
        // Detect a new timestamp (frame) mid-stream if marker is not set by sender.
        if let ts = currentTimestamp, ts != packet.timestamp, !currentNALs.isEmpty {
            let au = AccessUnit(timestamp: ts, sps: sps, pps: pps, nals: currentNALs)
            currentNALs.removeAll()
            currentTimestamp = packet.timestamp
            appendPayload(packet)
            return au
        }
        currentTimestamp = packet.timestamp
        appendPayload(packet)
        if packet.marker {
            let au = AccessUnit(timestamp: packet.timestamp, sps: sps, pps: pps, nals: currentNALs)
            currentNALs.removeAll()
            return au
        }
        return nil
    }

    private func appendPayload(_ packet: RTPPacket) {
        let payload = packet.payload
        guard !payload.isEmpty else { return }
        let nalHeader = payload[0]
        let type = nalHeader & 0x1F

        switch type {
        case 24:  // STAP-A
            var i = 1
            while i + 2 < payload.count {
                let nalLen = (Int(payload[i]) << 8) | Int(payload[i + 1])
                let nalStart = i + 2
                let nalEnd = nalStart + nalLen
                guard nalEnd <= payload.count else { break }
                let nal = payload.subdata(in: nalStart..<nalEnd)
                ingestSingleNAL(nal)
                i = nalEnd
            }
        case 28, 29:  // FU-A, FU-B (treat both as fragmentation units; FU-B is rare over RTSP/RTP)
            guard payload.count >= 2 else { return }
            let fuIndicator = payload[0]
            let fuHeader = payload[1]
            let originalType = fuHeader & 0x1F
            let startBit = (fuHeader & 0x80) != 0
            let endBit = (fuHeader & 0x40) != 0
            if startBit {
                let reconstructedHeader = (fuIndicator & 0xE0) | originalType
                var nal = Data([reconstructedHeader])
                nal.append(payload.subdata(in: 2..<payload.count))
                ingestSingleNAL(nal)
            } else if !currentNALs.isEmpty {
                currentNALs[currentNALs.count - 1].append(payload.subdata(in: 2..<payload.count))
            }
            _ = endBit
        default:  // Single NAL unit packet
            ingestSingleNAL(payload)
        }
    }

    private func ingestSingleNAL(_ nal: Data) {
        guard !nal.isEmpty else { return }
        let type = nal[0] & 0x1F
        if type == 7 { sps = nal }
        if type == 8 { pps = nal }
        currentNALs.append(nal)
    }
}

struct AccessUnit {
    let timestamp: UInt32
    let sps: Data?
    let pps: Data?
    /// NAL units (no start code prefix) in decode order.
    let nals: [Data]

    func withStartCodes() -> Data {
        var out = Data()
        out.reserveCapacity(nals.reduce(0) { $0 + $1.count + 4 })
        for nal in nals {
            out.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
            out.append(nal)
        }
        return out
    }
}
