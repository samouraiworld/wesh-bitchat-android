package com.bitchat.android.mesh

/**
 * Keeps the service-facing BLE runtime (bridge, gossip and send gate) aligned with the physical
 * manager. A start request survives a temporary radio outage; an explicit pause/stop does not.
 */
internal class BleMeshRuntimeCoordinator(
    private val eligible: () -> Boolean,
    private val activate: () -> Unit,
    private val deactivate: () -> Unit
) {
    private var active = false

    @Synchronized
    fun apply(managerActive: Boolean, startRequested: Boolean): Boolean {
        val shouldBeActive = managerActive && startRequested && eligible()
        if (shouldBeActive == active) return active
        active = shouldBeActive
        if (active) activate() else deactivate()
        return active
    }
}
