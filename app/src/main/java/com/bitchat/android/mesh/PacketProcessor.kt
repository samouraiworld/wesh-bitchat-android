package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

/**
 * Processes incoming packets and routes them to appropriate handlers
 * 
 * Per-peer packet serialization using one bounded queue per peer.
 * Prevents race condition where multiple threads process packets
 * from the same peer simultaneously, causing session management conflicts.
 *
 * Pending packets are bounded per ingress link (the claimed sender is not trusted for quotas),
 * in total packets and bytes, and in number of peer queues; a packet that does not fit is
 * dropped and counted.
 */
class PacketProcessor(
    private val myPeerID: String,
    private val perLinkQueueCapacity: Int = 256,
    private val maxQueuedPackets: Int = 1_024,
    private val maxQueuedBytes: Long = 16L * 1_048_576L,
    private val maxPeerQueues: Int = 256
) {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    
    companion object {
        private const val TAG = "PacketProcessor"
        // Header, IDs and signature counted against the byte bound for every queued packet.
        private const val PACKET_OVERHEAD_BYTES = 128L

        /** Quota key of a packet received over the transport link [linkID]. */
        fun linkQuotaKey(linkID: String): String = "link:$linkID"
    }
    
    // Delegate for callbacks
    var delegate: PacketProcessorDelegate? = null
    
    // Helper function to format peer ID with nickname for logging
    private fun formatPeerForLog(peerID: String): String {
        val nickname = delegate?.getPeerNickname(peerID)
        return if (nickname != null) "$peerID ($nickname)" else peerID
    }
    
    // Packet relay manager for centralized relay decisions
    private val packetRelayManager = PacketRelayManager(myPeerID)
    
    // Coroutines
    private val processorJob = SupervisorJob()
    private val processorScope = CoroutineScope(Dispatchers.IO + processorJob)
    
    // Each peer gets its own queue, processed sequentially, so session management never races.
    private class PeerQueue(val channel: Channel<RoutedPacket>) {
        /** Packets accepted and not yet fully handled, the one being handled included. */
        var pending = 0
    }

    private val queueLock = Any()
    private var shutdownRequested = false
    private val queues = LinkedHashMap<String, PeerQueue>()
    private val linkPending = HashMap<String, Int>()
    private var queuedPackets = 0
    private var queuedBytes = 0L
    private val droppedPackets = AtomicLong()

    /** Packets dropped because a queue bound was reached. */
    val dropped: Long get() = droppedPackets.get()
    
    init {
        // Set up the packet relay manager delegate immediately
        setupRelayManager()
    }
    
    /**
     * Process received packet - main entry point for all incoming packets
     * SURGICAL FIX: Route to per-peer actor for serialized processing
     */
    fun processPacket(routed: RoutedPacket) {
        val peerID = routed.peerID

        if (peerID == null) {
            Log.w(TAG, "Received packet with no peer ID, skipping")
            return
        }
        // TTL is mutable and excluded from signatures. Reject inflated hop counts before
        // they consume an actor slot or reach authentication, presence, storage or forwarding.
        if (routed.packet.ttl > AppConstants.MAX_MESH_TTL_HOPS) return
        
        val bytes = queuedSize(routed)
        val link = quotaKey(routed, peerID)
        val offered = synchronized(queueLock) {
            if (shutdownRequested) return@synchronized null
            if (queuedPackets >= maxQueuedPackets || queuedBytes + bytes > maxQueuedBytes) return@synchronized null
            if ((linkPending[link] ?: 0) >= perLinkQueueCapacity) return@synchronized null
            val queue = queues[peerID] ?: newQueueLocked(peerID) ?: return@synchronized null
            queue.pending += 1
            linkPending[link] = (linkPending[link] ?: 0) + 1
            queuedPackets += 1
            queuedBytes += bytes
            // These private channels have no undelivered handler and resume only fixed-IO actors.
            val sent = queue.channel.trySend(routed).isSuccess
            if (!sent) release(queue, routed)
            sent
        }
        if (offered != true) droppedPackets.incrementAndGet()
    }

    /** Quotas follow the link a packet arrived on, never the sender ID it claims. */
    private fun quotaKey(routed: RoutedPacket, peerID: String): String =
        routed.ingressLinkID?.let(::linkQuotaKey) ?: routed.relayAddress?.let { "addr:$it" } ?: "peer:$peerID"

    /** Starts a queue for a new peer, forgetting an idle one when the table is full. */
    private fun newQueueLocked(peerID: String): PeerQueue? {
        if (queues.size >= maxPeerQueues) {
            val idle = queues.entries.firstOrNull { it.value.pending == 0 } ?: return null
            queues.remove(idle.key)
            idle.value.channel.close()
        }
        // Bounded by the per-link and total quotas checked before every send.
        val queue = PeerQueue(Channel(Channel.UNLIMITED))
        queues[peerID] = queue
        processorScope.launch {
            for (packet in queue.channel) {
                try {
                    handleReceivedPacket(packet)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process packet from ${formatPeerForLog(peerID)}: ${e.message}")
                } finally {
                    release(queue, packet)
                }
            }
        }
        return queue
    }

    private fun release(queue: PeerQueue, routed: RoutedPacket) {
        val link = quotaKey(routed, routed.peerID ?: "")
        synchronized(queueLock) {
            queue.pending -= 1
            queuedPackets -= 1
            queuedBytes -= queuedSize(routed)
            val left = (linkPending[link] ?: 1) - 1
            if (left <= 0) linkPending.remove(link) else linkPending[link] = left
        }
    }

    private fun queuedSize(routed: RoutedPacket): Long = routed.packet.payload.size.toLong() + PACKET_OVERHEAD_BYTES
    
    /**
     * Set up the packet relay manager with its delegate
     */
    fun setupRelayManager() {
        packetRelayManager.delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize(): Int {
                return delegate?.getNetworkSize() ?: 1
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return delegate?.getBroadcastRecipient() ?: ByteArray(0)
            }
            
            override fun broadcastPacket(routed: RoutedPacket) {
                delegate?.relayPacket(routed)
            }
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return delegate?.sendToPeer(peerID, routed) ?: false
            }
        }
    }
    
    /**
     * Handle received packet - core protocol logic (exact same as iOS)
     */
    private suspend fun handleReceivedPacket(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        // Re-check at dispatch, including packets supplied by reassembly.
        if (packet.ttl > AppConstants.MAX_MESH_TTL_HOPS) return

        // Basic validation and security checks
        if (!delegate?.validatePacketSecurity(packet, peerID)!!) {
            return
        }

        // A valid ANNOUNCE for our own cryptographic identity is never application traffic. Give
        // the transport one chance to close the exact direct link, then suppress presence/relay.
        if (MessageType.fromValue(packet.type) == MessageType.ANNOUNCE && peerID == myPeerID) {
            delegate?.handleValidatedSelfAnnounce(routed)
            return
        }

        // Read before dispatch: local delivery runs asynchronously and may suppress relay of
        // this very copy (inbound budget) before the relay step below is reached.
        val relaySuppressed = delegate?.isRelaySuppressed(packet, peerID) == true

        var validPacket = true
        val messageType = MessageType.fromValue(packet.type)
        // Verbose logging to debug manager (and chat via ChatViewModel observer)
        try {
            val mt = messageType?.name ?: packet.type.toString()
            val routeDevice = routed.relayAddress
            val nick = delegate?.getPeerNickname(peerID)
            debugManager?.logIncomingPacket(peerID, nick, mt, routeDevice)
        } catch (_: Exception) { }
        
        
        // Handle public packet types (no address check needed)
        when (messageType) {
            MessageType.ANNOUNCE -> validPacket = handleAnnounce(routed)
            MessageType.MESSAGE -> {
                // Public text above the local bound is refused outright: not shown, kept or relayed.
                if (isOversizedPublicText(packet)) validPacket = false else handleMessage(routed)
            }
            // Wesh ships no media. A file addressed to us reaches the handler, which keeps nothing
            // (see handleMessage) and is never relayed. A file for another peer is relayed as before
            // and not processed here. A broadcast file is dropped: the V1 never spreads public files.
            MessageType.FILE_TRANSFER -> when {
                packetRelayManager.isPacketAddressedToMe(packet) -> {
                    validPacket = false
                    handleMessage(routed)
                }
                isDirectedToAnotherPeer(packet) -> Unit
                else -> validPacket = false
            }
            // Wesh has no voice: a BitChat live voice frame is never handled, never relayed and
            // never refreshes liveness, whoever sent it and whoever it is addressed to.
            MessageType.VOICE_FRAME -> validPacket = false
            MessageType.LEAVE -> handleLeave(routed)
            MessageType.FRAGMENT -> {
                if (isVoiceFrameFragment(packet)) {
                    // Same for a voice frame split into fragments: neither reassembled nor relayed.
                    validPacket = false
                } else if (isFileTransferFragment(packet)) {
                    // No media is reassembled here. Fragments of a file for another peer are relayed
                    // as before; those addressed to us or broadcast are dropped.
                    validPacket = isDirectedToAnotherPeer(packet)
                } else {
                    validPacket = handleFragment(routed)
                }
            }
            MessageType.REQUEST_SYNC -> handleRequestSync(routed)
            else -> {
                // Handle private packet types (address check required)
                if (packetRelayManager.isPacketAddressedToMe(packet)) {
                    when (messageType) {
                        MessageType.NOISE_HANDSHAKE -> validPacket = handleNoiseHandshake(routed)
                        MessageType.NOISE_ENCRYPTED -> validPacket = handleNoiseEncrypted(routed)
                        else -> {
                            validPacket = false
                            Log.w(TAG, "Unknown message type: ${packet.type}")
                        }
                    }
                } else {
                    // Not addressed to us; only relay handling below applies
                }
            }
        }
        
        // Update last seen timestamp
        if (validPacket) {
            delegate?.updatePeerLastSeen(peerID)
            
            // CENTRALIZED RELAY LOGIC: Handle relay decisions for all packets not addressed to us.
            // A redelivered copy of a packet relayed once already is never relayed again.
            if (!relaySuppressed) {
                packetRelayManager.handlePacketRelay(routed)
            }
        }
    }
    
    /**
     * Handle Noise handshake message - SIMPLIFIED iOS-compatible version
     */
    private suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        return delegate?.handleNoiseHandshake(routed) ?: false
    }
    
    /**
     * Handle Noise encrypted transport message
     * Returns false when decryption fails so undecryptable packets do not prove liveness.
     */
    private suspend fun handleNoiseEncrypted(routed: RoutedPacket): Boolean {
        return delegate?.handleNoiseEncrypted(routed) ?: false
    }
    
    /**
     * Handle announce message
     */
    private suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
        return delegate?.handleAnnounce(routed) ?: false
    }
    
    /**
     * Handle regular message
     */
    private suspend fun handleMessage(routed: RoutedPacket) {
        delegate?.handleMessage(routed)
    }
    
    /**
     * Handle leave message
     */
    private suspend fun handleLeave(routed: RoutedPacket) {
        delegate?.handleLeave(routed)
    }
    
    /**
     * Handle message fragments. Returns false for a refused fragment, which is then not relayed.
     */
    private suspend fun handleFragment(routed: RoutedPacket): Boolean {
        // Assembly slots follow the ingress link, like the queue quotas above.
        val result = delegate?.handleFragment(routed.packet, quotaKey(routed, routed.peerID ?: "")) ?: return false
        if (result is FragmentResult.Refused) return false
        if (result !is FragmentResult.Complete) return true
        val reassembledPacket = result.packet
        // A valid outer hop count does not authorize an inflated inner packet. Refuse the
        // completed assembly as well, so its outer fragment cannot refresh presence or relay.
        if (reassembledPacket.ttl > AppConstants.MAX_MESH_TTL_HOPS) return false
        if (reassembledPacket.type == MessageType.FRAGMENT.value) {
            // Fragments are never nested: refuse a second level of reassembly.
            Log.w(TAG, "Dropping nested fragment from ${formatPeerForLog(routed.peerID ?: "unknown")}")
            return false
        }
        handleReceivedPacket(
            RoutedPacket(
                packet = reassembledPacket,
                peerID = routed.peerID,
                relayAddress = routed.relayAddress,
                ingressLinkID = routed.ingressLinkID
            )
        )
        // Fragment relay is handled by the centralized PacketRelayManager
        return true
    }

    private fun isOversizedPublicText(packet: BitchatPacket): Boolean {
        val recipient = packet.recipientID
        val isBroadcast = recipient == null || recipient.contentEquals(SpecialRecipients.BROADCAST)
        return isBroadcast && packet.payload.size > PublicInboundBudget.MAX_PUBLIC_TEXT_BYTES
    }

    /** Reads the original-type byte of the fragment header without copying the fragment data. */
    private fun isDirectedToAnotherPeer(packet: BitchatPacket): Boolean {
        val recipient = packet.recipientID ?: return false
        return !recipient.contentEquals(SpecialRecipients.BROADCAST) &&
            !packetRelayManager.isPacketAddressedToMe(packet)
    }

    private fun isFileTransferFragment(packet: BitchatPacket): Boolean =
        fragmentOriginalType(packet) == MessageType.FILE_TRANSFER.value

    private fun isVoiceFrameFragment(packet: BitchatPacket): Boolean =
        fragmentOriginalType(packet) == MessageType.VOICE_FRAME.value

    private fun fragmentOriginalType(packet: BitchatPacket): UByte? =
        if (packet.payload.size >= com.bitchat.android.model.FragmentPayload.HEADER_SIZE) {
            packet.payload[com.bitchat.android.model.FragmentPayload.HEADER_SIZE - 1].toUByte()
        } else null

    /**
     * Handle REQUEST_SYNC packets (public, TTL=1)
     */
    private suspend fun handleRequestSync(routed: RoutedPacket) {
        delegate?.handleRequestSync(routed)
    }
    
    /**
     * Handle delivery acknowledgment
     */
