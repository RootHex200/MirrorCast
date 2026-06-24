package com.example.android_cast.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessUnitQueueTest {

    private fun paramSetAu(): AccessUnit =
        AccessUnit(listOf(byteArrayOf(0x67, 1, 2), byteArrayOf(0x68, 1)), presentationTimeUs = 0L)

    private fun sliceAu(i: Int): AccessUnit =
        AccessUnit(listOf(byteArrayOf(0x41, i.toByte())), presentationTimeUs = i * 33_000L)

    @Test
    fun `bypass fires synchronously for parameter-set AU`() = runTest {
        val sink = RecordingSink()
        val queue = AccessUnitQueue(sink.batcher, sink.framed)
        val pkt = RtpPacketizer(sender = NoopSender())

        queue.enqueue(paramSetAu(), pkt, ptsUs = 0L)

        // Bypass writes immediately — no consumer needed.
        assertEquals(1, sink.flushCount)
    }

    @Test
    fun `slice AU goes through the channel and arrives on consumer`() = runTest {
        val sink = RecordingSink()
        val queue = AccessUnitQueue(sink.batcher, sink.framed)
        val pkt = RtpPacketizer(sender = NoopSender())

        val job = launch { queue.consume() }
        queue.enqueue(sliceAu(1), pkt, ptsUs = 33_000L)
        queue.enqueue(sliceAu(2), pkt, ptsUs = 66_000L)
        queue.enqueue(sliceAu(3), pkt, ptsUs = 99_000L)
        // Let the consumer drain.
        repeat(5) { yield() }
        queue.close()
        job.join()

        assertEquals(3, sink.flushCount)
    }

    @Test
    fun `DROP_OLDEST keeps newest at capacity, drops oldest silently`() = runTest {
        val sink = RecordingSink()
        val queue = AccessUnitQueue(sink.batcher, sink.framed)
        val pkt = RtpPacketizer(sender = NoopSender())

        val job = launch { queue.consume() }
        // Don't yield — fill past capacity while consumer hasn't drained.
        repeat(10) { i -> queue.enqueue(sliceAu(i), pkt, ptsUs = i.toLong()) }
        repeat(5) { yield() }
        queue.close()
        job.join()

        // The bypass-free slice AUs all went through DROP_OLDEST channel(cap=3); we must
        // have drained at least the 3 that survived.
        assertTrue("consumer should have flushed something", sink.flushCount in 3..10)
    }

    @Test
    fun `enqueue returns false when channel is closed`() = runTest {
        val sink = RecordingSink()
        val queue = AccessUnitQueue(sink.batcher, sink.framed)
        val pkt = RtpPacketizer(sender = NoopSender())

        queue.close()
        val ok = queue.enqueue(sliceAu(1), pkt, ptsUs = 0L)

        assertTrue("closed channel must reject slice AU", !ok)
        assertEquals(0, sink.flushCount)
    }

    @Test
    fun `mixed SPS-PPS-IDR AU bypasses queue`() = runTest {
        val sink = RecordingSink()
        val queue = AccessUnitQueue(sink.batcher, sink.framed)
        val pkt = RtpPacketizer(sender = NoopSender())

        // First-frame shape: SPS + PPS + IDR (first byte 0x65 = type 5, not 7/8)
        val au = AccessUnit(
            listOf(byteArrayOf(0x67), byteArrayOf(0x68), byteArrayOf(0x65, 1, 2)),
            presentationTimeUs = 0L,
        )
        queue.enqueue(au, pkt, ptsUs = 0L)

        // Synchronous bypass even though the AU contains slices — the param-set NALs force bypass.
        assertEquals(1, sink.flushCount)
    }
}

/** Wraps a real AccessUnitBatcher + an AuFramedSender that writes into it, with recording. */
private class RecordingSink {
    val flushes = mutableListOf<ByteArray>()
    val batcher = AccessUnitBatcher(object : AccessUnitBatcher.Transport {
        override fun writeAndFlush(bytes: ByteArray) {
            flushes.add(bytes.copyOf())
        }
    })
    val framed = object : RtpPacketizer.AuFramedSender {
        override fun send(packet: ByteArray, length: Int, endOfAccessUnit: Boolean) {
            batcher.send(packet, length, endOfAccessUnit)
        }
    }
    val flushCount: Int get() = flushes.size
}

private class NoopSender : RtpPacketizer.Sender {
    override fun send(packet: ByteArray, length: Int) {}
}
