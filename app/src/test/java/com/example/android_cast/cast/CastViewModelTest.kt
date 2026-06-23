package com.example.android_cast.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State-machine tests for [CastViewModel] driven by [FakeReceiverDiscovery] and
 * [FakeScreenCaptureEngine]. Asserts contracts from issue #3.
 *
 * Tests avoid `advanceUntilIdle` on the streaming flow (which loops forever while
 * `running==true`); instead they advance the virtual clock to a precise point and
 * snapshot state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `receiver list populates immediately when discovery emits`() = runTest(testDispatcher) {
        val discovery = FakeReceiverDiscovery()
        val vm = CastViewModel(discovery = discovery, engine = FakeScreenCaptureEngine())

        assertTrue(vm.receivers.value.isEmpty())

        discovery.emit(Receiver(id = "m1", name = "Mac 1", host = "10.0.0.1", port = 7236))
        assertEquals(1, vm.receivers.value.size)
        assertEquals("Mac 1", vm.receivers.value.first().name)
    }

    @Test
    fun `intermediate connecting state observed before streaming`() = runTest(testDispatcher) {
        val discovery = FakeReceiverDiscovery()
        val engine = FakeScreenCaptureEngine(startLatencyMs = 500L)
        val vm = CastViewModel(discovery = discovery, engine = engine)
        val receiver = Receiver(id = "m1", name = "Mac 1", host = "10.0.0.1", port = 7236)
        discovery.emit(receiver)

        vm.castTo(receiver)
        advanceTimeBy(1L)  // let launch + Connecting emit run
        assertTrue(
            "expected Connecting, was ${vm.state.value}",
            vm.state.value is CastState.Connecting,
        )

        advanceTimeBy(600L)  // past startLatency
        assertTrue(
            "expected Streaming, was ${vm.state.value}",
            vm.state.value is CastState.Streaming,
        )

        engine.stop()
        advanceUntilIdle()
    }

    @Test
    fun `paired receiver carries Last connected semantics`() = runTest(testDispatcher) {
        val discovery = FakeReceiverDiscovery()
        val vm = CastViewModel(discovery = discovery, engine = FakeScreenCaptureEngine())
        discovery.emit(
            Receiver(id = "m1", name = "Mac 1", host = "10.0.0.1", port = 7236, paired = true)
        )
        val receiver = vm.receivers.value.single()
        assertTrue(
            "paired flag set -> UI renders 'Last connected'",
            receiver.paired,
        )
    }

    @Test
    fun `stop returns to idle`() = runTest(testDispatcher) {
        val discovery = FakeReceiverDiscovery()
        val engine = FakeScreenCaptureEngine()
        val vm = CastViewModel(discovery = discovery, engine = engine)
        val receiver = Receiver(id = "m1", name = "Mac 1", host = "10.0.0.1", port = 7236)
        discovery.emit(receiver)

        vm.castTo(receiver)
        advanceTimeBy(1L)
        advanceTimeBy(600L)  // past startLatency
        assertTrue(vm.state.value is CastState.Streaming)

        vm.stop()
        assertTrue(
            "expected Idle, was ${vm.state.value}",
            vm.state.value is CastState.Idle,
        )
    }
}
