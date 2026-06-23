package com.example.android_cast.cast

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fake engine that walks the state machine `idle → connecting → streaming`,
 * emits synthetic H.264 NAL units on a clock, and never touches a real codec.
 *
 * The fake honours an injected [startLatencyMs] and [framePeriodMs] so tests can
 * assert "list populates within 3s" and similar timing claims deterministically.
 */
class FakeScreenCaptureEngine(
    private val startLatencyMs: Long = 400L,
    private val framePeriodMs: Long = 33L,
    private val fps: Int = 30,
) : ScreenCaptureEngine {

    @Volatile
    private var running: Boolean = false

    /** Number of synthetic NALs emitted since the last [start]. Tests assert on this. */
    @Volatile
    var emittedNalCount: Int = 0
        private set

    override suspend fun start(receiver: Receiver): Flow<CastState> = flow {
        require(!running) { "FakeScreenCaptureEngine is single-session; call stop() first" }
        running = true
        emittedNalCount = 0
        emit(CastState.Connecting)
        delay(startLatencyMs)
        emit(CastState.Streaming(receiver = receiver, fps = fps))
        while (running) {
            // Synthesize a single-byte "NAL" — not a real H.264 NAL, just a clock tick
            // the fake transport can count. Real NALs come from MediaCodec in issue #4.
            emittedNalCount += 1
            delay(framePeriodMs)
        }
        emit(CastState.Idle)
    }

    override fun stop() {
        running = false
    }
}
