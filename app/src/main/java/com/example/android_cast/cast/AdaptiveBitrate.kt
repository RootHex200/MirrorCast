package com.example.android_cast.cast

/**
 * Parses the parts of an RTCP Receiver Report we need for adaptive bitrate.
 *
 * RTCP RR header layout (we only consume what we read):
 *   V=2, P, RC, PT=201, length, sender_ssrc, then RC report blocks.
 * Each report block:
 *   4B SSRC, 4B fraction_lost(8) + cumulative_lost(24), 4B highest_seq, 4B jitter,
 *   4B lsr, 4B dlsr.
 *
 * fraction_lost is the high byte of the first report-block word: fraction of packets
 * lost since the last RR, in 1/256 units. We use it directly to step the bitrate.
 */
data class RtcpReceiverReport(
    val fractionLost256: Int,   // 0..256
    val cumulativeLost: Long,
    val jitter: Int,
    val rttMs: Long?,           // round-trip time from lsr/dlsr; null if not measurable
)

object RtcpReceiverReportParser {
    fun parse(packet: ByteArray): RtcpReceiverReport? {
        if (packet.size < 8) return null
        val pt = packet[1].toInt() and 0xFF
        if (pt != 201) return null  // RR
        val rc = packet[0].toInt() and 0x1F
        if (rc == 0) return null
        if (packet.size < 8 + 24) return null

        val off = 8
        val fractionLost = packet[off + 4].toInt() and 0xFF
        val cumulativeLost = ((packet[off + 5].toLong() and 0xFF) shl 16) or
            ((packet[off + 6].toLong() and 0xFF) shl 8) or
            (packet[off + 7].toLong() and 0xFF)
        val jitter = ((packet[off + 12].toInt() and 0xFF) shl 24) or
            ((packet[off + 13].toInt() and 0xFF) shl 16) or
            ((packet[off + 14].toInt() and 0xFF) shl 8) or
            (packet[off + 15].toInt() and 0xFF)

        // RTT = NTP_now - lsr - dlsr — but we don't have a synced clock here; the
        // sender side will compute it. We surface raw lsr/dlsr difference.
        val lsr = ((packet[off + 16].toLong() and 0xFF) shl 24) or
            ((packet[off + 17].toLong() and 0xFF) shl 16) or
            ((packet[off + 18].toLong() and 0xFF) shl 8) or
            (packet[off + 19].toLong() and 0xFF)
        val dlsr = ((packet[off + 20].toLong() and 0xFF) shl 24) or
            ((packet[off + 21].toLong() and 0xFF) shl 16) or
            ((packet[off + 22].toLong() and 0xFF) shl 8) or
            (packet[off + 23].toLong() and 0xFF)
        val rttMs = if (lsr != 0L && dlsr != 0L) (dlsr * 1000L) / 65536L else null

        return RtcpReceiverReport(
            fractionLost256 = fractionLost,
            cumulativeLost = cumulativeLost,
            jitter = jitter,
            rttMs = rttMs,
        )
    }
}

/**
 * Steps encoder bitrate based on observed packet loss. Pure state machine so it
 * can be unit-tested against any sequence of receiver reports.
 *
 * Policy:
 *   - Loss >= 10%       -> step bitrate down hard (-50%).
 *   - Loss 2%-10%       -> step down 1 tier.
 *   - Loss < 2% sustained (3 reports in a row) -> step up 1 tier.
 * Bitrate is clamped to [200 Kbps, 8 Mbps]. A user resolution cap can lower the
 * effective ceiling.
 */
class AdaptiveBitrate(
    private val minBps: Int = 200_000,
    private val maxBps: Int = 8_000_000,
    initialBps: Int = 4_000_000,
) {
    var currentBps: Int = initialBps
        private set

    private var goodReportsInARow: Int = 0

    /** User-facing resolution cap lowers the ceiling. */
    var ceilingBps: Int = maxBps

    fun applyReport(rr: RtcpReceiverReport): Int {
        val lossPct = rr.fractionLost256 * 100 / 256
        return when {
            lossPct >= 10 -> {
                goodReportsInARow = 0
                stepDown(factor = 0.5)
            }
            lossPct >= 2 -> {
                goodReportsInARow = 0
                stepDown(factor = 0.75)
            }
            else -> {
                goodReportsInARow += 1
                if (goodReportsInARow >= 3) {
                    goodReportsInARow = 0
                    stepUp(factor = 1.25)
                } else currentBps
            }
        }
    }

    private fun stepDown(factor: Double): Int {
        currentBps = (currentBps * factor).toInt().coerceAtLeast(minBps)
        return currentBps
    }

    private fun stepUp(factor: Double): Int {
        val cap = minOf(maxBps, ceilingBps)
        currentBps = (currentBps * factor).toInt().coerceAtMost(cap)
        return currentBps
    }

    /** Called when the user changes resolution mid-session. */
    fun setResolutionCap(ceilingBps: Int) {
        this.ceilingBps = ceilingBps
        currentBps = currentBps.coerceAtMost(ceilingBps)
    }
}
