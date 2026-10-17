package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared mesh coordinator that wires all mesh-layer components and provides common APIs
 * for send/receive operations across transports.
 */
class MeshCore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transport: MeshTransport,
    private val encryptionService: EncryptionService,
    val myPeerID: String,
    private val maxTtl: UByte,
    sharedGossipManager: GossipSyncManager?,
    gossipConfigProvider: GossipSyncManager.ConfigProvider,
    private val hooks: Hooks = Hooks(),
    // Passed in rather than read from BuildConfig: this file also compiles into the watch module.
    // Defaults to the stricter Wesh policy.
    weshLocalOnly: Boolean = true
) {
    data class Hooks(
        /**
         * Reflects a decoded message into transport-owned state before delegate dispatch.
         * Return false to suppress all downstream effects for a rejected message.
         */
        val onMessageReceived: ((BitchatMessage) -> Boolean)? = null,
        val onAnnounceProcessed: ((RoutedPacket, Boolean) -> Unit)? = null,
        val readReceiptInterceptor: ((String, String) -> Boolean)? = null,
        val onReadReceiptSent: ((String) -> Unit)? = null,
        val announcementNicknameProvider: (() -> String?)? = null,
        val leavePayloadProvider: (() -> ByteArray)? = null
    )

    private val peerManager = PeerManager()
    val fragmentManager = FragmentManager(myPeerID).apply { acceptMediaFragments = !weshLocalOnly }
    private val readReceiptRetrySender = RetryingControlPacketSender(scope)
    private val authenticatedPeerStateStore = SecureAuthenticatedPeerStateStore(context)
    private val authenticatedPeerState by lazy {
        AuthenticatedPeerStateCoordinator(
            scope = scope,
            authenticatedSessionProvider = encryptionService::getAuthenticatedSession,
            withAuthenticatedSession = encryptionService::withAuthenticatedSession,
            store = authenticatedPeerStateStore,
            localStateProvider = {
                AuthenticatedPeerState(
                    PeerCapabilities.LOCAL_SUPPORTED,
                    requireNotNull(encryptionService.getSigningPublicKey())
                )
            },
            applyAuthenticatedState = peerManager::applyAuthenticatedPeerState,
            sendState = ::sendAuthenticatedPeerState,
            onResolution = {}
        )
    }
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    // Same inbound policy as Bluetooth.
    private val messageHandler = MessageHandler(myPeerID, context.applicationContext).apply {
        applyInboundPolicy(weshLocalOnly)
    }
    private val packetProcessor = PacketProcessor(myPeerID)
    private val directPeers = ConcurrentHashMap.newKeySet<String>()

    val gossipSyncManager: GossipSyncManager =
        sharedGossipManager ?: GossipSyncManager(myPeerID = myPeerID, scope = scope, configProvider = gossipConfigProvider)
    private val ownsGossipManager: Boolean = sharedGossipManager == null

    var delegate: MeshDelegate? = null

    private var isActive = false

    init {
        messageHandler.packetProcessor = packetProcessor
        peerManager.isPeerDirectlyConnected = { peerID -> directPeers.contains(peerID) }
        setupDelegates()

        if (sharedGossipManager == null) {
            gossipSyncManager.delegate = object : GossipSyncManager.Delegate {
                override fun sendPacket(packet: BitchatPacket) {
                    dispatchGlobal(RoutedPacket(packet))
                }

                override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                    transport.sendPacketToPeer(peerID, packet)
                    TransportBridgeService.sendToPeer(transport.id, peerID, packet)
                }

                override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                    return signPacketBeforeBroadcast(packet)
                }
            }
        }
    }

    fun startCore() {
        if (isActive) return
        isActive = true
        if (ownsGossipManager) {
            gossipSyncManager.start()
        }
    }

    fun stopCore() {
        if (!isActive) return
        isActive = false
        directPeers.clear()
        if (ownsGossipManager) {
            gossipSyncManager.stop()
        }
    }

    fun shutdown() {
        directPeers.clear()
        peerManager.shutdown()
        fragmentManager.shutdown()
        securityManager.shutdown()
        storeForwardManager.shutdown()
        messageHandler.shutdown()
        packetProcessor.shutdown()
    }

    fun processIncoming(
        packet: BitchatPacket,
        peerID: String?,
        relayAddress: String?,
        ingressLinkID: String? = null
    ) {
        packetProcessor.processPacket(
            RoutedPacket(
                packet = packet,
                peerID = peerID,
                relayAddress = relayAddress,
                ingressLinkID = ingressLinkID
            )
        )
    }

    fun sendFromBridge(packet: RoutedPacket) {
        transport.broadcastPacket(packet)
    }

    fun sendFromBridgeAndReport(packet: RoutedPacket): Boolean {
        return transport.broadcastPacket(packet)
    }

    private fun dispatchGlobal(routed: RoutedPacket) {
        transport.broadcastPacket(routed)
        TransportBridgeService.broadcast(transport.id, routed)
    }

    private suspend fun dispatchGlobalAndReport(routed: RoutedPacket): Boolean {
        val acceptedByLocalTransport = transport.broadcastPacket(routed)
        val acceptedByBridgedTransport =
            TransportBridgeService.broadcastAndReport(transport.id, routed)
        return acceptedByLocalTransport || acceptedByBridgedTransport
    }

    private fun setupDelegates() {
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerListUpdated(peerIDs: List<String>) {
                try { com.bitchat.android.services.AppStateStore.setTransportPeers(transport.id, peerIDs) } catch (_: Exception) { }
                delegate?.didUpdatePeerList(peerIDs)
            }

            override fun onPeerRemoved(peerID: String) {
                directPeers.remove(peerID)
                authenticatedPeerState.clear(peerID)
                try { gossipSyncManager.removeAnnouncementForPeer(peerID) } catch (_: Exception) { }
                try { encryptionService.removePeer(peerID) } catch (_: Exception) { }
                try { peerManager.refreshPeerList() } catch (_: Exception) { }
            }
        }

        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(
                peerID: String,
                authenticatedRemoteStaticKey: ByteArray,
                authenticatedSessionToken: ByteArray,
                directRelayAddress: String?,
                ingressLinkID: String?
            ) {
                authenticatedPeerState.onSessionAuthenticated(
                    peerID,
                    authenticatedRemoteStaticKey,
                    authenticatedSessionToken
                )
                scope.launch {
                    delay(100)
                    sendAnnouncementToPeer(peerID)
                    delay(1000)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }

            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {
                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    ttl = maxTtl
                )
                dispatchGlobal(RoutedPacket(signPacketBeforeBroadcast(responsePacket)))
            }

            override fun getPeerInfo(peerID: String): PeerInfo? = peerManager.getPeerInfo(peerID)

            override fun getAuthenticatedSigningKey(noisePublicKey: ByteArray): ByteArray? =
                authenticatedPeerState.persistedSigningKeyFor(noisePublicKey)
        }

        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String): Boolean {
                return delegate?.isFavorite(peerID) ?: false
            }

            override fun isPeerOnline(peerID: String): Boolean {
                return peerManager.isPeerActive(peerID)
            }

            override fun sendPacket(packet: BitchatPacket) {
                dispatchGlobal(RoutedPacket(packet))
            }
        }

        messageHandler.delegate = object : MessageHandlerDelegate {
            override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
                return peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun removePeer(peerID: String) {
                peerManager.removePeer(peerID)
            }

            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getMyNickname(): String? {
                return delegate?.getNickname()
            }

            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }

            override fun updatePeerInfoFromVerifiedAnnouncement(
                peerID: String,
                nickname: String,
                noisePublicKey: ByteArray,
                signingPublicKey: ByteArray,
                isVerified: Boolean,
                capabilities: com.bitchat.android.model.PeerCapabilities?
            ): Boolean {
                return peerManager.updatePeerInfoFromVerifiedAnnouncement(
                    peerID,
                    nickname,
                    noisePublicKey,
                    signingPublicKey,
                    isVerified,
                    capabilities
                )
            }

            override fun sendPacket(packet: BitchatPacket) {
                val signedPacket = signPacketBeforeBroadcast(packet)
                dispatchGlobal(RoutedPacket(signedPacket))
            }

            override fun relayPacket(routed: RoutedPacket) {
                dispatchGlobal(routed)
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.verifySignature(packet, peerID)
            }

            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
                return securityManager.encryptForPeer(data, recipientPeerID)
            }

            override fun decryptFromPeer(
                encryptedData: ByteArray,
                senderPeerID: String
            ): com.bitchat.android.noise.NoiseDecryptionResult? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }

            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
                return encryptionService.verifyEd25519Signature(signature, data, publicKey)
            }

            override fun getAuthenticatedSigningKey(noisePublicKey: ByteArray): ByteArray? =
                authenticatedPeerState.persistedSigningKeyFor(noisePublicKey)

            override fun hasNoiseSession(peerID: String): Boolean {
                return encryptionService.hasEstablishedSession(peerID)
            }

            override fun removeNoiseSession(peerID: String) {
                try {
                    encryptionService.removePeer(peerID)
                } catch (e: Exception) {
                    Log.w("MeshCore", "Failed to remove Noise session for $peerID: ${e.message}")
                }
            }

            override fun initiateNoiseHandshake(peerID: String) {
                this@MeshCore.initiateNoiseHandshake(peerID)
            }

            override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? {
                return try {
                    encryptionService.processHandshakeMessage(payload, peerID)
                } catch (_: Exception) {
                    null
                }
            }

            override fun onAuthenticatedPeerStateReceived(
                peerID: String,
                state: AuthenticatedPeerState,
                authenticatedSession: com.bitchat.android.noise.AuthenticatedNoiseSession
            ) {
                authenticatedPeerState.receive(peerID, state, authenticatedSession)
            }

            override fun onMessageReceived(message: BitchatMessage) {
                if (hooks.onMessageReceived?.invoke(message) == false) return
                delegate?.didReceiveMessage(message)
            }

            // Acknowledge only when the transport's admission hook ran and accepted the message.
            override fun onPrivateMessageReceived(message: BitchatMessage): Boolean {
                val admit = hooks.onMessageReceived
                if (admit == null) {
                    delegate?.didReceiveMessage(message)
                    return false
                }
                if (!admit(message)) return false
                delegate?.didReceiveMessage(message)
                return true
            }

            override fun onDeliveryAckReceived(messageID: String, peerID: String) {
                try {
                    com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(
                        messageID,
                        com.bitchat.android.model.DeliveryStatus.Delivered(peerID, java.util.Date())
                    )
                } catch (_: Exception) { }
                delegate?.didReceiveDeliveryAck(messageID, peerID)
            }

            override fun onReadReceiptReceived(messageID: String, peerID: String) {
                try {
                    com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(
                        messageID,
                        com.bitchat.android.model.DeliveryStatus.Read(peerID, java.util.Date())
                    )
                } catch (_: Exception) { }
                delegate?.didReceiveReadReceipt(messageID, peerID)
            }

            override fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                delegate?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
            }

            override fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                delegate?.didReceiveVerifyResponse(peerID, payload, timestampMs)
            }
        }

        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.validatePacket(packet, peerID)
            }

            override fun updatePeerLastSeen(peerID: String) {
                peerManager.updatePeerLastSeen(peerID)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
                return runBlocking { securityManager.handleNoiseHandshake(routed) }
            }

            override fun handleNoiseEncrypted(routed: RoutedPacket): Boolean {
                return runBlocking { messageHandler.handleNoiseEncrypted(routed) }
            }

            override suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
                val result = messageHandler.handleAnnounceWithResult(routed)
                if (result !is AnnounceHandlingResult.Accepted) return false
                hooks.onAnnounceProcessed?.invoke(routed, result.isFirst)
                try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                return true
            }

            override fun handleMessage(routed: RoutedPacket) {
                scope.launch { messageHandler.handleMessage(routed) }
                try {
                    val pkt = routed.packet
                    val isBroadcast = (pkt.recipientID == null || pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && pkt.type == MessageType.MESSAGE.value) {
                        gossipSyncManager.onPublicPacketSeen(pkt)
                    }
                } catch (_: Exception) { }
            }

            override fun handleLeave(routed: RoutedPacket) {
                scope.launch { messageHandler.handleLeave(routed) }
            }

            override fun handleFragment(packet: BitchatPacket): FragmentResult {
                val result = fragmentManager.receiveFragment(packet)
                if (result.keptForSync) {
                    try {
                        val isBroadcast = (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                        if (isBroadcast && packet.type == MessageType.FRAGMENT.value) {
                            gossipSyncManager.onPublicPacketSeen(packet)
                        }
                    } catch (_: Exception) { }
                }
                return result
            }

            override fun sendAnnouncementToPeer(peerID: String) {
                this@MeshCore.sendAnnouncementToPeer(peerID)
            }

            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }

            override fun relayPacket(routed: RoutedPacket) {
                dispatchGlobal(routed)
            }

            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                val sent = transport.sendPacketToPeer(peerID, routed.packet)
                TransportBridgeService.sendToPeer(transport.id, peerID, routed.packet)
                return sent
            }

            override fun handleRequestSync(routed: RoutedPacket) {
                val fromPeer = routed.peerID ?: return
                val req = RequestSyncPacket.decode(routed.packet.payload) ?: return
                gossipSyncManager.handleRequestSync(fromPeer, req)
            }
        }
    }

    fun sendMessage(content: String, mentions: List<String> = emptyList()) {
        if (content.isEmpty()) return
        scope.launch {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = content.toByteArray(Charsets.UTF_8),
                signature = null,
                ttl = maxTtl
            )
            val signedPacket = signPacketBeforeBroadcast(packet)
            dispatchGlobal(RoutedPacket(signedPacket))
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    private fun sendAuthenticatedPeerState(
        peerID: String,
        state: AuthenticatedPeerState,
        authenticatedSession: com.bitchat.android.noise.AuthenticatedNoiseSession
    ): Boolean {
        val plaintext = NoisePayload(NoisePayloadType.PEER_STATE, state.encode()).encode()
        val ciphertext = securityManager.encryptForPeer(
            plaintext,
            peerID,
            authenticatedSession
        ) ?: return false
        val packet = BitchatPacket(
            version = if (ciphertext.size > 0xFFFF) 2u else 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
            recipientID = MeshPacketUtils.hexStringToByteArray(peerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = ciphertext,
            ttl = maxTtl
        )
        val signed = signPacketBeforeBroadcast(packet)
        if (signed.signature?.size != 64) return false
        dispatchGlobal(RoutedPacket(signed))
        return true
    }

    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty()) return
        scope.launch {
            val finalMessageID = messageID ?: java.util.UUID.randomUUID().toString()

            if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                try {
                    val privateMessage = PrivateMessagePacket(messageID = finalMessageID, content = content)
                    val tlvData = privateMessage.encode() ?: return@launch
                    val messagePayload = NoisePayload(
                        type = NoisePayloadType.PRIVATE_MESSAGE,
                        data = tlvData
                    )
                    val encrypted = encryptionService.encrypt(messagePayload.encode(), recipientPeerID)
                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                        recipientID = MeshPacketUtils.hexStringToByteArray(recipientPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encrypted,
                        signature = null,
                        ttl = maxTtl
                    )
                    val signedPacket = signPacketBeforeBroadcast(packet)
                    dispatchGlobal(RoutedPacket(signedPacket))
                } catch (e: Exception) {
                    Log.e("MeshCore", "Failed to encrypt private message: ${e.message}")
                }
            } else {
                initiateNoiseHandshake(recipientPeerID)
            }
        }
    }

    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        scope.launch {
            if (hooks.readReceiptInterceptor?.invoke(messageID, recipientPeerID) == true) return@launch
            try {
                val payload = NoisePayload(
                    type = NoisePayloadType.READ_RECEIPT,
                    data = messageID.toByteArray(Charsets.UTF_8)
                ).encode()
                val enc = encryptionService.encrypt(payload, recipientPeerID)
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = enc,
                    signature = null,
                    ttl = maxTtl
                )
                val signedPacket = signPacketBeforeBroadcast(packet)
                val retryKey = "$recipientPeerID:$messageID"
                readReceiptRetrySender.enqueue(
                    key = retryKey,
                    sendAttempt = {
                        dispatchGlobalAndReport(RoutedPacket(signedPacket))
                    },
                    onComplete = { accepted ->
                        if (accepted) {
                            try {
                                com.bitchat.android.services.SeenMessageStore
                                    .getInstance(context.applicationContext)
                                    .markReadReceiptSent(messageID)
                            } catch (_: Exception) { }
                            hooks.onReadReceiptSent?.invoke(messageID)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("MeshCore", "Failed to send read receipt: ${e.message}")
            }
        }
    }

    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_CHALLENGE,
            data = com.bitchat.android.services.VerificationService.buildVerifyChallenge(noiseKeyHex, nonceA)
        )
        sendNoisePayloadToPeer(payload, peerID)
    }

    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = com.bitchat.android.services.VerificationService.buildVerifyResponse(noiseKeyHex, nonceA) ?: return
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_RESPONSE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID)
    }

    private fun sendNoisePayloadToPeer(payload: NoisePayload, recipientPeerID: String) {
        scope.launch {
            try {
                val encrypted = encryptionService.encrypt(payload.encode(), recipientPeerID)
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = maxTtl
                )
                dispatchGlobal(RoutedPacket(signPacketBeforeBroadcast(packet)))
            } catch (e: Exception) {
                Log.e("MeshCore", "Failed to send Noise payload to $recipientPeerID: ${e.message}")
            }
        }
    }

    fun sendBroadcastAnnounce() {
        scope.launch {
            val nickname = hooks.announcementNicknameProvider?.invoke()
                ?: delegate?.getNickname()
                ?: myPeerID
            val staticKey = encryptionService.getStaticPublicKey() ?: run {
                Log.e("MeshCore", "No static public key available for announcement")
                return@launch
            }
            val signingKey = encryptionService.getSigningPublicKey() ?: run {
                Log.e("MeshCore", "No signing public key available for announcement")
                return@launch
            }
            val announcement = IdentityAnnouncement.forLocalPeer(nickname, staticKey, signingKey)
            val tlvPayload = buildAnnouncementPayload(announcement, nickname) ?: return@launch
            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = maxTtl,
                senderID = myPeerID,
                payload = tlvPayload
            )
            val signedPacket = signPacketBeforeBroadcast(announcePacket)
            dispatchGlobal(RoutedPacket(signedPacket))
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        val nickname = hooks.announcementNicknameProvider?.invoke()
            ?: delegate?.getNickname()
            ?: myPeerID
        val staticKey = encryptionService.getStaticPublicKey() ?: return
        val signingKey = encryptionService.getSigningPublicKey() ?: return
        val announcement = IdentityAnnouncement.forLocalPeer(nickname, staticKey, signingKey)
        val tlvPayload = buildAnnouncementPayload(announcement, nickname) ?: return
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = maxTtl,
            senderID = myPeerID,
            payload = tlvPayload
        )
        val signedPacket = signPacketBeforeBroadcast(packet)
        dispatchGlobal(RoutedPacket(signedPacket))
        peerManager.markPeerAsAnnouncedTo(peerID)
        try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
    }

    private fun buildAnnouncementPayload(announcement: IdentityAnnouncement, nickname: String): ByteArray? {
        var tlvPayload = announcement.encode() ?: return null
        val directPeersForGossip = getDirectPeerIDsForGossip()
        try {
            if (directPeersForGossip.isNotEmpty()) {
                tlvPayload += com.bitchat.android.services.meshgraph.GossipTLV.encodeNeighbors(directPeersForGossip)
            }
            com.bitchat.android.services.meshgraph.MeshGraphService.getInstance()
                .updateFromAnnouncement(myPeerID, nickname, directPeersForGossip, System.currentTimeMillis().toULong())
        } catch (_: Exception) { }
        return tlvPayload
    }

    private fun getDirectPeerIDsForGossip(): List<String> {
        return try {
            val verifiedDirect = peerManager.getVerifiedPeers()
                .filter { it.value.isDirectConnection }
                .keys
            val localDirect = (verifiedDirect + directPeers).toSet()
            // Publish this transport's direct peers and gossip the cross-transport union so a
            // node connected via multiple transports advertises a complete neighbor list.
            try { com.bitchat.android.services.AppStateStore.setTransportDirectPeers(transport.id, localDirect) } catch (_: Exception) { }
            val union = try {
                com.bitchat.android.services.AppStateStore.getDirectPeers().ifEmpty { localDirect }
            } catch (_: Exception) { localDirect }
            union.distinct().take(10)
        } catch (_: Exception) {
            directPeers.toList().take(10)
        }
    }

    fun sendLeaveAnnouncement() {
        val payload = hooks.leavePayloadProvider?.invoke() ?: byteArrayOf()
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = maxTtl,
            senderID = myPeerID,
            payload = payload
        )
        val signedPacket = signPacketBeforeBroadcast(packet)
        dispatchGlobal(RoutedPacket(signedPacket))
    }

    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()

    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()

    fun getPeerNickname(peerID: String): String? = peerManager.getPeerNickname(peerID)

    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
        return peerManager.addOrUpdatePeer(peerID, nickname)
    }

    fun removePeer(peerID: String) {
        directPeers.remove(peerID)
        peerManager.removePeer(peerID)
    }

    fun setDirectConnection(peerID: String, isDirect: Boolean) {
        if (isDirect) {
            directPeers.add(peerID)
        } else {
            directPeers.remove(peerID)
        }
        peerManager.refreshPeerList()
    }

    fun updatePeerRSSI(peerID: String, rssi: Int) {
        peerManager.updatePeerRSSI(peerID, rssi)
    }

    fun getDebugInfoWithDeviceAddresses(deviceMap: Map<String, String>): String {
        return peerManager.getDebugInfoWithDeviceAddresses(deviceMap)
    }

    fun getFingerprintDebugInfo(): String {
        return peerManager.getFingerprintDebugInfo()
    }

    fun hasEstablishedSession(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }

    fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState {
        return encryptionService.getSessionState(peerID)
    }

    fun initiateNoiseHandshake(peerID: String) {
        scope.launch {
            try {
                val handshakeData = encryptionService.initiateHandshake(peerID) ?: return@launch
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = handshakeData,
                    ttl = maxTtl
                )
                val signedPacket = signPacketBeforeBroadcast(packet)
                dispatchGlobal(RoutedPacket(signedPacket))
            } catch (e: Exception) {
                Log.e("MeshCore", "Failed to initiate Noise handshake with $peerID: ${e.message}")
            }
        }
    }

    fun getPeerFingerprint(peerID: String): String? = peerManager.getFingerprintForPeer(peerID)

    fun getPeerInfo(peerID: String): PeerInfo? = peerManager.getPeerInfo(peerID)

    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean = peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)

    fun getIdentityFingerprint(): String = encryptionService.getIdentityFingerprint()

    fun getStaticNoisePublicKey(): ByteArray? = encryptionService.getStaticPublicKey()

    fun shouldShowEncryptionIcon(peerID: String): Boolean = encryptionService.hasEstablishedSession(peerID)

    fun getEncryptedPeers(): List<String> = emptyList()

    fun getActivePeerCount(): Int = try { peerManager.getActivePeerCount() } catch (_: Exception) { 0 }

    fun refreshPeerList() {
        try { peerManager.refreshPeerList() } catch (_: Exception) { }
    }

    fun getDeviceAddressForPeer(peerID: String): String? = transport.getDeviceAddressForPeer(peerID)

    fun getDeviceAddressToPeerMapping(): Map<String, String> = transport.getDeviceAddressToPeerMapping()

    fun getDebugStatus(
        transportInfo: String,
        deviceMap: Map<String, String>,
        extraLines: List<String> = emptyList(),
        title: String? = null
    ): String {
        return buildString {
            appendLine("=== ${title ?: "${transport.id} Mesh Debug Status"} ===")
            appendLine("My Peer ID: $myPeerID")
            if (extraLines.isNotEmpty()) {
                extraLines.forEach { appendLine(it) }
            }
            appendLine(transportInfo)
            appendLine(peerManager.getDebugInfo(deviceMap))
            appendLine(fragmentManager.getDebugInfo())
            appendLine(securityManager.getDebugInfo())
            appendLine(storeForwardManager.getDebugInfo())
            appendLine(messageHandler.getDebugInfo())
            appendLine(packetProcessor.getDebugInfo())
        }
    }

    fun clearAllInternalData() {
        directPeers.clear()
        fragmentManager.clearAllFragments()
        storeForwardManager.clearAllCache()
        securityManager.clearAllData()
        peerManager.clearAllPeers()
        peerManager.clearAllFingerprints()
        try { gossipSyncManager.clear() } catch (_: Exception) { }
    }

    fun clearAllEncryptionData() {
        encryptionService.clearPersistentIdentity()
    }

    private fun applyRouteIfAvailable(packet: BitchatPacket): BitchatPacket {
        return try {
            val recipient = packet.recipientID
            if (recipient != null && !recipient.contentEquals(SpecialRecipients.BROADCAST)) {
                val destination = recipient.toHexString()
                val path = com.bitchat.android.services.meshgraph.RoutePlanner.shortestPath(
                    myPeerID,
                    destination
                )
                if (path != null && path.size >= 3) {
                    val intermediates = path.subList(1, path.size - 1)
                    packet.copy(
                        route = intermediates.map { MeshPacketUtils.hexStringToByteArray(it) },
                        version = 2u
                    )
                } else {
                    packet.copy(route = null)
                }
            } else {
                packet
            }
        } catch (_: Exception) {
            packet
        }
    }

    private fun signPacketBeforeBroadcast(packet: BitchatPacket): BitchatPacket {
        return try {
            val withRoute = applyRouteIfAvailable(packet)

            val packetDataForSigning = withRoute.toBinaryDataForSigning() ?: return withRoute
            val signature = encryptionService.signData(packetDataForSigning)
            if (signature != null) {
                withRoute.copy(signature = signature)
            } else {
                withRoute
            }
        } catch (_: Exception) {
            packet
        }
    }
}
