package com.example.android_cast.cast

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import kotlin.math.min

/**
 * H.264 RTP packetizer (RFC 6184) writing FU-A fragmented packets to a UDP socket.
 *
 * Single-NAL and small enough packets are sent whole; large NALs (> MTU) are
 * fragmented via FU-A. Marker bit is set on the final packet of each access unit
 * so the receiver can frame-lock without inspecting NAL contents.
 *
 * Pure-Java core so it's JVM-unit-testable without Android. Tests inject a fake
 * [Sender] and assert on the bytes that would hit the wire.
 */
class RtpPacketizer(
    private val sender: Sender,
    private val ssrc: Int = 0x12345678,
    private val mtu: Int = 1400,  // bytes available for RTP payload (UDP payload - 12B RTP hdr)
    private val clockRateHz: Int = 90_000,
) {
    /** Sink abstraction. Real impl wraps a DatagramChannel; tests use a recording fake. */
    fun interface Sender {
        fun send(packet: ByteArray, length: Int)
    }

    /**
     * Sink that knows when an access unit ends. Used by the batched pipeline so the
     * [AccessUnitBatcher] can flush once per AU. The [Sender] interface stays 2-arg so
     * non-video callers (AAC packetizer, conformance tests) keep working unchanged.
     */
    fun interface AuFramedSender {
        fun send(packet: ByteArray, length: Int, endOfAccessUnit: Boolean)
    }

    private var sequenceNumber: Int = 0
    private var timestampTicks: Long = 0L

    /** Packetize one access unit (a list of NALs forming a single picture). */
    fun sendAccessUnit(nals: List<ByteArray>, presentationTimeUs: Long) {
        timestampTicks = (presentationTimeUs * clockRateHz) / 1_000_000L
        for ((idx, nal) in nals.withIndex()) {
            val isLastNal = idx == nals.lastIndex
            sendNal(nal, isLastNal)
        }
    }

    /**
     * AU-aware overload: routes every packet through [sink], marking [endOfAccessUnit=true]
     * on the final packet of the final NAL (the packet that carries the RTP marker bit).
     * Use this when the downstream sink batches writes per AU (e.g. [AccessUnitBatcher]).
     */
    fun sendAccessUnit(nals: List<ByteArray>, presentationTimeUs: Long, sink: AuFramedSender) {
        timestampTicks = (presentationTimeUs * clockRateHz) / 1_000_000L
        for ((idx, nal) in nals.withIndex()) {
            val isLastNal = idx == nals.lastIndex
            sendNal(nal, isLastNal, sink)
        }
    }

    private fun sendNal(nal: ByteArray, isLastInAu: Boolean) {
        if (nal.isEmpty()) return
        if (nal.size <= mtu) {
            sendSingleNal(nal, isLastInAu)
        } else {
            sendFuA(nal, isLastInAu)
        }
    }

    private fun sendNal(nal: ByteArray, isLastInAu: Boolean, sink: AuFramedSender) {
        if (nal.isEmpty()) return
        if (nal.size <= mtu) {
            sendSingleNal(nal, isLastInAu, sink)
        } else {
            sendFuA(nal, isLastInAu, sink)
        }
    }

    private fun sendSingleNal(nal: ByteArray, isLastInAu: Boolean) {
        val packet = ByteArray(12 + nal.size)
        writeRtpHeader(packet, payloadType = 96, marker = isLastInAu)
        System.arraycopy(nal, 0, packet, 12, nal.size)
        sender.send(packet, packet.size)
    }

    private fun sendSingleNal(nal: ByteArray, isLastInAu: Boolean, sink: AuFramedSender) {
        val packet = ByteArray(12 + nal.size)
        writeRtpHeader(packet, payloadType = 96, marker = isLastInAu)
        System.arraycopy(nal, 0, packet, 12, nal.size)
        sink.send(packet, packet.size, endOfAccessUnit = isLastInAu)
    }

    private fun sendFuA(nal: ByteArray, isLastInAu: Boolean) {
        val nalHeader = nal[0].toInt() and 0xFF
        val nalType = nalHeader and 0x1F
        val nri = nalHeader and 0x60
        val fuIndicator = (nri or 0x1C).toByte()  // FU-A indicator (type 28)

        // Each FU-A payload can carry up to (mtu - 2) bytes of NAL body.
        val chunkLen = mtu - 2
        var offset = 1  // skip original NAL header byte; FU-A reconstructs it
        var isFirst = true
        while (offset < nal.size) {
            val take = min(chunkLen, nal.size - offset)
            val isLast = (offset + take) == nal.size
            val packet = ByteArray(12 + 2 + take)
            writeRtpHeader(packet, payloadType = 96, marker = isLastInAu && isLast)
            packet[12] = fuIndicator
            var fuHeader = nalType
            if (isFirst) fuHeader = fuHeader or 0x80  // start bit
            if (isLast) fuHeader = fuHeader or 0x40   // end bit
            packet[13] = fuHeader.toByte()
            System.arraycopy(nal, offset, packet, 14, take)
            sender.send(packet, packet.size)

            offset += take
            isFirst = false
        }
    }

    private fun sendFuA(nal: ByteArray, isLastInAu: Boolean, sink: AuFramedSender) {
        val nalHeader = nal[0].toInt() and 0xFF
        val nalType = nalHeader and 0x1F
        val nri = nalHeader and 0x60
        val fuIndicator = (nri or 0x1C).toByte()

        val chunkLen = mtu - 2
        var offset = 1
        var isFirst = true
        while (offset < nal.size) {
            val take = min(chunkLen, nal.size - offset)
            val isLast = (offset + take) == nal.size
            val packet = ByteArray(12 + 2 + take)
            writeRtpHeader(packet, payloadType = 96, marker = isLastInAu && isLast)
            packet[12] = fuIndicator
            var fuHeader = nalType
            if (isFirst) fuHeader = fuHeader or 0x80
            if (isLast) fuHeader = fuHeader or 0x40
            packet[13] = fuHeader.toByte()
            System.arraycopy(nal, offset, packet, 14, take)
            sink.send(packet, packet.size, endOfAccessUnit = isLastInAu && isLast)

            offset += take
            isFirst = false
        }
    }

    private fun writeRtpHeader(packet: ByteArray, payloadType: Int, marker: Boolean) {
        // V=2, P=0, X=0, CC=0
        packet[0] = 0x80.toByte()
        // M + PT
        packet[1] = (((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte())
        // sequence number (big-endian)
        packet[2] = ((sequenceNumber ushr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        // timestamp (big-endian)
        val ts = timestampTicks.toInt()
        packet[4] = ((ts ushr 24) and 0xFF).toByte()
        packet[5] = ((ts ushr 16) and 0xFF).toByte()
        packet[6] = ((ts ushr 8) and 0xFF).toByte()
        packet[7] = (ts and 0xFF).toByte()
        // SSRC (big-endian)
        packet[8] = ((ssrc ushr 24) and 0xFF).toByte()
        packet[9] = ((ssrc ushr 16) and 0xFF).toByte()
        packet[10] = ((ssrc ushr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
    }

    companion object {
        /** Build a Sender backed by a connected UDP DatagramChannel. */
        fun udpSender(host: String, port: Int): Sender {
            val channel = DatagramChannel.open()
            val remote: SocketAddress = InetSocketAddress(host, port)
            channel.connect(remote)
            return Sender { data, length ->
                val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
                channel.write(buf)
            }
        }
    }
}
