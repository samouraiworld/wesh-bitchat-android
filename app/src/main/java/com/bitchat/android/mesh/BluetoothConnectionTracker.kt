package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

/**
 * Tracks all Bluetooth connections and handles cleanup
 */
class BluetoothConnectionTracker(
    private val connectionScope: CoroutineScope,
    private val powerManager: PowerManager,
    private val localPeerID: String = ""
) : MeshConnectionTracker(connectionScope, TAG) {
    
    companion object {
        private const val TAG = "BluetoothConnectionTracker"
    }
    
    // A phone may temporarily have both a client and server GATT link to the same peer. Links are
    // therefore owned by their generation ID until a validated ANNOUNCE elects one of them.
    private val linksByID = ConcurrentHashMap<String, DeviceConnection>()
    private val linkAddressByID = ConcurrentHashMap<String, String>()
    private val addressLinkIDs = ConcurrentHashMap<String, MutableSet<String>>()
    private val selectedLinkByAddress = ConcurrentHashMap<String, String>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    val addressPeerMap = ConcurrentHashMap<String, String>()
    // Track whether we have seen the first ANNOUNCE on a given device connection
    private val firstAnnounceSeen = ConcurrentHashMap<String, Boolean>()
    // RSSI tracking from scan results (for devices we discover but may connect as servers)
    private val scanRSSI = ConcurrentHashMap<String, Int>()
    private val connectionStateLock = Any()
    
    /**
     * Consolidated device connection information
     */
    data class DeviceConnection(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt? = null,
        val characteristic: BluetoothGattCharacteristic? = null,
        val rssi: Int = Int.MIN_VALUE,
        val isClient: Boolean = false,
        val connectedAt: Long = System.currentTimeMillis(),
        val peerID: String? = null,
        /** Unique to this GATT connection, even when Android reuses the device address. */
        val linkID: String = UUID.randomUUID().toString()
    )

    data class LinkObservation(
        val accepted: Boolean,
        /** Links removed from routing which their owning GATT manager must now close. */
        val linksToClose: List<LinkToClose> = emptyList()
    )

    data class LinkToClose(
        val deviceAddress: String,
        val connection: DeviceConnection
    )
    
    override fun start() {
        super.start()
    }
    
    override fun stop() {
        super.stop()
        cleanupAllConnections()
        clearAllConnections()
    }

    // Abstract implementations
    override fun isConnected(id: String): Boolean = synchronized(connectionStateLock) {
        addressLinkIDs[id]?.isNotEmpty() == true
    }
    
    override fun disconnect(id: String) {
        val links = synchronized(connectionStateLock) {
            addressLinkIDs[id].orEmpty().mapNotNull(linksByID::get)
        }
        links.forEach { connection ->
            connection.gatt?.let {
                try {
                    it.disconnect()
                } catch (_: SecurityException) {
                    Log.w(TAG, "Bluetooth permission revoked while disconnecting")
                    closeGatt(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting GATT: ${e.message}")
                    closeGatt(it)
                }
            }
        }
        cleanupDeviceConnection(id)
        Log.d(TAG, "Requested disconnect for $id")
    }

    override fun getConnectionCount(): Int = linksByID.size
    
    /**
     * Add a device connection
     */
    fun addDeviceConnection(deviceAddress: String, deviceConn: DeviceConnection) {
        Log.d(TAG, "Tracker: Adding device connection for $deviceAddress (isClient: ${deviceConn.isClient}")
        synchronized(connectionStateLock) {
            val links = addressLinkIDs.getOrPut(deviceAddress) { linkedSetOf() }
            val wasEmpty = links.isEmpty()
            links += deviceConn.linkID
            linksByID[deviceConn.linkID] = deviceConn
            linkAddressByID[deviceConn.linkID] = deviceAddress
            selectedLinkByAddress.putIfAbsent(deviceAddress, deviceConn.linkID)
            // An address route belongs to an authenticated live generation. A second, still
            // anonymous generation must not erase the route of the first one.
            if (wasEmpty) addressPeerMap.remove(deviceAddress)
        }
        removePendingConnection(deviceAddress)
        // Mark as awaiting first ANNOUNCE on this connection
        firstAnnounceSeen[deviceAddress] = false
    }
    
    /**
     * Track a link accepted by the local GATT server. It remains independent from an outgoing
     * link to the same address until both have authenticated their peer identity.
     */
    fun addServerConnection(deviceAddress: String, deviceConn: DeviceConnection): Boolean {
        addDeviceConnection(deviceAddress, deviceConn)
        return true
    }

    /**
     * Update a device connection
     */
    fun updateDeviceConnection(deviceAddress: String, deviceConn: DeviceConnection) {
        synchronized(connectionStateLock) {
            if (addressLinkIDs[deviceAddress]?.contains(deviceConn.linkID) == true) {
                linksByID[deviceConn.linkID] = deviceConn
            }
        }
    }

    fun updateDeviceConnectionIfCurrent(
        deviceAddress: String,
        linkID: String,
        update: (DeviceConnection) -> DeviceConnection
    ): Boolean = synchronized(connectionStateLock) {
        if (addressLinkIDs[deviceAddress]?.contains(linkID) != true) return@synchronized false
        val current = linksByID[linkID] ?: return@synchronized false
        linksByID[linkID] = update(current)
        true
    }
    
    /**
     * Get a device connection
     */
    fun getDeviceConnection(deviceAddress: String): DeviceConnection? {
        return synchronized(connectionStateLock) {
            selectedLinkByAddress[deviceAddress]?.let(linksByID::get)
        }
    }

    fun getCurrentLinkID(deviceAddress: String): String? =
        synchronized(connectionStateLock) { selectedLinkByAddress[deviceAddress] }

    fun hasLink(deviceAddress: String, linkID: String): Boolean = synchronized(connectionStateLock) {
        addressLinkIDs[deviceAddress]?.contains(linkID) == true && linksByID.containsKey(linkID)
    }

    fun getLink(deviceAddress: String, linkID: String): DeviceConnection? = synchronized(connectionStateLock) {
        if (addressLinkIDs[deviceAddress]?.contains(linkID) == true) linksByID[linkID] else null
    }

    fun getPeerForLink(linkID: String): String? = linksByID[linkID]?.peerID

    /**
     * Records that the current link delivered a validated, non-relayed ANNOUNCE for [peerID].
     *
     * A peer may be reachable over more than one link, so observing one link must not discard the
     * other observations. The link generation check prevents a late packet from an old GATT
     * connection from being applied to a replacement connection that reused the same address.
     */
    fun observePeerIfCurrent(deviceAddress: String, linkID: String, peerID: String): LinkObservation =
        synchronized(connectionStateLock) {
            if (addressLinkIDs[deviceAddress]?.contains(linkID) != true) {
                return@synchronized LinkObservation(false)
            }
            val current = linksByID[linkID] ?: return@synchronized LinkObservation(false)
            val normalizedPeer = peerID.lowercase()

            // A remote claiming our identity is never allowed to influence routing or tie-breaks.
            if (localPeerID.isNotBlank() && normalizedPeer == localPeerID.lowercase()) {
                removeLinkLocked(deviceAddress, linkID)
                return@synchronized LinkObservation(false, listOf(LinkToClose(deviceAddress, current)))
            }

            // The same live Android address cannot change authenticated identity. This also stops
            // a delayed ANNOUNCE from an old generation rewriting a replacement route.
            val conflict = addressLinkIDs[deviceAddress].orEmpty()
                .asSequence()
                .filter { it != linkID }
                .mapNotNull(linksByID::get)
                .any { it.peerID != null && it.peerID.lowercase() != normalizedPeer }
            if (conflict) {
                removeLinkLocked(deviceAddress, linkID)
                return@synchronized LinkObservation(false, listOf(LinkToClose(deviceAddress, current)))
            }

            linksByID[linkID] = current.copy(peerID = normalizedPeer)
            val candidates = linksByID.values.filter { it.peerID?.lowercase() == normalizedPeer }
            val hasBothRoles = candidates.any { it.isClient } && candidates.any { !it.isClient }
            val desiredClient = localPeerID.isNotBlank() && localPeerID.lowercase() < normalizedPeer
            // Peer IDs choose the direction, which is symmetric across the two phones. Multiple
            // generations of the chosen direction remain independently tracked: their local
            // timestamps and link IDs are not shared and therefore cannot safely break a tie.
            val losers = if (hasBothRoles && localPeerID.isNotBlank()) {
                candidates.filter { it.isClient != desiredClient }
            } else {
                emptyList()
            }
            val linksToClose = losers.mapNotNull { loser ->
                linkAddressByID[loser.linkID]?.let { LinkToClose(it, loser) }
            }
            linksToClose.forEach { removeLinkLocked(it.deviceAddress, it.connection.linkID) }
            val keepers = candidates.filter { candidate -> losers.none { it.linkID == candidate.linkID } }
            keepers.forEach { keeper ->
                val address = linkAddressByID[keeper.linkID] ?: return@forEach
                selectedLinkByAddress[address] = keeper.linkID
                refreshAddressPeerLocked(address)
            }
            LinkObservation(losers.none { it.linkID == linkID }, linksToClose)
        }
    
    /**
     * Get all connected devices
     */
    fun getConnectedDevices(): Map<String, DeviceConnection> {
        return synchronized(connectionStateLock) {
            selectedLinkByAddress.mapNotNull { (address, linkID) ->
                linksByID[linkID]?.let { address to it }
            }.toMap()
        }
    }

    fun getAllDeviceConnections(): List<DeviceConnection> = linksByID.values.toList()

    fun getAllTrackedLinks(): List<LinkToClose> = synchronized(connectionStateLock) {
        linksByID.values.mapNotNull { connection ->
            linkAddressByID[connection.linkID]?.let { LinkToClose(it, connection) }
        }
    }
    
    /**
     * Get subscribed devices (for server connections)
     */
    fun getSubscribedDevices(): List<BluetoothDevice> {
        return subscribedDevices.toList()
    }
    
    /**
     * Get current RSSI for a device address
     */
    fun getDeviceRSSI(deviceAddress: String): Int? {
        return getDeviceConnection(deviceAddress)?.rssi?.takeIf { it != Int.MIN_VALUE }
    }
    
    /**
     * Store RSSI from scan results
     */
    fun updateScanRSSI(deviceAddress: String, rssi: Int) {
        scanRSSI[deviceAddress] = rssi
    }
    
    /**
     * Get best available RSSI for a device (connection RSSI preferred, then scan RSSI)
     */
    fun getBestRSSI(deviceAddress: String): Int? {
        // Prefer connection RSSI if available and valid
        synchronized(connectionStateLock) {
            addressLinkIDs[deviceAddress].orEmpty().mapNotNull(linksByID::get)
                .map { it.rssi }.filter { it != Int.MIN_VALUE }.maxOrNull()
        }?.let { return it }
        
        // Fall back to scan RSSI
        return scanRSSI[deviceAddress]
    }
    
    /**
     * Add a subscribed device
     */
    fun addSubscribedDevice(device: BluetoothDevice) {
        subscribedDevices.removeAll { it.address == device.address }
        subscribedDevices.add(device)
    }
    
    /**
     * Remove a subscribed device
     */
    fun removeSubscribedDevice(device: BluetoothDevice) {
        subscribedDevices.removeAll { it.address == device.address }
    }
    
    /**
     * Check if device is already connected
     */
    fun isDeviceConnected(deviceAddress: String): Boolean = isConnected(deviceAddress)

    /**
     * Check if a peer is already connected (by PeerID)
     */
    fun isPeerConnected(peerID: String): Boolean {
        // Only consider actual connected devices that have identified themselves
        return linksByID.values.any { it.peerID == peerID }
    }
    
    /**
     * Disconnect a specific device (by MAC address)
     */
    fun disconnectDevice(deviceAddress: String) = disconnect(deviceAddress)
    
    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = getConnectionCount()
    
    /**
     * Check if connection limit is reached
     */
    /**
     * Check if a new client connection is allowed based on limits
     */
    fun canConnectAsClient(maxOverall: Int, maxClient: Int): Boolean {
        val all = linksByID.values.toList()
        val total = all.size
        val clients = all.count { it.isClient }
        return total < maxOverall && clients < maxClient
    }
    
    /**
     * Calculate which connections should be evicted to satisfy limits.
     * Logic:
     * 1. Enforce strict role limits (maxClient, maxServer) - evict oldest excess.
     * 2. Enforce overall limit (maxOverall) - evict oldest remaining, preferring clients.
     */
    fun getConnectionsToEvict(maxOverall: Int, maxServer: Int, maxClient: Int): List<DeviceConnection> {
        val toEvict = mutableSetOf<DeviceConnection>()
        val currentDevices = linksByID.values.toList()
        
        // 1. Enforce Role Limits
        val clients = currentDevices.filter { it.isClient }.sortedBy { it.connectedAt }
        if (clients.size > maxClient) {
            toEvict.addAll(clients.take(clients.size - maxClient))
        }
        
        val servers = currentDevices.filter { !it.isClient }.sortedBy { it.connectedAt }
        if (servers.size > maxServer) {
            toEvict.addAll(servers.take(servers.size - maxServer))
        }
        
        // 2. Enforce Overall Limit
        // Count how many would remain after the above evictions
        val remaining = currentDevices.filter { !toEvict.contains(it) }
        if (remaining.size > maxOverall) {
            val excessCount = remaining.size - maxOverall
            
            // Explicitly prefer evicting clients first
            val clientCandidates = remaining.filter { it.isClient }.sortedBy { it.connectedAt }
            val serverCandidates = remaining.filter { !it.isClient }.sortedBy { it.connectedAt }
            
            var needed = excessCount
            
            // Take from clients first
            val fromClients = clientCandidates.take(needed)
            toEvict.addAll(fromClients)
            needed -= fromClients.size
            
            // If still need more, take from servers
            if (needed > 0) {
                val fromServers = serverCandidates.take(needed)
                toEvict.addAll(fromServers)
            }
        }
        
        return toEvict.toList()
    }
    
    /**
     * Clean up a specific device connection
     */
    fun cleanupDeviceConnection(deviceAddress: String) {
        synchronized(connectionStateLock) {
            addressLinkIDs.remove(deviceAddress).orEmpty().forEach { linkID ->
                linksByID.remove(linkID)
                linkAddressByID.remove(linkID)
            }
            selectedLinkByAddress.remove(deviceAddress)
            subscribedDevices.removeAll { it.address == deviceAddress }
            addressPeerMap.remove(deviceAddress)
            firstAnnounceSeen.remove(deviceAddress)
        }
        Log.d(TAG, "Cleaned up device connection for $deviceAddress")
    }

    fun cleanupDeviceConnectionIfCurrent(
        deviceAddress: String,
        expectedLinkID: String
    ): Boolean = synchronized(connectionStateLock) {
        if (!removeLinkLocked(deviceAddress, expectedLinkID)) return@synchronized false
        Log.d(TAG, "Cleaned up link $expectedLinkID for $deviceAddress")
        true
    }

    private fun removeLinkLocked(deviceAddress: String, linkID: String): Boolean {
        if (addressLinkIDs[deviceAddress]?.remove(linkID) != true) return false
        linksByID.remove(linkID)
        linkAddressByID.remove(linkID)
        val remaining = addressLinkIDs[deviceAddress]
        if (remaining.isNullOrEmpty()) {
            addressLinkIDs.remove(deviceAddress)
            selectedLinkByAddress.remove(deviceAddress)
            subscribedDevices.removeAll { it.address == deviceAddress }
            firstAnnounceSeen.remove(deviceAddress)
        } else if (selectedLinkByAddress[deviceAddress] == linkID) {
            selectedLinkByAddress[deviceAddress] = remaining.mapNotNull(linksByID::get)
                .sortedWith(compareByDescending<DeviceConnection> { it.peerID != null }
                    .thenBy { it.connectedAt }.thenBy { it.linkID })
                .first().linkID
        }
        refreshAddressPeerLocked(deviceAddress)
        return true
    }

    private fun refreshAddressPeerLocked(deviceAddress: String) {
        val selected = selectedLinkByAddress[deviceAddress]?.let(linksByID::get)
        val peer = selected?.peerID
        if (peer == null) addressPeerMap.remove(deviceAddress) else addressPeerMap[deviceAddress] = peer
    }
    
    /**
     * Clean up all connections
     */
    private fun cleanupAllConnections() {
        // Terminal cleanup must finish before the owner cancels the scope. Retain each GATT
        // until close has been attempted, even when permission was revoked during disconnect.
        linksByID.values.toList().forEach { deviceConn ->
            val gatt = deviceConn.gatt ?: return@forEach
            try {
                gatt.disconnect()
            } catch (_: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked while disconnecting")
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting GATT during cleanup: ${e.message}")
            }
            closeGatt(gatt)
        }
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while closing GATT")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT during cleanup: ${e.message}")
        }
    }

    /**
     * Clear all connection tracking
     */
    private fun clearAllConnections() {
        linksByID.clear()
        linkAddressByID.clear()
        addressLinkIDs.clear()
        selectedLinkByAddress.clear()
        subscribedDevices.clear()
        addressPeerMap.clear()
        pendingConnections.clear()
        scanRSSI.clear()
        firstAnnounceSeen.clear()
    }

    /**
     * Mark that we have received the first ANNOUNCE over this device connection.
     */
    fun noteAnnounceReceived(deviceAddress: String) {
        firstAnnounceSeen[deviceAddress] = true
    }

    /**
     * Check whether the first ANNOUNCE has been seen for a device connection.
     */
    fun hasSeenFirstAnnounce(deviceAddress: String): Boolean {
        return firstAnnounceSeen[deviceAddress] == true
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("Connected Links: ${linksByID.size} / ${powerManager.getMaxConnections()}")
            linksByID.values.forEach { deviceConn ->
                val address = linkAddressByID[deviceConn.linkID] ?: "unknown"
                val age = (System.currentTimeMillis() - deviceConn.connectedAt) / 1000
                appendLine("  - $address (we're ${if (deviceConn.isClient) "client" else "server"}, ${age}s, RSSI: ${deviceConn.rssi})")
            }
            appendLine()
            appendLine("Subscribed Devices (server mode): ${subscribedDevices.size}")
            appendLine()
            appendLine("Pending Connections: ${pendingConnections.size}")
            val now = System.currentTimeMillis()
            pendingConnections.forEach { (address, attempt) ->
                val elapsed = (now - attempt.lastAttempt) / 1000
                appendLine("  - $address: ${attempt.attempts} attempts, last ${elapsed}s ago")
            }
            appendLine()
            appendLine("Scan RSSI Cache: ${scanRSSI.size}")
            scanRSSI.forEach { (address, rssi) ->
                appendLine("  - $address: $rssi dBm")
            }
        }
    }
} 
