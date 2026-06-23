package com.example.android_cast.cast

/** Lifecycle of a single cast session. */
sealed interface CastState {
    data object Idle : CastState
    data object Connecting : CastState
    data class Streaming(val receiver: Receiver, val fps: Int) : CastState
    data class Paused(val receiver: Receiver) : CastState
    data class Failed(val message: String) : CastState
}

/**
 * Captures the Android screen (MediaProjection in production) and emits a stream of
 * H.264 NAL units toward the receiver. Fake implementations emit synthetic NALs on
 * a clock so the UI and transport are testable without a real MediaProjection.
 */
interface ScreenCaptureEngine {
    /**
     * Begin capturing toward [receiver]. Returns a hot flow of [CastState] transitions
     * for this session. Cancelling the flow stops the capture.
     */
    suspend fun start(receiver: Receiver): kotlinx.coroutines.flow.Flow<CastState>

    /** Stop the current session, if any. Idempotent. */
    fun stop()
}
