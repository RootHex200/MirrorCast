package com.example.android_cast.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Bridges injected [ReceiverDiscovery] + [ScreenCaptureEngine] into [CastViewModel]. */
class CastViewModelFactory(
    private val discovery: ReceiverDiscovery,
    private val engine: ScreenCaptureEngine,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == CastViewModel::class.java) { "Unknown ViewModel: $modelClass" }
        return CastViewModel(discovery, engine) as T
    }
}
