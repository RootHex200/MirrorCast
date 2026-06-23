package com.example.android_cast.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Records every writeAndFlush call. */
private class RecordingTransport : AccessUnitBatcher.Transport {
    val flushes = mutableListOf<ByteArray>()
    var throwOnNext = false

    override fun writeAndFlush(bytes: ByteArray) {
        if (throwOnNext) {
            throwOnNext = false
            throw java.io.IOException("simulated transport failure")
        }
        flushes.add(bytes.copyOf())
    }
}

class AccessUnitBatcherTest {

    @Test
    fun `three packets in one AU produce one flush with concatenated bytes`() {
        val t = RecordingTransport()
        val b = AccessUnitBatcher(t)

        b.send(byteArrayOf(1, 2), 2, endOfAccessUnit = false)
        b.send(byteArrayOf(3, 4), 2, endOfAccessUnit = false)
        b.send(byteArrayOf(5, 6), 2, endOfAccessUnit = true)

        assertEquals(1, t.flushes.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), t.flushes[0])
    }

    @Test
    fun `single-packet AU produces one flush`() {
        val t = RecordingTransport()
        val b = AccessUnitBatcher(t)

        b.send(byteArrayOf(7, 8, 9), 3, endOfAccessUnit = true)

        assertEquals(1, t.flushes.size)
        assertArrayEquals(byteArrayOf(7, 8, 9), t.flushes[0])
    }

    @Test
    fun `two AUs produce two flushes in order`() {
        val t = RecordingTransport()
        val b = AccessUnitBatcher(t)

        b.send(byteArrayOf(1), 1, endOfAccessUnit = false)
        b.send(byteArrayOf(2), 1, endOfAccessUnit = true)
        b.send(byteArrayOf(3), 1, endOfAccessUnit = false)
        b.send(byteArrayOf(4), 1, endOfAccessUnit = true)

        assertEquals(2, t.flushes.size)
        assertArrayEquals(byteArrayOf(1, 2), t.flushes[0])
        assertArrayEquals(byteArrayOf(3, 4), t.flushes[1])
    }

    @Test
    fun `flush with no pending bytes is a no-op`() {
        val t = RecordingTransport()
        val b = AccessUnitBatcher(t)

        b.flush()

        assertEquals(0, t.flushes.size)
    }

    @Test
    fun `transport throw leaves batcher clean for next AU`() {
        val t = RecordingTransport()
        val b = AccessUnitBatcher(t)

        b.send(byteArrayOf(1, 2), 2, endOfAccessUnit = false)
        t.throwOnNext = true

        try {
            b.send(byteArrayOf(3, 4), 2, endOfAccessUnit = true)
        } catch (_: java.io.IOException) {
            // expected
        }

        // Next AU must flush cleanly without leaking the previous AU's bytes.
        b.send(byteArrayOf(9), 1, endOfAccessUnit = true)

        assertEquals(1, t.flushes.size)
        assertArrayEquals(byteArrayOf(9), t.flushes[0])
    }

    @Test
    fun `concurrent writers do not interleave AU bytes`() {
        val t = RecordingTransport()
        val b = AccessUnitBatcher(t)

        val n = 50
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        repeat(n) { i ->
            thread {
                start.await()
                val payload = ByteArray(100) { (i + 1).toByte() }
                b.send(payload, payload.size, endOfAccessUnit = true)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("concurrent writers timed out", done.await(5, TimeUnit.SECONDS))

        // Every flushed batch must be exactly 100 copies of a single sender index —
        // no interleaving across writers.
        assertEquals(n, t.flushes.size)
        for (batch in t.flushes) {
            assertEquals(100, batch.size)
            val v = batch[0]
            for (b2 in batch) assertEquals(v, b2)
        }
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
