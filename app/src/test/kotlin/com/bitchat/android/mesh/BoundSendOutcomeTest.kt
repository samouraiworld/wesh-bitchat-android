package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundSendOutcomeTest {
    @Test
    fun `only a refused first fragment with the session intact lets another route take the message`() {
        assertEquals(BoundSendOutcome.NOT_SENT, BoundSendOutcome.afterFragment(0, accepted = false, stillAuthorized = true))
        assertNull(BoundSendOutcome.afterFragment(0, accepted = true, stillAuthorized = true))
    }

    @Test
    fun `a timeout or a later fragment failing may have left bytes`() {
        assertEquals(BoundSendOutcome.UNCONFIRMED, BoundSendOutcome.afterFragment(0, accepted = null, stillAuthorized = true))
        assertEquals(BoundSendOutcome.UNCONFIRMED, BoundSendOutcome.afterFragment(2, accepted = null, stillAuthorized = true))
        assertEquals(BoundSendOutcome.UNCONFIRMED, BoundSendOutcome.afterFragment(2, accepted = false, stillAuthorized = true))
    }

    @Test
    fun `a session lost mid-send is no identity change, only the caller decides that`() {
        assertEquals(BoundSendOutcome.NOT_SENT, BoundSendOutcome.afterFragment(0, accepted = false, stillAuthorized = false))
        assertEquals(BoundSendOutcome.UNCONFIRMED, BoundSendOutcome.afterFragment(1, accepted = true, stillAuthorized = false))
        assertEquals(BoundSendOutcome.NOT_SENT, BoundSendOutcome.beforeFragment(0))
        assertEquals(BoundSendOutcome.UNCONFIRMED, BoundSendOutcome.beforeFragment(2))
    }
}
