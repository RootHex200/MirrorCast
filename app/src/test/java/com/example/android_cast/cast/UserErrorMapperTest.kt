package com.example.android_cast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserErrorMapperTest {

    @Test
    fun `pin rejection surfaces PinRejected`() {
        val err = UserErrorMapper.from(
            state = CastState.Idle,
            pairingOutcome = PairingClient.Outcome.Rejected,
        )
        assertTrue(err is UserError.PinRejected)
        assertTrue(err!!.retryable)
    }

    @Test
    fun `pairing cancelled returns null`() {
        val err = UserErrorMapper.from(
            state = CastState.Idle,
            pairingOutcome = PairingClient.Outcome.Cancelled,
        )
        assertNull(err)
    }

    @Test
    fun `session dropped state maps to SessionDropped`() {
        val err = UserErrorMapper.from(
            state = CastState.Failed("session dropped"),
        )
        assertTrue(err is UserError.SessionDropped)
    }

    @Test
    fun `peer lost state maps to PeerLost`() {
        val err = UserErrorMapper.from(
            state = CastState.Failed("peer lost"),
        )
        assertTrue(err is UserError.PeerLost)
    }

    @Test
    fun `unknown failure message maps to Generic`() {
        val err = UserErrorMapper.from(
            state = CastState.Failed("oops"),
        ) as UserError.Generic
        assertEquals("oops", err.body)
    }

    @Test
    fun `idle state with no pairing outcome returns null`() {
        assertNull(UserErrorMapper.from(CastState.Idle))
    }
}