//    private suspend fun handleDeliveryAck(routed: RoutedPacket) {
//        val peerID = routed.peerID ?: "unknown"
//        Log.d(TAG, "Processing delivery ACK from ${formatPeerForLog(peerID)}")
//        delegate?.handleDeliveryAck(routed)
//    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Processor Debug Info ===")
            appendLine("Processor Scope Active: ${processorScope.isActive}")
            synchronized(queueLock) {
                appendLine("Active Peer Queues: ${queues.size}")
                appendLine("Queued Packets: $queuedPackets ($queuedBytes bytes), dropped: ${droppedPackets.get()}")
                appendLine("My Peer ID: $myPeerID")
                if (queues.isNotEmpty()) {
                    appendLine("Peer Queues:")
                    queues.forEach { (peerID, queue) -> appendLine("  - $peerID (${queue.pending})") }
                }
            }
        }
    }
    
    /**
     * Shutdown the processor and all peer actors
     */
    fun shutdown() {
        synchronized(queueLock) {
            shutdownRequested = true
            Log.d(TAG, "Shutting down PacketProcessor and ${queues.size} peer queues")
            queues.values.forEach { it.channel.close() }
            queues.clear()
        }
        
        // Shutdown the relay manager
        packetRelayManager.shutdown()
        
        // Cancel the main scope
        processorScope.cancel()
    }

    /** Only the original external teardown may await after requesting shutdown. */
    internal suspend fun awaitShutdown() {
        check(synchronized(queueLock) { shutdownRequested }) { "Shutdown must be requested before awaiting" }
        processorJob.join()
    }
}

/**
 * Delegate interface for packet processor callbacks
 */
interface PacketProcessorDelegate {
    // Security validation
    fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean
    fun handleValidatedSelfAnnounce(routed: RoutedPacket): Boolean = false
    
    // Peer management
    fun updatePeerLastSeen(peerID: String)
    fun getPeerNickname(peerID: String): String?
    
    // Network information
    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Message type handlers
    fun handleNoiseHandshake(routed: RoutedPacket): Boolean
    fun handleNoiseEncrypted(routed: RoutedPacket): Boolean
    suspend fun handleAnnounce(routed: RoutedPacket): Boolean
    fun handleMessage(routed: RoutedPacket)
    fun isRelaySuppressed(packet: BitchatPacket, peerID: String): Boolean = false
    fun handleLeave(routed: RoutedPacket)
    /** [ingressKey] is the quota key of the link the fragment arrived on. */
    fun handleFragment(packet: BitchatPacket, ingressKey: String): FragmentResult
    fun handleRequestSync(routed: RoutedPacket)
    
    // Communication
    fun sendAnnouncementToPeer(peerID: String)
    fun sendCachedMessages(peerID: String)
    fun relayPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}
