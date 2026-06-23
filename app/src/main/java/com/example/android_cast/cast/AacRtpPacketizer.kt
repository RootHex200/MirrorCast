package com.example.android_cast.cast

/**
 * AAC-LC RTP packetizer per RFC 3640 (single AU per packet, AU-header-length = 16 bits:
 * AU-size 13 bits + AU-index 3 bits). Frame size limit = mtu; larger AUs get fragmented
 * across packets — the receiver reassembles via AU-data-continuation semantics.
 *
 * Pure-Java core, JVM-unit-testable without Android.
 */
class AacRtpPacketizer(
    private val sender: RtpPacketizer.Sender,
    private val ssrc: Int = 0x76543210,
    private val mtu: Int = 1400,
    private val clockRateHz: Int = 48_000,
) {
    private var sequenceNumber: Int = 0
    private var timestampTicks: Long = 0L

    fun sendFrame(frame: ByteArray, presentationTimeUs: Long) {
        timestampTicks = (presentationTimeUs * clockRateHz) / 1_000_000L
        if (frame.size <= mtu - 4) {
            sendSingleAu(frame)
        } else {
            sendFragmentedAu(frame)
        }
    }

    private fun sendSingleAu(au: ByteArray) {
        // 2 bytes AU-header-length (16 bits of headers = 0x10 0x00 -> 2 bytes), 2 bytes AU-header
        val packet = ByteArray(12 + 4 + au.size)
        writeRtpHeader(packet, payloadType = 97, marker = true)
        packet[12] = 0x00  // header-length = 0 (size in 32-bit words: 16 bits = 0.5 word -> 0x10)
        packet[13] = 0x10  // 16 bits of AU-headers
        // AU-header: 13 bits AU-size, 3 bits AU-index (0)
        val size = au.size
        packet[14] = ((size ushr 5) and 0xFF).toByte()
        packet[15] = ((size shl 3) and 0xFF).toByte()
        System.arraycopy(au, 0, packet, 16, au.size)
        sender.send(packet, packet.size)
    }

    private fun sendFragmentedAu(au: ByteArray) {
        // First packet carries the AU-headers and the first chunk; subsequent packets
        // carry continuation AU fragments (no AU-headers). For simplicity we always
        // emit AU-headers in every fragment of the same AU (RFC 3640 allows this when
        // there is exactly one AU). Marker only on the last fragment.
        val payloadChunk = mtu - 4 - 4  // account for AU-header section + 1 AU-header
        var offset = 0
        while (offset < au.size) {
            val take = minOf(payloadChunk, au.size - offset)
            val isLast = (offset + take) == au.size
            val packet = ByteArray(12 + 4 + take)
            writeRtpHeader(packet, payloadType = 97, marker = isLast)
            packet[12] = 0x00
            packet[13] = 0x10
            val size = au.size
            packet[14] = ((size ushr 5) and 0xFF).toByte()
            packet[15] = ((size shl 3) and 0xFF).toByte()
            System.arraycopy(au, offset, packet, 16, take)
            sender.send(packet, packet.size)
            offset += take
        }
    }

    private fun writeRtpHeader(packet: ByteArray, payloadType: Int, marker: Boolean) {
        packet[0] = 0x80.toByte()
        packet[1] = (((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte())
        packet[2] = ((sequenceNumber ushr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        val ts = timestampTicks.toInt()
        packet[4] = ((ts ushr 24) and 0xFF).toByte()
        packet[5] = ((ts ushr 16) and 0xFF).toByte()
        packet[6] = ((ts ushr 8) and 0xFF).toByte()
        packet[7] = (ts and 0xFF).toByte()
        packet[8] = ((ssrc ushr 24) and 0xFF).toByte()
        packet[9] = ((ssrc ushr 16) and 0xFF).toByte()
        packet[10] = ((ssrc ushr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
    }
}
