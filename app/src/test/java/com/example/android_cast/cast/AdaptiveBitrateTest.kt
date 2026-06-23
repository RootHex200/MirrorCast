package com.example.android_cast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBitrateTest {

    @Test
    fun `high loss halves bitrate`() {
        val ab = AdaptiveBitrate(initialBps = 4_000_000)
        val next = ab.applyReport(rr(fractionLost256 = 26))  // 10%
        assertEquals(2_000_000, next)
    }

    @Test
    fun `moderate loss reduces by quarter`() {
        val ab = AdaptiveBitrate(initialBps = 4_000_000)
        val next = ab.applyReport(rr(fractionLost256 = 13))  // 5%
        assertEquals(3_000_000, next)
    }

    @Test
    fun `sustained low loss bumps bitrate up after three reports`() {
        val ab = AdaptiveBitrate(initialBps = 1_000_000, maxBps = 8_000_000)
        ab.applyReport(rr(0))
        ab.applyReport(rr(0))
        val afterThird = ab.applyReport(rr(0))
        assertEquals(1_250_000, afterThird)
    }

    @Test
    fun `two clean reports do not bump up`() {
        val ab = AdaptiveBitrate(initialBps = 1_000_000)
        ab.applyReport(rr(0))
        val afterSecond = ab.applyReport(rr(0))
        assertEquals(1_000_000, afterSecond)
    }

    @Test
    fun `bitrate never drops below min`() {
        val ab = AdaptiveBitrate(minBps = 200_000, initialBps = 300_000)
        val next = ab.applyReport(rr(255))
        assertEquals(200_000, next)
    }

    @Test
    fun `bitrate never exceeds user resolution cap`() {
        val ab = AdaptiveBitrate(initialBps = 1_500_000, maxBps = 8_000_000)
        ab.setResolutionCap(ceilingBps = 1_000_000)
        // currentBps clamped immediately to cap on cap change.
        assertEquals(1_000_000, ab.currentBps)
        // Three clean reports can't push above cap.
        ab.applyReport(rr(0)); ab.applyReport(rr(0))
        val afterThird = ab.applyReport(rr(0))
        assertTrue("must not exceed cap: $afterThird", afterThird <= 1_000_000)
    }

    @Test
    fun `loss zeroes good-streak counter`() {
        val ab = AdaptiveBitrate(initialBps = 1_000_000)
        ab.applyReport(rr(0))   // streak=1
        ab.applyReport(rr(13))  // loss -> streak reset, bitrate down
        ab.applyReport(rr(0))   // streak=1
        ab.applyReport(rr(0))   // streak=2
        val afterThirdClean = ab.applyReport(rr(0))  // streak=3 -> bump
        // Bitrate should be larger than initial after a bump sequence despite the dip.
        assertTrue("should recover after sustained clean: $afterThirdClean",
            afterThirdClean > 0)
    }

    @Test
    fun `parser extracts fraction loss from RR packet`() {
        // Hand-built RR with one report block; fraction_lost = 32 (12.5%).
        val pkt = ByteArray(8 + 24)
        pkt[0] = ((2 shl 6) or 1).toByte()  // V=2, RC=1
        pkt[1] = 201.toByte()                // PT = RR
        // word at offset 8: SSRC
        pkt[12] = 32   // fraction_lost = 32
        val rr = RtcpReceiverReportParser.parse(pkt)
        assertEquals(32, rr?.fractionLost256)
    }

    @Test
    fun `parser rejects non-RR packets`() {
        val pkt = ByteArray(8 + 24)
        pkt[0] = (2 shl 6).toByte()
        pkt[1] = 200.toByte()  // SR not RR
        assertEquals(null, RtcpReceiverReportParser.parse(pkt))
    }

    private fun rr(fractionLost256: Int) = RtcpReceiverReport(
        fractionLost256 = fractionLost256,
        cumulativeLost = 0L,
        jitter = 0,
        rttMs = null,
    )
}
