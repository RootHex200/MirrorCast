package com.example.android_cast.cast

/**
 * Accumulates framed-RTP bytes for one access unit and flushes them to the
 * underlying transport in a single write+flush call when the AU ends.
 *
 * Thread-safe: the cast pipeline writes to the batcher from two paths — the
 * synchronous parameter-set bypass (encoder's Dispatchers.Default worker)
 * and the channel-drain consumer coroutine (Dispatchers.IO). A lock guards
 * the accumulate+flush critical section so two writers can never interleave
 * framed-RTP bytes from different access units.
 *
 * Pure-Java core, JVM-unit-testable without Android.
 */
class AccessUnitBatcher(private val transport: Transport) {

    /** Underlying byte sink. Real impl wraps a TCP socket; tests use a recording fake. */
    fun interface Transport {
        fun writeAndFlush(bytes: ByteArray)
    }

    private val lock = Any()
    private val buffer = java.io.ByteArrayOutputStream()

    /**
     * Append [length] bytes from [packet] to the current AU's buffer.
     * When [endOfAccessUnit] is true, flush the accumulated bytes in a single
     * transport write+flush and reset the buffer for the next AU.
     */
    fun send(packet: ByteArray, length: Int, endOfAccessUnit: Boolean) {
        synchronized(lock) {
            buffer.write(packet, 0, length)
            if (endOfAccessUnit) {
                flushLocked()
            }
        }
    }

    /** Force-flush any pending bytes (e.g. on session stop). Safe to call when empty. */
    fun flush() {
        synchronized(lock) {
            flushLocked()
        }
    }

    private fun flushLocked() {
        if (buffer.size() == 0) return
        val bytes = buffer.toByteArray()
        buffer.reset()
        transport.writeAndFlush(bytes)
    }
}
