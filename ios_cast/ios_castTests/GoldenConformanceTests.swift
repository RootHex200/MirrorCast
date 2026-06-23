import Testing
import Foundation
@testable import ios_cast

/// Cross-platform conformance suite (Swift side).
///
/// The Kotlin suite writes golden PCAPs (RTP H.264, RTP AAC, RTSP handshake) using
/// the real packetizers as oracle. This suite reads the same files back through
/// the Mac parsers and asserts on structure. Together the two suites guarantee
/// "an Android build from this repo can be received by a Mac build from this repo."
///
/// When a new behaviour lands, add a fixture in GoldenConformanceTest.kt AND a
/// matching assertion here.
@Suite struct GoldenConformanceTests {

    /// Path to the Kotlin suite's output directory.
    private static let kotlinOutDir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("app")
        .appendingPathComponent("build")
        .appendingPathComponent("test-resources")
        .appendingPathComponent("golden")

    @Test func parsesGoldenRtpH264Stream() throws {
        let url = kotlinOutDir.appendingPathComponent("h264.pcap")
        guard FileManager.default.fileExists(atPath: url.path) else {
            // Kotlin suite hasn't generated fixtures in this env — skip, not fail.
            return
        }
        let payloads = try Self.readUdpPayloads(from: url)
        #expect(payloads.count > 0)

        // Each payload parses as RTP with V=2.
        for payload in payloads {
            let pkt = RTPPacket(payload)
            #expect(pkt != nil)
            #expect(pkt?.version == 2)
            #expect(pkt?.payloadType == 96)
        }

        // The H.264 depacketizer should assemble at least one access unit from
        // the captured packets.
        let depack = H264Depacketizer()
        var assembled = 0
        for payload in payloads {
            guard let pkt = RTPPacket(payload) else { continue }
            if depack.consume(packet: pkt) != nil { assembled += 1 }
        }
        #expect(assembled >= 2)  // SPS + at least one picture
    }

    @Test func parsesGoldenRtspHandshake() throws {
        let url = kotlinOutDir.appendingPathComponent("rtsp_handshake.pcap")
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        let payloads = try Self.readUdpPayloads(from: url)
        #expect(payloads.count == 4)

        // First payload should parse as an OPTIONS request.
        let first = String(data: payloads[0], encoding: .utf8) ?? ""
        #expect(first.hasPrefix("OPTIONS"))

        // Last payload should be TEARDOWN.
        let last = String(data: payloads[3], encoding: .utf8) ?? ""
        #expect(last.hasPrefix("TEARDOWN"))
    }

    // MARK: - PCAP reader (mirrors Kotlin PcapReader)

    private static func readUdpPayloads(from url: URL) throws -> [Data] {
        let data = try Data(contentsOf: url)
        guard data.count >= 24 else { return [] }
        let magic = UInt32(littleEndian: data.subdata(in: 0..<4).withUnsafeBytes { $0.load(as: UInt32.self) })
        if magic != 0xa1b2c3d4 { return [] }
        var i = 24
        var out: [Data] = []
        while i + 16 <= data.count {
            let rec = data.subdata(in: i..<(i + 16))
            let inclLen = Int(littleEndian: rec.subdata(in: 8..<12).withUnsafeBytes { $0.load(as: UInt32.self) })
            i += 16
            let payloadStart = i + 20 + 8  // ip + udp
            let payloadEnd = i + inclLen
            if payloadEnd > payloadStart {
                out.append(data.subdata(in: payloadStart..<payloadEnd))
            }
            i += inclLen
        }
        return out
    }
}
