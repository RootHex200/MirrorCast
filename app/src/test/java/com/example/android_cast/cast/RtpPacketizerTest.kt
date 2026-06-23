package com.example.android_cast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every packet the packetizer would emit; assertions inspect the bytes. */
private class RecordingSender : RtpPacketizer.Sender {
    val packets = mutableListOf<ByteArray>()
    override fun send(packet: ByteArray, length: Int) {
        packets.add(packet.copyOf(length))
    }
}

private data class AuCall(val bytes: ByteArray, val endOfAu: Boolean)

private class RecordingAuFramedSender : RtpPacketizer.AuFramedSender {
    val calls = mutableListOf<AuCall>()
    override fun send(packet: ByteArray, length: Int, endOfAccessUnit: Boolean) {
        calls.add(AuCall(packet.copyOf(length), endOfAccessUnit))
    }
}

class RtpPacketizerTest {

    @Test
    fun `small NAL sent as single packet with marker`() {
        val sender = RecordingSender()
        val p = RtpPacketizer(sender = sender, mtu = 1400)
        val nal = byteArrayOf(0x65, 0x01, 0x02, 0x03)  // IDR slice, type 5

        p.sendAccessUnit(listOf(nal), presentationTimeUs = 1_000L)

        assertEquals(1, sender.packets.size)
        val pkt = sender.packets[0]
        // RTP V=2
        assertEquals(0x80.toByte(), pkt[0])
        // marker set + PT=96
        assertEquals((0x80 or 96).toByte(), pkt[1])
        // NAL body intact
        assertEquals(0x65, pkt[12].toInt() and 0xFF)
    }

    @Test
    fun `large NAL fragmented via FU-A with start and end bits`() {
        val sender = RecordingSender()
        // MTU=1400 payload -> 1398 body bytes per FU-A
        val p = RtpPacketizer(sender = sender, mtu = 100)
        val bigNal = ByteArray(500) { it.toByte() }
        bigNal[0] = 0x65  // IDR type=5, NRI=0

        p.sendAccessUnit(listOf(bigNal), presentationTimeUs = 0L)

        assertTrue("expected fragmentation", sender.packets.size > 1)

        // First packet: FU-A indicator has type bits = 28 (0x1C) + NRI from original
        val first = sender.packets[0]
        assertEquals(0x1C, (first[12].toInt() and 0x1F))
        assertTrue("start bit", (first[13].toInt() and 0x80) != 0)

        // Last packet: end bit set, RTP marker set
        val last = sender.packets.last()
        assertTrue("end bit", (last[13].toInt() and 0x40) != 0)
        assertTrue("RTP marker on last", (last[1].toInt() and 0x80) != 0)

        // No intermediate fragment should have marker set
        for (i in 0 until sender.packets.size - 1) {
            val pkt = sender.packets[i]
            assertFalse("intermediate packet $i must not have RTP marker",
                (pkt[1].toInt() and 0x80) != 0)
        }
    }

    @Test
    fun `sequence number increments across packets`() {
        val sender = RecordingSender()
        val p = RtpPacketizer(sender = sender, mtu = 100)

        val nal = ByteArray(500) { 0x41 }  // type 1
        nal[0] = 0x41
        p.sendAccessUnit(listOf(nal), presentationTimeUs = 0L)

        val seqs = sender.packets.map { pkt ->
            ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        }
        for (i in 1 until seqs.size) {
            assertEquals("seq +1 at $i", (seqs[i - 1] + 1) and 0xFFFF, seqs[i])
        }
    }

    @Test
    fun `timestamp advances with presentation time`() {
        val sender = RecordingSender()
        val p = RtpPacketizer(sender = sender, ssrc = 0xDEADBEEF.toInt())

        p.sendAccessUnit(listOf(byteArrayOf(0x41, 0x01)), presentationTimeUs = 0L)
        val ts0 = timestampOf(sender.packets[0])
        p.sendAccessUnit(listOf(byteArrayOf(0x41, 0x01)), presentationTimeUs = 1_000_000L)  // +1s
        val ts1 = timestampOf(sender.packets.last())

        // 90kHz clock, 1s = 90000 ticks
        assertEquals(90_000, (ts1 - ts0 + 0x100000000L).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun `ssrc written in all packets`() {
        val sender = RecordingSender()
        val p = RtpPacketizer(sender = sender, ssrc = 0xCAFEBABE.toInt())

        p.sendAccessUnit(listOf(byteArrayOf(0x41, 0x01, 0x02)), presentationTimeUs = 0L)

        for (pkt in sender.packets) {
            val ssrc = ((pkt[8].toInt() and 0xFF) shl 24) or
                ((pkt[9].toInt() and 0xFF) shl 16) or
                ((pkt[10].toInt() and 0xFF) shl 8) or
                (pkt[11].toInt() and 0xFF)
            assertEquals(0xCAFEBABE.toInt(), ssrc)
        }
    }

    @Test
    fun `AU-aware sink marks endOfAccessUnit only on final packet of final NAL`() {
        val sink = RecordingAuFramedSender()
        val p = RtpPacketizer(sender = RecordingSender(), mtu = 1400)
        // SPS + PPS + IDR (3 NALs in one AU)
        p.sendAccessUnit(
            listOf(byteArrayOf(0x67, 1), byteArrayOf(0x68, 1), byteArrayOf(0x65, 1, 2, 3)),
            presentationTimeUs = 0L,
            sink = sink,
        )
        // Each NAL fits in a single packet -> 3 packets, only the last has endOfAu=true.
        assertEquals(3, sink.calls.size)
        assertFalse("packet 1 must not mark end", sink.calls[0].endOfAu)
        assertFalse("packet 2 must not mark end", sink.calls[1].endOfAu)
        assertTrue("final packet must mark end", sink.calls[2].endOfAu)
    }

    @Test
    fun `AU-aware sink marks end on final FU-A fragment only`() {
        val sink = RecordingAuFramedSender()
        val p = RtpPacketizer(sender = RecordingSender(), mtu = 100)
        // Big IDR NAL -> FU-A fragmentation. Only the last fragment carries endOfAu.
        val bigNal = ByteArray(500) { 0x41.toByte() }
        p.sendAccessUnit(listOf(bigNal), presentationTimeUs = 0L, sink = sink)

        assertTrue("expected fragmentation", sink.calls.size > 1)
        for (i in 0 until sink.calls.size - 1) {
            assertFalse("fragment $i must not mark end", sink.calls[i].endOfAu)
        }
        assertTrue("last fragment must mark end", sink.calls.last().endOfAu)
    }

    @Test
    fun `Sender SAM stays 2-arg for non-video callers`() {
        // AAC packetizer relies on the 2-arg Sender; U2 must not break it.
        val sender = RecordingSender()
        val aac = AacRtpPacketizer(sender = sender)
        aac.sendFrame(ByteArray(50) { 0x42 }, presentationTimeUs = 0L)
        assertTrue("AAC must keep using 2-arg Sender unchanged", sender.packets.isNotEmpty())
    }

    private fun timestampOf(pkt: ByteArray): Long {
        return (((pkt[4].toLong() and 0xFF) shl 24) or
            ((pkt[5].toLong() and 0xFF) shl 16) or
            ((pkt[6].toLong() and 0xFF) shl 8) or
            (pkt[7].toLong() and 0xFF))
    }
}
