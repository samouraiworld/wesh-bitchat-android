
package com.bitchat.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.actor
import java.util.ArrayDeque

/**
 * Handles packet broadcasting to connected devices using actor pattern for serialization
 * 
 * In Bluetooth Low Energy (BLE):
 *
 * Peripheral (server):
 * Advertises.
 * Accepts connections.
 * Hosts a GATT server.
 * Remote devices read/write/subscribe to characteristics.
 *
 *  Central (client):
 * Scans.
 * Initiates connections.
 * Hosts a GATT client.
 * Reads/writes to the peripheral’s characteristics.
 */
class BluetoothPacketBroadcaster(
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val fragmentManager: FragmentManager?,
    private val myPeerID: String,
    private val gattCallbackTimeoutMs: Long = GATT_CALLBACK_TIMEOUT_MS,
    private val afterGattApiResultForTesting: ((Boolean) -> Unit)? = null
) {
    
    companion object {
        private const val TAG = "BluetoothPacketBroadcaster"
        private const val MAX_PENDING_SENDS_PER_LINK = 256
        private const val MAX_PENDING_BYTES_PER_LINK = 1_048_576
        private const val SEND_RETRY_DELAY_MS = 15L
        private const val MAX_CALLBACK_RETRIES = 3
        private const val GATT_CALLBACK_TIMEOUT_MS = 5_000L
    }

    // Optional nickname resolver injected by higher layer (peerID -> nickname?)
    private var nicknameResolver: ((String) -> String?)? = null

    fun setNicknameResolver(resolver: (String) -> String?) {
        nicknameResolver = resolver
    }
    
    /**
     * Debug logging helper - can be easily removed/disabled for production
     */
    private fun logPacketRelay(
        typeName: String,
        senderPeerID: String,
        senderNick: String?,
        incomingPeer: String?,
        incomingAddr: String?,
        toPeer: String?,
        toDeviceAddress: String,
        ttl: UByte,
        packetVersion: UByte = 1u,
        routeInfo: String? = null
    ) {
        try {
            val fromNick = incomingPeer?.let { nicknameResolver?.invoke(it) }
            val toNick = toPeer?.let { nicknameResolver?.invoke(it) }
            val manager = com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
            // Always log outgoing for the actual transmission target
            manager.logOutgoing(
                packetType = typeName,
                toPeerID = toPeer,
                toNickname = toNick,
                toDeviceAddress = toDeviceAddress,
                previousHopPeerID = incomingPeer,
                packetVersion = packetVersion,
                routeInfo = routeInfo
            )
            // Keep the verbose relay message for human readability
            manager.logPacketRelayDetailed(
                packetType = typeName,
                senderPeerID = senderPeerID,
                senderNickname = senderNick,
                fromPeerID = incomingPeer,
                fromNickname = fromNick,
                fromDeviceAddress = incomingAddr,
                toPeerID = toPeer,
                toNickname = toNick,
                toDeviceAddress = toDeviceAddress,
                ttl = ttl,
                isRelay = true,
                packetVersion = packetVersion,
                routeInfo = routeInfo
            )
        } catch (_: Exception) { 
            // Silently ignore debug logging failures
        }
    }
    
    // Data class to hold broadcast request information
    private data class BroadcastRequest(
        val routed: RoutedPacket,
        val gattServer: BluetoothGattServer?,
        val characteristic: BluetoothGattCharacteristic?,
        val accepted: CompletableDeferred<GattOperationResult>? = null,
        val actualWrites: Boolean = false,
        val authorized: () -> Boolean = { true },
        val handoffEvidence: GattHandoffEvidence = GattHandoffEvidence()
    )
    
    // Actor scope for the broadcaster
    private val broadcasterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fragmentingSender = FragmentingPacketSender(connectionScope, fragmentManager, TAG)

    private enum class SendDirection { CLIENT_WRITE, SERVER_NOTIFICATION }

    /** What the exact GATT operation established, without conflating API and callback success. */
    private enum class GattOperationResult {
        /** The platform API refused the operation before it could enter the Bluetooth stack. */
        REJECTED,
        /** The platform callback confirmed successful completion. */
        COMPLETED,
        /** The API accepted it, but its callback failed, disappeared, or the link fell. */
        UNCONFIRMED;

        val completed: Boolean get() = this == COMPLETED

        fun merge(other: GattOperationResult): GattOperationResult = when {
            this == UNCONFIRMED || other == UNCONFIRMED -> UNCONFIRMED
            this == COMPLETED || other == COMPLETED -> COMPLETED
            else -> REJECTED
        }
    }

    private class GattHandoffEvidence {
        private var acceptedCalls = 0
        private var pendingCalls = 0

        @Synchronized fun beginApiCall() { pendingCalls++ }

        @Synchronized fun finishApiCall(accepted: Boolean) {
            pendingCalls = (pendingCalls - 1).coerceAtLeast(0)
            if (accepted) acceptedCalls++
        }

        @Synchronized fun failureResult(): GattOperationResult =
            if (acceptedCalls > 0 || pendingCalls > 0) GattOperationResult.UNCONFIRMED
            else GattOperationResult.REJECTED
    }

    private enum class GattApiState { NOT_STARTED, API_PENDING, ACCEPTED, REJECTED }

    private data class SendKey(
        val deviceAddress: String,
        val linkID: String,
        val direction: SendDirection
    )

    private data class PendingSend(
        val data: ByteArray,
        val device: BluetoothDevice,
        val gatt: BluetoothGatt? = null,
        val gattServer: BluetoothGattServer? = null,
        val characteristic: BluetoothGattCharacteristic,
        var callbackFailures: Int = 0,
        val accepted: CompletableDeferred<GattOperationResult>? = null,
        val handoffEvidence: GattHandoffEvidence = GattHandoffEvidence(),
        var apiState: GattApiState = GattApiState.NOT_STARTED,
        var deferredCallbackStatus: Int? = null,
        val authorized: () -> Boolean = { true }
    ) {
        fun failureResult(): GattOperationResult = handoffEvidence.failureResult()
    }

    private class LinkSendState {
        val pending = ArrayDeque<PendingSend>()
        var pendingBytes = 0
        var inFlight = false
        var retryScheduled = false
        var operationGeneration = 0L
        var callbackTimeout: Job? = null
    }

    private val sendLock = Any()
    private val sendStates = mutableMapOf<SendKey, LinkSendState>()
    @Volatile private var linkTimeoutHandler: ((deviceAddress: String, linkID: String, isClient: Boolean, device: BluetoothDevice) -> Unit)? = null

    fun setLinkTimeoutHandler(
        handler: (deviceAddress: String, linkID: String, isClient: Boolean, device: BluetoothDevice) -> Unit
    ) {
        linkTimeoutHandler = handler
    }
    
    // SERIALIZATION: Actor to serialize all broadcast operations
    @OptIn(kotlinx.coroutines.ObsoleteCoroutinesApi::class)
    private val broadcasterActor = broadcasterScope.actor<BroadcastRequest>(
        capacity = 256
    ) {
        for (request in channel) {
            val result = try {
                broadcastSinglePacketInternal(
                    request.routed,
                    request.gattServer,
                    request.characteristic, request.actualWrites,
                    { request.accepted?.isCancelled != true && request.authorized() },
                    request.handoffEvidence
                )
            } catch (e: Exception) {
                Log.w(TAG, "Broadcast request failed: ${e.message}")
                request.handoffEvidence.failureResult()
            }
            request.accepted?.complete(result)
        }
    }
    
    fun broadcastPacket(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        return fragmentingSender.send(routed, "BLE broadcast") { packet ->
            broadcastSinglePacket(packet, gattServer, characteristic)
            true
        }
    }

    /** Wesh texts keep the existing bounded fragment plan and report completed GATT operations. */
    suspend fun broadcastPacketAndAwaitAcceptance(routed: RoutedPacket, gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?, authorized: () -> Boolean): Boolean =
        broadcastPacketAndAwaitOutcome(routed, gattServer, characteristic, authorized) == BoundSendOutcome.SENT

    suspend fun broadcastPacketAndAwaitOutcome(routed: RoutedPacket, gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?, authorized: () -> Boolean): BoundSendOutcome {
        val packets = fragmentingSender.packetsForTransport(routed) ?: return BoundSendOutcome.NOT_SENT
        for ((index, packet) in packets.withIndex()) {
            if (!authorized()) return BoundSendOutcome.beforeFragment(index)
            val accepted = CompletableDeferred<GattOperationResult>()
            val handoffEvidence = GattHandoffEvidence()
            val result = try {
                kotlinx.coroutines.withTimeoutOrNull(gattCallbackTimeoutMs + 1_000L) {
                    broadcasterActor.send(BroadcastRequest(routed.copy(packet = packet),
                        gattServer, characteristic, accepted, true, authorized, handoffEvidence))
                    accepted.await()
                } ?: handoffEvidence.failureResult()
            } catch (cancelled: CancellationException) { accepted.cancel(); throw cancelled }
            catch (_: Exception) { handoffEvidence.failureResult() }
            val callbackAccepted = when (result) {
                GattOperationResult.COMPLETED -> true
                GattOperationResult.REJECTED -> false
                GattOperationResult.UNCONFIRMED -> null
            }
            BoundSendOutcome.afterFragment(index, callbackAccepted, authorized())?.let { outcome ->
                accepted.cancel()
                return outcome
            }
            if (index < packets.lastIndex) delay(20)
        }
        return BoundSendOutcome.SENT
    }

    /**
     * Send a packet to a specific peer only, without broadcasting.
     * Returns true if a direct path was found and used.
     */
    fun sendPacketToPeer(
        routed: RoutedPacket,
        targetPeerID: String,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        if (!hasPeerConnection(targetPeerID)) return false
        return fragmentingSender.send(routed, "BLE peer ${targetPeerID.take(8)}") { packet ->
            sendSinglePacketToPeer(packet, targetPeerID, gattServer, characteristic)
        }
    }

    fun sendPacketToLink(
        routed: RoutedPacket,
        deviceAddress: String,
        linkID: String,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean = fragmentingSender.send(routed, "BLE link $deviceAddress") { packet ->
        val data = packet.packet.toBinaryData(
            padding = BLEPacketPaddingPolicy.shouldPadForBLE(packet.packet.type)
        ) ?: return@send false
        val currentLink = connectionTracker.getLink(deviceAddress, linkID) ?: return@send false
        if (currentLink.isClient) {
            return@send writeToDeviceConn(currentLink, data)
        }
        val serverTarget = connectionTracker.getSubscribedDevices()
            .firstOrNull { it.address == deviceAddress }
            ?: return@send false
        notifyDevice(serverTarget, data, gattServer, characteristic, expectedLinkID = linkID)
    }

    private fun sendSinglePacketToPeer(
        routed: RoutedPacket,
        targetPeerID: String,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val packet = routed.packet
        // iOS-compatible: Use selective padding policy for BLE
        val padForBLE = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type)
        val data = packet.toBinaryData(padding = padForBLE) ?: return false
        val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
        val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
        val incomingAddr = routed.relayAddress
        val incomingPeer = incomingAddr?.let { connectionTracker.addressPeerMap[it] }
        val senderNick = senderPeerID.let { pid -> nicknameResolver?.invoke(pid) }
        val route = packet.route
        val routeInfo = if (!route.isNullOrEmpty()) "routed: ${route.size} hops" else null

        // Prefer server-side subscriptions
        val serverTarget = connectionTracker.getSubscribedDevices()
            .firstOrNull { connectionTracker.addressPeerMap[it.address] == targetPeerID }
        if (serverTarget != null) {
            if (notifyDevice(serverTarget, data, gattServer, characteristic)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, serverTarget.address, packet.ttl, packet.version, routeInfo)
                return true
            }
        }

        // Then client connections
        val clientTarget = connectionTracker.getConnectedDevices().values
            .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == targetPeerID }
        if (clientTarget != null) {
            if (writeToDeviceConn(clientTarget, data)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, clientTarget.device.address, packet.ttl, packet.version, routeInfo)
                return true
            }
        }

        return false
    }

    
    /**
     * Public entry point for broadcasting - submits request to actor for serialization
     */
    fun broadcastSinglePacket(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        // Submit broadcast request to actor for serialized processing
        broadcasterScope.launch {
            try {
                broadcasterActor.send(BroadcastRequest(routed, gattServer, characteristic))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send broadcast request to actor: ${e.message}")
                // Fallback to direct processing if actor fails
                broadcastSinglePacketInternal(routed, gattServer, characteristic)
            }
        }
    }

    /**
     * Serializes a small control packet with normal BLE traffic and waits for the platform write
     * API to accept at least one notification/write.
     */
    suspend fun broadcastControlPacketAndAwaitAcceptance(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val accepted = CompletableDeferred<GattOperationResult>()
        return try {
            broadcasterActor.send(
                BroadcastRequest(routed, gattServer, characteristic, accepted)
            )
            accepted.await().completed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to queue control packet: ${e.message}")
            broadcastSinglePacketInternal(routed, gattServer, characteristic).completed
        }
    }

    /**
     * Targeted send to a specific peer (by peerID) if directly connected.
     * Returns true if sent to at least one matching connection.
     */
    fun sendToPeer(
        targetPeerID: String,
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        if (!hasPeerConnection(targetPeerID)) return false
        return fragmentingSender.send(routed, "BLE peer ${targetPeerID.take(8)}") { packet ->
            sendSinglePacketToPeer(packet, targetPeerID, gattServer, characteristic)
        }
    }

    private fun hasPeerConnection(targetPeerID: String): Boolean {
        val hasServerTarget = connectionTracker.getSubscribedDevices()
            .any { connectionTracker.addressPeerMap[it.address] == targetPeerID }
        if (hasServerTarget) return true

        return connectionTracker.getConnectedDevices().values
            .any { connectionTracker.addressPeerMap[it.device.address] == targetPeerID }
    }
    
    /**
     * Internal broadcast implementation - runs in serialized actor context
     */
    private suspend fun broadcastSinglePacketInternal(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?,
        actualWrites: Boolean = false,
        authorized: () -> Boolean = { true },
        handoffEvidence: GattHandoffEvidence = GattHandoffEvidence()
    ): GattOperationResult {
        if (!authorized()) return GattOperationResult.REJECTED
        suspend fun notify(device: BluetoothDevice, data: ByteArray): GattOperationResult =
            if (actualWrites) notifyAndAwait(device, data, gattServer, characteristic, authorized, handoffEvidence)
            else if (notifyDevice(device, data, gattServer, characteristic)) GattOperationResult.COMPLETED
            else GattOperationResult.REJECTED
        suspend fun write(connection: BluetoothConnectionTracker.DeviceConnection, data: ByteArray): GattOperationResult =
            if (actualWrites) writeAndAwait(connection, data, authorized, handoffEvidence)
            else if (writeToDeviceConn(connection, data)) GattOperationResult.COMPLETED
            else GattOperationResult.REJECTED
        val packet = routed.packet
        // iOS-compatible: Use selective padding policy for BLE
        val padForBLE = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type)
        val data = packet.toBinaryData(padding = padForBLE) ?: return GattOperationResult.REJECTED
        val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
        val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
        val incomingAddr = routed.relayAddress
        val incomingPeer = incomingAddr?.let { connectionTracker.addressPeerMap[it] }
        val senderNick = senderPeerID.let { pid -> nicknameResolver?.invoke(pid) }
        val route = packet.route
        val routeInfo = if (!route.isNullOrEmpty()) "routed: ${route.size} hops" else null

        // Source Routing for Originating Packets
        // If we are the sender and a source route is defined, we must send ONLY to the first hop.
        if (packet.senderID.toHexString() == myPeerID && !packet.route.isNullOrEmpty()) {
            val firstHop = packet.route!![0].toHexString()

            var sent = false

            // Try to find first hop in server connections (subscribedDevices)
            val serverTarget = connectionTracker.getSubscribedDevices()
                .firstOrNull { connectionTracker.addressPeerMap[it.address] == firstHop }

            if (serverTarget != null) {
                val result = notify(serverTarget, data)
                if (result.completed) {
                    val toPeer = connectionTracker.addressPeerMap[serverTarget.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, serverTarget.address, packet.ttl, packet.version, routeInfo)
                    sent = true
                }
                if (actualWrites) return result
            }

            // Try to find first hop in client connections if not sent yet
            if (!sent) {
                val clientTarget = connectionTracker.getConnectedDevices().values
                    .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == firstHop }
                
                if (clientTarget != null) {
                    val result = write(clientTarget, data)
                    if (result.completed) {
                        val toPeer = connectionTracker.addressPeerMap[clientTarget.device.address]
                        logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, clientTarget.device.address, packet.ttl, packet.version, routeInfo)
                        sent = true
                    }
                    if (actualWrites) return result
                }
            }

            if (sent) return GattOperationResult.COMPLETED

            Log.d(TAG, "Source Routing: First hop $firstHop not connected. Falling back to standard broadcast logic.")
        }
        
        val directRecipient = packet.recipientID
        if (directRecipient != null && !directRecipient.contentEquals(SpecialRecipients.BROADCAST)) {
            val recipientID = directRecipient.toHexString()

            // Try to find the recipient in server connections (subscribedDevices)
            val targetDevice = connectionTracker.getSubscribedDevices()
                .firstOrNull { connectionTracker.addressPeerMap[it.address] == recipientID }
            
            // If found, send directly
            if (targetDevice != null) {
                val result = notify(targetDevice, data)
                if (result.completed) {
                    val toPeer = connectionTracker.addressPeerMap[targetDevice.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, targetDevice.address, packet.ttl, packet.version, routeInfo)
                    return GattOperationResult.COMPLETED
                }
                if (actualWrites) return result
            }

            // Try to find the recipient in client connections (connectedDevices)
            val targetDeviceConn = connectionTracker.getConnectedDevices().values
                .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == recipientID }
            
            // If found, send directly
            if (targetDeviceConn != null) {
                val result = write(targetDeviceConn, data)
                if (result.completed) {
                    val toPeer = connectionTracker.addressPeerMap[targetDeviceConn.device.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, targetDeviceConn.device.address, packet.ttl, packet.version, routeInfo)
                    return GattOperationResult.COMPLETED
                }
                if (actualWrites) return result
            }
        }

        // Else, continue with broadcasting to all devices
        val subscribedDevices = connectionTracker.getSubscribedDevices()
        val connectedDevices = connectionTracker.getConnectedDevices()

        val senderID = packet.senderID.toHexString()
        var result = GattOperationResult.REJECTED

        // Send to server connections (devices connected to our GATT server)
        subscribedDevices.forEach { device ->
            if (device.address == routed.relayAddress) {
                return@forEach
            }
            if (connectionTracker.addressPeerMap[device.address] == senderID) {
                return@forEach
            }
            val operation = notify(device, data)
            result = result.merge(operation)
            if (operation.completed) {
                val toPeer = connectionTracker.addressPeerMap[device.address]
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, device.address, packet.ttl, packet.version, routeInfo)
            }
        }
        
        // Send to client connections (GATT servers we are connected to)
        connectedDevices.values.forEach { deviceConn ->
            if (deviceConn.isClient && deviceConn.gatt != null && deviceConn.characteristic != null) {
                if (deviceConn.device.address == routed.relayAddress) {
                    return@forEach
                }
                if (connectionTracker.addressPeerMap[deviceConn.device.address] == senderID) {
                    return@forEach
                }
                val operation = write(deviceConn, data)
                result = result.merge(operation)
                if (operation.completed) {
                    val toPeer = connectionTracker.addressPeerMap[deviceConn.device.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, deviceConn.device.address, packet.ttl, packet.version, routeInfo)
                }
            }
        }
        return result
    }
    
    /**
     * Send data to a single device (server->client)
     */
    private fun notifyDevice(
        device: BluetoothDevice, 
        data: ByteArray,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?,
        expectedLinkID: String? = null
    ): Boolean {
        val server = gattServer ?: return false
        val char = characteristic ?: return false
        val link = if (expectedLinkID != null) {
            connectionTracker.getLink(device.address, expectedLinkID)
        } else {
            connectionTracker.getDeviceConnection(device.address)
        }
        val linkID = link
            ?.takeIf { !it.isClient }
            ?.linkID
            ?: return false
        return enqueueSend(
            SendKey(device.address, linkID, SendDirection.SERVER_NOTIFICATION),
            PendingSend(data.copyOf(), device, gattServer = server, characteristic = char)
        )
    }

    /**
     * Send data to a single device (client->server)
     */
    private fun writeToDeviceConn(
        deviceConn: BluetoothConnectionTracker.DeviceConnection, 
        data: ByteArray
    ): Boolean {
        val gatt = deviceConn.gatt ?: return false
        val char = deviceConn.characteristic ?: return false
        return enqueueSend(
            SendKey(deviceConn.device.address, deviceConn.linkID, SendDirection.CLIENT_WRITE),
            PendingSend(data.copyOf(), deviceConn.device, gatt = gatt, characteristic = char)
        )
    }

    private suspend fun notifyAndAwait(device: BluetoothDevice, data: ByteArray, server: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?, authorized: () -> Boolean,
        handoffEvidence: GattHandoffEvidence): GattOperationResult {
        val link = connectionTracker.getDeviceConnection(device.address)?.takeIf { !it.isClient }
            ?: return GattOperationResult.REJECTED
        if (server == null || characteristic == null) return GattOperationResult.REJECTED
        return enqueueAndAwait(SendKey(device.address, link.linkID, SendDirection.SERVER_NOTIFICATION),
            PendingSend(data.copyOf(), device, gattServer = server, characteristic = characteristic,
                accepted = CompletableDeferred(), handoffEvidence = handoffEvidence, authorized = authorized))
    }
    private suspend fun writeAndAwait(connection: BluetoothConnectionTracker.DeviceConnection, data: ByteArray,
        authorized: () -> Boolean, handoffEvidence: GattHandoffEvidence): GattOperationResult {
        val gatt = connection.gatt ?: return GattOperationResult.REJECTED
        val characteristic = connection.characteristic ?: return GattOperationResult.REJECTED
        return enqueueAndAwait(SendKey(connection.device.address, connection.linkID, SendDirection.CLIENT_WRITE),
            PendingSend(data.copyOf(), connection.device, gatt = gatt, characteristic = characteristic,
                accepted = CompletableDeferred(), handoffEvidence = handoffEvidence, authorized = authorized))
    }
    private suspend fun enqueueAndAwait(key: SendKey, request: PendingSend): GattOperationResult {
        val accepted = request.accepted ?: return GattOperationResult.REJECTED
        if (!enqueueSend(key, request)) return GattOperationResult.REJECTED
        return try {
            kotlinx.coroutines.withTimeoutOrNull(gattCallbackTimeoutMs + 1_000L) { accepted.await() }
                ?: request.failureResult()
        }
        finally { if (!accepted.isCompleted) accepted.cancel() }
    }

    /**
     * Android permits only one outstanding GATT operation per link. Queueing here mirrors the
     * readiness-driven iOS transport and prevents a later write from overwriting an operation
     * that the controller has not completed yet.
     */
    private fun enqueueSend(key: SendKey, request: PendingSend): Boolean {
        val startNow = synchronized(sendLock) {
            val state = sendStates.getOrPut(key, ::LinkSendState)
            if (
                state.pending.size >= MAX_PENDING_SENDS_PER_LINK ||
                state.pendingBytes + request.data.size > MAX_PENDING_BYTES_PER_LINK
            ) {
                Log.w(TAG, "BLE send queue full for ${key.direction}; rejecting ${request.data.size} bytes")
                return false
            }
            state.pending.addLast(request)
            state.pendingBytes += request.data.size
            if (!state.inFlight && !state.retryScheduled) {
                state.inFlight = true
                true
            } else {
                false
            }
        }
        if (startNow) startHead(key)
        return true
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "ObsoleteSdkInt")
    private fun startHead(key: SendKey) {
        val (request, operationGeneration) = synchronized(sendLock) {
            val state = sendStates[key] ?: return
            val head = state.pending.peekFirst() ?: return
            state.operationGeneration += 1
            head to state.operationGeneration
        }
        val exactLink = connectionTracker.getLink(key.deviceAddress, key.linkID)
        val exactRole = when (key.direction) {
            SendDirection.CLIENT_WRITE -> exactLink?.isClient == true
            SendDirection.SERVER_NOTIFICATION -> exactLink?.isClient == false
        }
        if (request.accepted?.isCancelled == true || !request.authorized() || !exactRole) {
            failBoundHead(key, request); return
        }
        synchronized(sendLock) {
            val state = sendStates[key] ?: return
            if (state.pending.peekFirst() !== request || state.operationGeneration != operationGeneration) return
            request.apiState = GattApiState.API_PENDING
            request.handoffEvidence.beginApiCall()
        }
        val accepted = try {
            when (key.direction) {
                SendDirection.CLIENT_WRITE -> {
                    val gatt = request.gatt
                    if (gatt == null) {
                        false
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(
                            request.characteristic,
                            request.data,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        request.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        request.characteristic.value = request.data
                        gatt.writeCharacteristic(request.characteristic)
                    }
                }
                SendDirection.SERVER_NOTIFICATION -> {
                    val server = request.gattServer
                    if (server == null) {
                        false
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        server.notifyCharacteristicChanged(
                            request.device,
                            request.characteristic,
                            false,
                            request.data
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        request.characteristic.value = request.data
                        server.notifyCharacteristicChanged(request.device, request.characteristic, false)
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "BLE ${key.direction} failed to start: ${error.message}")
            false
        }
        // Test hook deliberately widens the otherwise tiny return/publication race. Production is
        // null; callbacks that arrive while it is held are recorded under sendLock and replayed.
        afterGattApiResultForTesting?.invoke(accepted)
        val deferredCallback = synchronized(sendLock) {
            request.handoffEvidence.finishApiCall(accepted)
            request.apiState = if (accepted) GattApiState.ACCEPTED else GattApiState.REJECTED
            request.deferredCallbackStatus.also { request.deferredCallbackStatus = null }
        }
        if (accepted) {
            if (deferredCallback != null) completeSend(key, deferredCallback)
            else armGattCallbackTimeout(key, request, operationGeneration)
        }
        else if (request.accepted != null) failBoundHead(key, request) else rejectStart(key)
    }

    /**
     * Android accepting write/notify only means that the operation entered the local Bluetooth
     * stack. The exact-link queue advances, and a bound send succeeds, only after its GATT callback.
     * A missing callback invalidates that generation so no later operation can cross over to a
     * replacement link which happens to reuse the same address.
     */
    private fun armGattCallbackTimeout(key: SendKey, request: PendingSend, operationGeneration: Long) {
        val timeout = broadcasterScope.launch {
            delay(gattCallbackTimeoutMs)
            onGattCallbackTimeout(key, request, operationGeneration)
        }
        synchronized(sendLock) {
            val state = sendStates[key]
            if (state == null || state.pending.peekFirst() !== request || !state.inFlight ||
                state.operationGeneration != operationGeneration
            ) {
                timeout.cancel()
            } else {
                state.callbackTimeout?.cancel()
                state.callbackTimeout = timeout
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onGattCallbackTimeout(key: SendKey, request: PendingSend, operationGeneration: Long) {
        val abandoned = synchronized(sendLock) {
            val state = sendStates[key] ?: return
            if (state.pending.peekFirst() !== request || !state.inFlight ||
                state.operationGeneration != operationGeneration
            ) return
            state.callbackTimeout = null
            sendStates.remove(key)?.pending?.toList().orEmpty()
        }
        Log.w(TAG, "BLE ${key.direction} callback timed out; invalidating exact link")
        abandoned.forEach { it.accepted?.complete(it.failureResult()) }
        val timeoutHandler = linkTimeoutHandler
        if (timeoutHandler != null) {
            timeoutHandler(key.deviceAddress, key.linkID, key.direction == SendDirection.CLIENT_WRITE, request.device)
            return
        }
        // Standalone/tests fallback. Production installs a manager handler so peer, Noise and
        // direct-route state receive the same disconnect delegate event as a platform callback.
        connectionTracker.cleanupDeviceConnectionIfCurrent(key.deviceAddress, key.linkID)
        try {
            when (key.direction) {
                SendDirection.CLIENT_WRITE -> {
                    request.gatt?.disconnect()
                    request.gatt?.close()
                }
                SendDirection.SERVER_NOTIFICATION -> request.gattServer?.cancelConnection(request.device)
            }
        } catch (error: Exception) {
            Log.w(TAG, "BLE ${key.direction} timed-out link close failed: ${error.message}")
        }
    }

    private fun failBoundHead(key: SendKey, request: PendingSend) {
        request.accepted?.complete(request.failureResult())
        val startNext = synchronized(sendLock) {
            val state = sendStates[key] ?: return
            if (state.pending.peekFirst() !== request) return
            state.callbackTimeout?.cancel(); state.callbackTimeout = null
            state.pending.removeFirst(); state.pendingBytes -= request.data.size; state.inFlight = false
            if (state.pending.isEmpty()) { sendStates.remove(key); false }
            else { state.inFlight = true; true }
        }
        if (startNext) startHead(key)
    }

    private fun rejectStart(key: SendKey): Boolean {
        val schedule = synchronized(sendLock) {
            val state = sendStates[key] ?: return false
            state.inFlight = false
            if (state.retryScheduled || state.pending.isEmpty()) false else {
                state.retryScheduled = true
                true
            }
        }
        if (schedule) {
            connectionScope.launch {
                delay(SEND_RETRY_DELAY_MS)
                val retry = synchronized(sendLock) {
                    val state = sendStates[key] ?: return@synchronized false
                    state.retryScheduled = false
                    if (!state.inFlight && state.pending.isNotEmpty()) {
                        state.inFlight = true
                        true
                    } else false
                }
                if (retry) startHead(key)
            }
        }
        return false
    }

    fun onGattClientWriteComplete(deviceAddress: String, linkID: String, status: Int) {
        completeSend(SendKey(deviceAddress, linkID, SendDirection.CLIENT_WRITE), status)
    }

    fun onGattServerNotificationComplete(deviceAddress: String, linkID: String?, status: Int) {
        if (linkID == null) return
        completeSend(SendKey(deviceAddress, linkID, SendDirection.SERVER_NOTIFICATION), status)
    }

    private fun completeSend(key: SendKey, status: Int) {
        var retry = false
        var completed: PendingSend? = null
        var completedResult = GattOperationResult.REJECTED
        val startNext = synchronized(sendLock) {
            val state = sendStates[key] ?: return
            val head = state.pending.peekFirst() ?: run {
                sendStates.remove(key)
                return
            }
            if (head.apiState == GattApiState.API_PENDING) {
                // Android is allowed to callback immediately. Publish the API return first so a
                // successful callback cannot be mistaken for a pre-API refusal (or vice versa).
                if (head.deferredCallbackStatus == null) head.deferredCallbackStatus = status
                return
            }
            state.callbackTimeout?.cancel(); state.callbackTimeout = null
            state.inFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS && head.callbackFailures < MAX_CALLBACK_RETRIES) {
                head.callbackFailures++
                retry = true
                false
            } else {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "BLE ${key.direction} failed with status $status after retries")
                }
                state.pending.removeFirst()
                state.pendingBytes -= head.data.size
                completed = head
                completedResult = if (status == BluetoothGatt.GATT_SUCCESS) {
                    GattOperationResult.COMPLETED
                } else {
                    head.failureResult()
                }
                if (state.pending.isEmpty()) {
                    sendStates.remove(key)
                    false
                } else {
                    state.inFlight = true
                    true
                }
            }
        }
        completed?.accepted?.complete(completedResult)
        if (retry) rejectStart(key) else if (startNext) startHead(key)
    }

    fun onLinkDisconnected(deviceAddress: String, linkID: String?) {
        synchronized(sendLock) {
            sendStates.keys.filter { key -> key.deviceAddress == deviceAddress && (linkID == null || key.linkID == linkID) }
                .forEach { key -> sendStates.remove(key)?.let { state ->
                    state.callbackTimeout?.cancel()
                    state.pending.forEach { it.accepted?.complete(it.failureResult()) }
                } }
        }
    }

    internal fun pendingSendCountForTesting(deviceAddress: String, linkID: String): Int = synchronized(sendLock) {
        sendStates.filterKeys { it.deviceAddress == deviceAddress && it.linkID == linkID }
            .values.sumOf { it.pending.size }
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Broadcaster Debug Info ===")
            appendLine("Broadcaster Scope Active: ${broadcasterScope.isActive}")
            appendLine("Actor Channel Closed: ${broadcasterActor.isClosedForSend}")
            appendLine("Connection Scope Active: ${connectionScope.isActive}")
        }
    }
    
    /**
     * Shutdown the broadcaster actor gracefully
     */
    fun shutdown() {
        synchronized(sendLock) {
            sendStates.values.forEach { state ->
                state.callbackTimeout?.cancel()
                state.pending.forEach { it.accepted?.complete(it.failureResult()) }
            }
            sendStates.clear()
        }
        // Close the actor gracefully
        broadcasterActor.close()

        // Cancel the broadcaster scope
        broadcasterScope.cancel()
    }
} 
