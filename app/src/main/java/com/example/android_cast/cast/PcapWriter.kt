package com.example.android_cast.cast

import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a minimal libpcap file (linktype = raw IP, so we skip Ethernet framing).
 * Used to check in self-consistent golden fixtures: tests use the real RTP/AAC
 * packetizers to write the bytes a live capture would have produced, then assert
 * those same bytes back through the parsers.
 */
class PcapWriter(private val out: OutputStream) : java.io.Closeable {
    init {
        // Global header
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0xa1b2c3d4.toInt())    // magic (unsigned 32-bit)
        bb.putShort(2)                   // major
        bb.putShort(4)                   // minor
        bb.putInt(0)                     // thiszone
        bb.putInt(0)                     // sigfigs
        bb.putInt(65_535)                // snaplen
        bb.putInt(101)                   // network = raw IP
        out.write(bb.array())
    }

    /** Write one UDP packet carrying [payload] from [srcPort] to [dstPort]. */
    fun writeUdp(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray) {
        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address

        val udpLen = 8 + payload.size
        val ipLen = 20 + udpLen

        // IP header — exactly 20 bytes; UDP+payload come from the UDP buffer below.
        val ip = ByteArray(20)
        ip[0] = 0x45                       // version + IHL
        ip[1] = 0                          // DSCP/ECN
        ip[2] = ((ipLen ushr 8) and 0xFF).toByte()
        ip[3] = (ipLen and 0xFF).toByte()
        ip[4] = 0; ip[5] = 0               // ident
        ip[6] = 0x40; ip[7] = 0            // don't fragment
        ip[8] = 64                         // TTL
        ip[9] = 17                         // proto = UDP
        // ip[10..11] checksum = 0 (skip — receivers tolerate)
        System.arraycopy(src, 0, ip, 12, 4)
        System.arraycopy(dst, 0, ip, 16, 4)

        // UDP header + payload
        val udp = ByteArray(udpLen)
        udp[0] = ((srcPort ushr 8) and 0xFF).toByte()
        udp[1] = (srcPort and 0xFF).toByte()
        udp[2] = ((dstPort ushr 8) and 0xFF).toByte()
        udp[3] = (dstPort and 0xFF).toByte()
        udp[4] = ((udpLen ushr 8) and 0xFF).toByte()
        udp[5] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, udp, 8, payload.size)

        val now = System.currentTimeMillis() / 1000L
        val usec = ((System.currentTimeMillis() % 1000) * 1000).toInt()
        val rec = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        rec.putInt(now.toInt())           // ts_sec
        rec.putInt(usec)                  // ts_usec
        rec.putInt(ipLen)                 // incl_len
        rec.putInt(ipLen)                 // orig_len
        out.write(rec.array())
        out.write(ip)
        out.write(udp)
    }

    override fun close() { out.flush() }
}

/** Helper: write all [packets] (udp payloads) to [file] as a single PCAP. */
fun writePcap(file: File, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int,
              packets: List<ByteArray>) {
    file.parentFile?.mkdirs()
    PcapWriter(file.outputStream()).use { w ->
        for (p in packets) w.writeUdp(srcIp, srcPort, dstIp, dstPort, p)
    }
}

/** Minimal libpcap reader. Returns the list of UDP payloads in capture order. */
class PcapReader(private val file: File) {
    fun readUdpPayloads(): List<ByteArray> {
        val bytes = file.readBytes()
        require(bytes.size >= 24) { "pcap too small" }
        val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(magic == 0xa1b2c3d4.toInt()) { "bad magic" }
        val out = mutableListOf<ByteArray>()
        var i = 24
        while (i + 16 <= bytes.size) {
            val rec = bytes.copyOfRange(i, i + 16)
            val bb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
            val inclLen = bb.getInt(8)
            i += 16
            require(i + inclLen in 0..bytes.size) { "truncated record i=$i inclLen=$inclLen size=${bytes.size}" }
            val payloadStart = i + 20 + 8
            val payloadEnd = i + inclLen
            if (payloadEnd > payloadStart) {
                out.add(bytes.copyOfRange(payloadStart, payloadEnd))
            }
            i += inclLen
        }
        return out
    }
}
