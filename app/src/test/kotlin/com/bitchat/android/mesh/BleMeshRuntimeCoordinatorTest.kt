package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleMeshRuntimeCoordinatorTest {
    @Test
    fun `cold start waits then active callback opens bridge gossip and send gate once`() {
        val events = mutableListOf<String>()
        val coordinator = BleMeshRuntimeCoordinator(
            eligible = { true },
            activate = { events += listOf("send-open", "bridge-register", "gossip-start") },
            deactivate = { events += listOf("send-close", "gossip-stop", "bridge-unregister") }
        )

        assertFalse(coordinator.apply(managerActive = false, startRequested = true))
        assertTrue(coordinator.apply(managerActive = true, startRequested = true))
        assertTrue(coordinator.apply(managerActive = true, startRequested = true))
        assertEquals(listOf("send-open", "bridge-register", "gossip-start"), events)
    }

    @Test
    fun `radio outage can reactivate requested runtime but explicit stop cannot`() {
        var activations = 0
        var deactivations = 0
        val coordinator = BleMeshRuntimeCoordinator(
            eligible = { true },
            activate = { activations += 1 },
            deactivate = { deactivations += 1 }
        )

        assertTrue(coordinator.apply(managerActive = true, startRequested = true))
        assertFalse(coordinator.apply(managerActive = false, startRequested = true))
        assertTrue(coordinator.apply(managerActive = true, startRequested = true))
        assertFalse(coordinator.apply(managerActive = false, startRequested = false))
        assertFalse(coordinator.apply(managerActive = true, startRequested = false))
        assertEquals(2, activations)
        assertEquals(2, deactivations)
    }
}
