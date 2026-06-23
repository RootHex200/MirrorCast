package com.example.android_cast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingAacSender : RtpPacketizer.Sender {
    val packets = mutableListOf<ByteArray>()
    override fun send(packet: ByteArray, length: Int) {
        packets.add(packet.copyOf(length))
    }
}

class AacRtpPacketizerTest {

    @Test
    fun `single AU packet carries AU-headers-section and AU data`() {
        val sender = RecordingAacSender()
        val p = AacRtpPacketizer(sender = sender, mtu = 1400)
        val au = ByteArray(100) { it.toByte() }

        p.sendFrame(au, presentationTimeUs = 0L)

        assertEquals(1, sender.packets.size)
        val pkt = sender.packets[0]
        // RTP V=2
        assertEquals(0x80.toByte(), pkt[0])
        // marker set + PT=97
        assertEquals((0x80 or 97).toByte(), pkt[1])
        // AU-headers-length = 16 bits (0x0010 big-endian)
        assertEquals(0x00, pkt[12].toInt() and 0xFF)
        assertEquals(0x10, pkt[13].toInt() and 0xFF)
        // AU-header: size=100 (13 bits) + index=0
        val size = ((pkt[14].toInt() and 0xFF) shl 5) or ((pkt[15].toInt() and 0xFF) ushr 3)
        assertEquals(100, size)
        assertEquals(0, (pkt[15].toInt() and 0x07))  // AU-index
    }

    @Test
    fun `large AU is fragmented and only last fragment has marker`() {
        val sender = RecordingAacSender()
        val p = AacRtpPacketizer(sender = sender, mtu = 100)
        val big = ByteArray(800) { 0x42 }

        p.sendFrame(big, presentationTimeUs = 0L)

        assertTrue("expected fragmentation", sender.packets.size > 1)
        for (i in 0 until sender.packets.size - 1) {
            val pkt = sender.packets[i]
            assertEquals("intermediate $i must not have marker",
                0, (pkt[1].toInt() and 0x80))
        }
        val last = sender.packets.last()
        assertTrue("last must have marker", (last[1].toInt() and 0x80) != 0)
    }

    @Test
    fun `timestamp scales with 48kHz clock`() {
        val sender = RecordingAacSender()
        val p = AacRtpPacketizer(sender = sender, clockRateHz = 48_000)

        p.sendFrame(ByteArray(50), presentationTimeUs = 0L)
        val ts0 = tsOf(sender.packets[0])
        p.sendFrame(ByteArray(50), presentationTimeUs = 1_000_000L)  // +1s
        val ts1 = tsOf(sender.packets.last())
        assertEquals(48_000L, (ts1 - ts0 + 0x100000000L) and 0xFFFFFFFFL)
    }

    @Test
    fun `ssrc written in every packet`() {
        val sender = RecordingAacSender()
        val p = AacRtpPacketizer(sender = sender, ssrc = 0xABCDEF01.toInt())
        p.sendFrame(ByteArray(50), presentationTimeUs = 0L)
        for (pkt in sender.packets) {
            val ssrc = ((pkt[8].toInt() and 0xFF) shl 24) or
                ((pkt[9].toInt() and 0xFF) shl 16) or
                ((pkt[10].toInt() and 0xFF) shl 8) or
                (pkt[11].toInt() and 0xFF)
            assertEquals(0xABCDEF01.toInt(), ssrc)
        }
    }

    private fun tsOf(pkt: ByteArray): Long =
        (((pkt[4].toLong() and 0xFF) shl 24) or
            ((pkt[5].toLong() and 0xFF) shl 16) or
            ((pkt[6].toLong() and 0xFF) shl 8) or
            (pkt[7].toLong() and 0xFF))
}
