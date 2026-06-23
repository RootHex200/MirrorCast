package com.example.android_cast.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process fake discovery. Tests call [emit] / [remove] / [clear] on a controlled
 * clock to drive the UI deterministically. The fake intentionally does NOT do any
 * networking — that's the point of the seam.
 */
class FakeReceiverDiscovery : ReceiverDiscovery {

    private val _receivers = MutableStateFlow<List<Receiver>>(emptyList())
    override val receivers: StateFlow<List<Receiver>> = _receivers.asStateFlow()

    override fun start() {}

    override fun stop() {
        _receivers.value = emptyList()
    }

    fun emit(receiver: Receiver) {
        val current = _receivers.value.toMutableList()
        val idx = current.indexOfFirst { it.id == receiver.id }
        if (idx >= 0) current[idx] = receiver else current.add(receiver)
        _receivers.value = current
    }

    fun remove(id: String) {
        _receivers.value = _receivers.value.filterNot { it.id == id }
    }

    fun clear() {
        _receivers.value = emptyList()
    }
}
