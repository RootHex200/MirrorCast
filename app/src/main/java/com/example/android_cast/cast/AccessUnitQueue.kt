package com.example.android_cast.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Bounded producer/consumer queue between the encoder's drain callback and the
 * [AccessUnitBatcher]. Decouples encode from send so a slow socket flush cannot
 * block the encoder's worker (the documented freeze-then-jump root cause).
 *
 * Parameter-set NALs (SPS, PPS) bypass the queue entirely and write through to
 * the batcher synchronously on the producer thread. Dropping them under
 * backpressure would leave the receiver's decoder unable to initialize. Slice
 * AUs go through the channel with [BufferOverflow.DROP_OLDEST] so sustained
 * backpressure drops the oldest pending frame, not the newest — the receiver
 * sees a frame skip rather than a stall.
 *
 * Capacity 3 covers one in-flight send + one queued + headroom for encoder
 * bursts above the configured fps. The batcher is thread-safe (its lock guards
 * the accumulate+flush critical section), so the bypass path and the consumer
 * coroutine can both write without interleave risk.
 *
 * Pure-Java core, JVM-unit-testable without Android.
 */
/** Internal queued item: the AU plus the packetizer instance to send it through. */
private data class Pending(
    val au: AccessUnit,
    val packetizer: RtpPacketizer,
    val ptsUs: Long,
)

class AccessUnitQueue private constructor(
    private val batcher: AccessUnitBatcher,
    private val channel: Channel<Pending>,
) {

    constructor(batcher: AccessUnitBatcher) : this(
        batcher,
        Channel(capacity = 3, onBufferOverflow = BufferOverflow.DROP_OLDEST),
    )

    /**
     * Producer entry point. Inspects [au]'s NAL types:
     *  - any NAL is a parameter set (type 7 SPS or type 8 PPS): write through
     *    to [batcher] synchronously, never queued.
     *  - otherwise: enqueue onto [channel]. DROP_OLDEST if full.
     *
     * Returns true if the AU was accepted (sync bypass or queued); false if
     * the channel is closed and the AU was dropped.
     */
    fun enqueue(au: AccessUnit, packetizer: RtpPacketizer, ptsUs: Long): Boolean {
        val hasParamSet = au.nals.any { it.isNotEmpty() && ((it[0].toInt() and 0x1F) == 7 || (it[0].toInt() and 0x1F) == 8) }
        if (hasParamSet) {
            // Bypass: write through immediately on the producer thread.
            packetizer.sendAccessUnit(au.nals, ptsUs, sink = AuFramedSink(batcher))
            return true
        }
        val result = channel.trySend(Pending(au, packetizer, ptsUs))
        return result.isSuccess
    }

    /** Consumer coroutine: drain [channel] and forward each AU to [batcher]. */
    suspend fun consume() = coroutineScope {
        for (item in channel) {
            item.packetizer.sendAccessUnit(item.au.nals, item.ptsUs, sink = AuFramedSink(batcher))
        }
    }

    /** Close the channel; the consumer's `for` loop will exit after draining. */
    fun close() {
        channel.close()
    }

    /** Adapter so [AccessUnitBatcher] can be used as an [RtpPacketizer.AuFramedSender]. */
    private class AuFramedSink(private val batcher: AccessUnitBatcher) : RtpPacketizer.AuFramedSender {
        override fun send(packet: ByteArray, length: Int, endOfAccessUnit: Boolean) {
            batcher.send(packet, length, endOfAccessUnit)
        }
    }

    companion object {
        /** Run the consumer on Dispatchers.IO (for use inside a runBlocking scope). */
        fun consumerDispatcher() = Dispatchers.IO
    }
}
