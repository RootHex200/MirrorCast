package com.example.android_cast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PairingStore tests use the JVM-only constructor that takes a File. Android Context
 * is mocked out via Robolectric-free path: we use java.io.tmp instead of Context.filesDir.
 */
class PairingStoreTest {

    @Test
    fun `new peer is not paired`() {
        val store = newTmpStore()
        assert(!store.isPaired("p1"))
    }

    @Test
    fun `upsert makes peer paired and persists across new instance`() {
        val file = java.io.File.createTempFile("pairing", ".json").apply { delete() }
        val store = PairingStore(file)
        store.upsert(record("p1"))

        val reloaded = PairingStore(file)
        assertTrue(reloaded.isPaired("p1"))
    }

    @Test
    fun `remove breaks pairing`() {
        val store = newTmpStore()
        store.upsert(record("p1"))
        store.remove("p1")
        assert(!store.isPaired("p1"))
    }

    @Test
    fun `touchLastConnected updates only lastConnectedAtMs`() {
        val store = newTmpStore()
        store.upsert(record("p1"))
        store.touchLastConnected("p1", nowMs = 999_999L)
        val rec = store.all().single()
        assertEquals(999_999L, rec.lastConnectedAtMs)
        assertEquals("p1", rec.peerDeviceID)
    }

    private fun record(id: String) = PairingStore.PairingRecord(
        peerDeviceID = id,
        peerDisplayName = "Mac $id",
        ourDeviceID = "android-self",
        createdAtMs = 0L,
        lastConnectedAtMs = 0L,
    )

    private fun newTmpStore(): PairingStore {
        val file = java.io.File.createTempFile("pairing", ".json").apply { delete() }
        return PairingStore(file)
    }
}

class PairingClientTest {

    @Test
    fun `already paired returns AlreadyPaired without prompting`() {
        val store = PairingStore(java.io.File.createTempFile("pair", ".json").apply { delete() })
        store.upsert(PairingStore.PairingRecord(
            peerDeviceID = "mac1", peerDisplayName = "Mac 1", ourDeviceID = "a",
            createdAtMs = 0, lastConnectedAtMs = 0))
        var prompted = false
        val client = PairingClient(
            store = store,
            transport = object : PairingClient.Transport {
                override fun requestPin(peerDeviceID: String): String? { return "1111" }
                override fun confirmPin(peerDeviceID: String, pin: String): Boolean = true
            },
            promptUser = { _, _ -> prompted = true },
        )

        val outcome = client.pair("mac1", "Mac 1", "a")
        assertEquals(PairingClient.Outcome.AlreadyPaired, outcome)
        assert(!prompted)
    }

    @Test
    fun `correct pin persists pairing record`() {
        val store = PairingStore(java.io.File.createTempFile("pair", ".json").apply { delete() })
        val client = PairingClient(
            store = store,
            transport = object : PairingClient.Transport {
                override fun requestPin(peerDeviceID: String): String? = "4242"
                override fun confirmPin(peerDeviceID: String, pin: String): Boolean = pin == "4242"
            },
            promptUser = { _, onResult -> onResult("4242") },
        )

        val outcome = client.pair("mac1", "Mac 1", "android-self")
        assertTrue(outcome is PairingClient.Outcome.Paired)
        assertTrue(store.isPaired("mac1"))
    }

    @Test
    fun `wrong pin returns Rejected and does not persist`() {
        val store = PairingStore(java.io.File.createTempFile("pair", ".json").apply { delete() })
        val client = PairingClient(
            store = store,
            transport = object : PairingClient.Transport {
                override fun requestPin(peerDeviceID: String): String? = "4242"
                override fun confirmPin(peerDeviceID: String, pin: String): Boolean = false
            },
            promptUser = { _, onResult -> onResult("0000") },
        )

        val outcome = client.pair("mac1", "Mac 1", "android-self")
        assertEquals(PairingClient.Outcome.Rejected, outcome)
        assert(!store.isPaired("mac1"))
    }

    @Test
    fun `user cancel returns Cancelled`() {
        val store = PairingStore(java.io.File.createTempFile("pair", ".json").apply { delete() })
        val client = PairingClient(
            store = store,
            transport = object : PairingClient.Transport {
                override fun requestPin(peerDeviceID: String): String? = "4242"
                override fun confirmPin(peerDeviceID: String, pin: String): Boolean = true
            },
            promptUser = { _, onResult -> onResult(null) },
        )

        val outcome = client.pair("mac1", "Mac 1", "android-self")
        assertEquals(PairingClient.Outcome.Cancelled, outcome)
    }
}
