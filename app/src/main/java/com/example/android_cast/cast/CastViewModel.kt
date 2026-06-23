package com.example.android_cast.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Wires [ReceiverDiscovery] and [ScreenCaptureEngine] to Compose state. Drives the
 * `idle → connecting → streaming` state machine in response to taps on a receiver.
 */
class CastViewModel(
    private val discovery: ReceiverDiscovery,
    private val engine: ScreenCaptureEngine,
) : ViewModel() {

    val receivers: StateFlow<List<Receiver>> = discovery.receivers

    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state.asStateFlow()

    private var sessionJob: Job? = null

    init {
        discovery.start()
    }

    /** User tapped a receiver in the list. */
    fun castTo(receiver: Receiver) {
        if (sessionJob != null) return
        sessionJob = viewModelScope.launch {
            engine.start(receiver).collect { state ->
                _state.value = state
            }
            sessionJob = null
        }
    }

    /** User tapped "Stop casting". */
    fun stop() {
        engine.stop()
        sessionJob?.cancel()
        sessionJob = null
        _state.value = CastState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
        discovery.stop()
    }
}
