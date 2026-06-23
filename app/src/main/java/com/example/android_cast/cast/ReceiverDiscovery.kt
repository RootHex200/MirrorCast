package com.example.android_cast.cast

import kotlinx.coroutines.flow.StateFlow

/** A receiver discovered on the local network. */
data class Receiver(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val paired: Boolean = false,
)

/**
 * Discovers MirrorCast receivers on the local network (mDNS / NSD in production).
 * Implementations push updates into [receivers]; UIs collect that StateFlow.
 *
 * Fake implementation is suitable for unit testing the UI without real NsdManager.
 */
interface ReceiverDiscovery {
    /** Hot stream of currently-known receivers, keyed by id. */
    val receivers: StateFlow<List<Receiver>>

    /** Begin browsing. Idempotent. */
    fun start()

    /** Stop browsing and clear discovered receivers. */
    fun stop()
}
