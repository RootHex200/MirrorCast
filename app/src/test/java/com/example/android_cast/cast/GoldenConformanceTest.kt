package com.example.android_cast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden PCAP conformance suite (Kotlin side).
 *
 * Strategy: use the REAL [RtpPacketizer] + [AacRtpPacketizer] as the oracle to
 * write golden PCAP fixtures under build/test-resources/golden, then read them
 * back through [PcapReader] and assert structure. The Mac side asserts on the
 * same fixtures via the Swift conformance runner.
 *
 * When a new behaviour lands, add a new test that emits its bytes; the golden
 * file regenerates on the next test run.
 */
class GoldenConformanceTest {

    private val outDir = File(System.getProperty("user.dir"), "build/test-resources/golden").apply { mkdirs() }

    @Test
    fun `golden H264 stream round-trips through PCAP`() {
        val captured = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer(sender = RtpPacketizer.Sender { pkt, len ->
            captured.add(pkt.copyOf(len))
        }, ssrc = 0xCAFEBABE.toInt())

        // Two access units: one small single-NAL, one FU-A-fragmented large.
        packetizer.sendAccessUnit(
            listOf(byteArrayOf(0x67, 0x01, 0x02, 0x03)),  // SPS
            presentationTimeUs = 0L,
        )
        packetizer.sendAccessUnit(
            listOf(ByteArray(2000) { it.toByte() }.also { it[0] = 0x65 }),
            presentationTimeUs = 33_333L,
        )

        val file = File(outDir, "h264.pcap")
        writePcap(file, "10.0.0.1", 5004, "10.0.0.2", 7236, captured)

        // Round-trip
        val payloads = PcapReader(file).readUdpPayloads()
        assertEquals(captured.size, payloads.size)
        // Each packet's first byte is the RTP V=2 marker
        for (p in payloads) {
            assertEquals(0x80.toByte(), p[0])
        }
        // Final packet has marker (last fragment of fragmented AU)
        assertTrue("last packet should have marker", (payloads.last()[1].toInt() and 0x80) != 0)
    }

    @Test
    fun `golden AAC stream round-trips through PCAP`() {
        val captured = mutableListOf<ByteArray>()
        val packetizer = AacRtpPacketizer(
            sender = RtpPacketizer.Sender { pkt, len -> captured.add(pkt.copyOf(len)) },
            ssrc = 0xDEADBEEF.toInt(),
        )
        // Five AAC frames; one large enough to fragment.
        repeat(5) { i ->
            packetizer.sendFrame(ByteArray(if (i == 2) 2000 else 100), presentationTimeUs = i * 21_000L)
        }

        val file = File(outDir, "aac.pcap")
        writePcap(file, "10.0.0.1", 5006, "10.0.0.2", 7238, captured)

        val payloads = PcapReader(file).readUdpPayloads()
        assertTrue(captured.size > 5)  // fragmentation inflated count
        for (p in payloads) {
            assertEquals(0x80.toByte(), p[0])
            // PT = 97 for AAC
            assertEquals(97, p[1].toInt() and 0x7F)
        }
    }

    @Test
    fun `golden RTSP handshake round-trips as raw bytes`() {
        val handshake = listOf(
            "OPTIONS rtsp://10.0.0.2:7236/live RTSP/1.0\r\nCSeq: 1\r\n\r\n",
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: OPTIONS, DESCRIBE\r\n\r\n",
            "DESCRIBE rtsp://10.0.0.2:7236/live RTSP/1.0\r\nCSeq: 2\r\n\r\n",
            "TEARDOWN rtsp://10.0.0.2:7236/live RTSP/1.0\r\nCSeq: 5\r\nSession: ABC\r\n\r\n",
        )
        val file = File(outDir, "rtsp_handshake.pcap")
        writePcap(file, "10.0.0.1", 5005, "10.0.0.2", 7236,
            handshake.map { it.toByteArray(Charsets.US_ASCII) })

        val payloads = PcapReader(file).readUdpPayloads()
        assertEquals(handshake.size, payloads.size)
        assertTrue(payloads.first().decodeToString().startsWith("OPTIONS"))
        assertTrue(payloads.last().decodeToString().startsWith("TEARDOWN"))
    }
}
