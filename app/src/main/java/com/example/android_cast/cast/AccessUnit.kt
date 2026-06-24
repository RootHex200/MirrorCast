package com.example.android_cast.cast

/**
 * One H.264 access unit (the NALs that compose a single picture, in decode order).
 * Tagged with its RTP timestamp origin so the queue and packetizer agree on timing.
 *
 * Pure data — JVM-unit-testable without Android.
 */
data class AccessUnit(
    val nals: List<ByteArray>,
    val presentationTimeUs: Long,
)
