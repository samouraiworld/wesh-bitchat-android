package com.bitchat.android.testhook

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Headless engine behind [TestHookReceiver]. Drives the public [MeshService] API and
 * observes state via [AppStateStore] flows (never touches the single-slot mesh delegate).
 */
object TestHookDriver {

    private const val TAG = TestHookReceiver.TAG

    private const val DEFAULT_SCAN_TIMEOUT_MS = 30_000L
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000L
    private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L
    private const val DEFAULT_RECV_TIMEOUT_MS = 60_000L

    suspend fun execute(context: Context, cmd: String, intent: Intent): JSONObject {
        Log.d(TAG, "execute cmd=$cmd")
        val result = when (cmd) {
            "ping" -> ok(cmd).put("pong", true).put("package", context.packageName)
            "start" -> start(context)
            "stop" -> stop(context)
            "whoami" -> whoami(context)
            "set_nickname" -> setNickname(context, intent.requiredString("name"))
            "scan" -> scan(context, intent)
            "peers" -> peers(context)
            "connect" -> connect(intent.requiredString("peer"), intent)
            "handshake" -> handshake(context, intent.requiredString("peer"), intent)
            "session" -> session(context, intent.requiredString("peer"))
            "announce" -> announce(context)
            "broadcast_msg" -> broadcastMsg(context, intent.requiredString("content"))
            "dm_send" -> dmSend(context, intent.requiredString("peer"), intent.requiredString("content"), intent.getStringExtra("msg_id"))
            "dm_recv" -> dmRecv(context, intent)
            "msg_recv" -> msgRecv(context, intent)
            "favorite_set" -> favoriteSet(
                context,
                intent.requiredString("peer"),
                intent.getBooleanExtra("enabled", true)
            )
            "favorite_status" -> favoriteStatus(context, intent.requiredString("peer"))
            "verification_set" -> verificationSet(
                context,
                intent.requiredString("peer"),
                intent.getBooleanExtra("enabled", true)
            )
            "verification_status" -> verificationStatus(context, intent.requiredString("peer"))
            "raw_send" -> rawSend(context, intent)
            "ble" -> setBle(intent.getBooleanExtra("enabled", true))
            "inject_peers" -> injectPeers(intent.getStringExtra("peers"))
            "state" -> state(context)
            "clear_results" -> clearResults(context)
            else -> err(cmd, "unknown command: $cmd")
        }
        return result.put("cmd", cmd)
    }

    // MARK: - Lifecycle

    private fun start(context: Context): JSONObject {
        MeshForegroundService.start(context)
        val mesh = mesh(context)
        mesh.startServices()
        return ok("start").put("peer_id", mesh.myPeerID)
    }

    private fun stop(context: Context): JSONObject {
        try {
            MeshServiceHolder.unifiedMeshService?.stopServices()
        } catch (e: Exception) {
            Log.w(TAG, "stopServices failed: ${e.message}")
        }
        MeshForegroundService.stop(context)
        return ok("stop")
    }

    // MARK: - Identity

    private fun whoami(context: Context): JSONObject {
        val mesh = mesh(context)
        return ok("whoami")
            .put("peer_id", mesh.myPeerID)
            .put("identity_fingerprint", mesh.getIdentityFingerprint())
            .put("noise_public_key", mesh.getStaticNoisePublicKey()?.toHex())
            .put("nickname", AppStateStore.nickname.value)
    }

    private fun setNickname(context: Context, name: String): JSONObject {
        DataManager(context).saveNickname(name)
        AppStateStore.setNickname(name)
        mesh(context).sendBroadcastAnnounce()
        return ok("set_nickname").put("nickname", name)
    }

    // MARK: - Discovery / connection

    private suspend fun scan(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_SCAN_TIMEOUT_MS)
        val minPeers = intent.getIntExtra("min_peers", 1)
        val mesh = mesh(context)
        val found = withTimeoutOrNull(timeoutMs) {
            AppStateStore.peers.first { it.size >= minPeers }
        }
        val peerIds = found ?: AppStateStore.peers.value
        return ok("scan")
            .put("reached_min_peers", found != null)
            .put("peers", peerInfosJson(mesh, peerIds))
    }

    private fun peers(context: Context): JSONObject {
        val mesh = mesh(context)
        return ok("peers").put("peers", peerInfosJson(mesh, AppStateStore.peers.value))
    }

    /**
     * Debug-only state injection for testing peer-list consumers such as notifications.
     * A blank or missing comma-separated value restores the empty state.
     */
    private fun injectPeers(commaSeparatedPeers: String?): JSONObject {
        val peers = commaSeparatedPeers
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        AppStateStore.setPeers(peers)
        return ok("inject_peers").put("peers", JSONArray(peers))
    }

    private suspend fun connect(peerID: String, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_CONNECT_TIMEOUT_MS)
        val ble = MeshServiceHolder.meshService ?: return err("connect", "BLE service not running")
        val address = ble.getDeviceAddressForPeer(peerID)
            ?: return err("connect", "no device address known for peer $peerID (scan first)")
        val accepted = ble.connectionManager.connectToAddress(address)
        if (!accepted) return err("connect", "connectToAddress($address) rejected")
        val direct = withTimeoutOrNull(timeoutMs) {
            AppStateStore.directPeers.first { it.contains(peerID) }
        }
        return ok("connect")
            .put("peer", peerID)
            .put("address", address)
            .put("direct", direct != null)
    }

    // MARK: - Noise

    private suspend fun handshake(context: Context, peerID: String, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_HANDSHAKE_TIMEOUT_MS)
        val mesh = mesh(context)
        val deadline = System.currentTimeMillis() + timeoutMs
        if (!mesh.hasEstablishedSession(peerID)) {
            mesh.initiateNoiseHandshake(peerID)
        }
        var lastState: NoiseSession.NoiseSessionState = NoiseSession.NoiseSessionState.Uninitialized
        while (System.currentTimeMillis() < deadline) {
            lastState = mesh.getSessionState(peerID)
            when (lastState) {
                is NoiseSession.NoiseSessionState.Established -> {
                    return ok("handshake")
                        .put("peer", peerID)
                        .put("state", lastState.toString())
                        .put("fingerprint", mesh.getPeerFingerprint(peerID))
                }
                is NoiseSession.NoiseSessionState.Failed -> {
                    return err("handshake", "session failed: $lastState").put("peer", peerID)
                }
                else -> delay(100)
            }
        }
        return err("handshake", "timeout after ${timeoutMs}ms (last state: $lastState)").put("peer", peerID)
    }

    private fun session(context: Context, peerID: String): JSONObject {
        val mesh = mesh(context)
        return ok("session")
            .put("peer", peerID)
            .put("state", mesh.getSessionState(peerID).toString())
            .put("established", mesh.hasEstablishedSession(peerID))
            .put("fingerprint", mesh.getPeerFingerprint(peerID))
    }

    // MARK: - Messaging

    private fun announce(context: Context): JSONObject {
        mesh(context).sendBroadcastAnnounce()
        return ok("announce")
    }

    private fun broadcastMsg(context: Context, content: String): JSONObject {
        mesh(context).sendMessage(content, emptyList())
        return ok("broadcast_msg").put("content", content)
    }

    private fun dmSend(context: Context, peerID: String, content: String, msgID: String?): JSONObject {
        val mesh = mesh(context)
        val nickname = mesh.getPeerNicknames()[peerID] ?: peerID
        val id = msgID ?: "testhook-${System.currentTimeMillis()}"
        mesh.sendPrivateMessage(content, peerID, nickname, id)
        return ok("dm_send").put("peer", peerID).put("msg_id", id)
    }

    private suspend fun dmRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_RECV_TIMEOUT_MS)
        val fromPeer = intent.getStringExtra("peer")
        val contains = intent.getStringExtra("contains")
        val startTime = System.currentTimeMillis()
        val mesh = mesh(context)
        val match = withTimeoutOrNull(timeoutMs) {
            AppStateStore.privateMessages.first { conversations ->
                conversations.values.flatten().any { msg ->
                    msg.timestamp.time >= startTime &&
                        msg.senderPeerID != mesh.myPeerID &&
                        (fromPeer == null || msg.senderPeerID == fromPeer) &&
                        (contains == null || msg.content.contains(contains))
                }
            }
        } ?: return err("dm_recv", "timeout after ${timeoutMs}ms")
        val msg = match.values.flatten().first { msg ->
            msg.timestamp.time >= startTime &&
                msg.senderPeerID != mesh.myPeerID &&
                (fromPeer == null || msg.senderPeerID == fromPeer) &&
                (contains == null || msg.content.contains(contains))
        }
        return ok("dm_recv")
            .put("from", msg.senderPeerID)
            .put("sender", msg.sender)
            .put("content", msg.content)
            .put("msg_id", msg.id)
    }

    private suspend fun msgRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_RECV_TIMEOUT_MS)
        val contains = intent.getStringExtra("contains")
        val startTime = System.currentTimeMillis()
        val mesh = mesh(context)
        val matches: (com.bitchat.android.model.BitchatMessage) -> Boolean = { msg ->
            msg.timestamp.time >= startTime &&
                msg.senderPeerID != mesh.myPeerID &&
                (contains == null || msg.content.contains(contains))
        }
        val found = withTimeoutOrNull(timeoutMs) {
            AppStateStore.publicMessages.first { l -> l.any(matches) }.first(matches)
        } ?: return err("msg_recv", "timeout after ${timeoutMs}ms")
        return ok("msg_recv")
            .put("from", found.senderPeerID)
            .put("sender", found.sender)
            .put("content", found.content)
            .put("msg_id", found.id)
    }

    // MARK: - Favorite and verification state

    private fun favoriteSet(context: Context, peerID: String, enabled: Boolean): JSONObject {
        val mesh = mesh(context)
        val peerInfo = mesh.getPeerInfo(peerID)
            ?: return err("favorite_set", "peer is not known")
        val noisePublicKey = peerInfo.noisePublicKey
            ?: return err("favorite_set", "peer Noise key is unavailable")
        FavoritesPersistenceService.initialize(context)
        FavoritesPersistenceService.shared.updateFavoriteStatus(
            noisePublicKey = noisePublicKey,
            nickname = peerInfo.nickname,
            isFavorite = enabled
        )
        mesh.sendFavoriteNotification(peerID, enabled)
        return favoriteStatus(context, peerID)
    }

    private fun favoriteStatus(context: Context, peerID: String): JSONObject {
        val mesh = mesh(context)
        FavoritesPersistenceService.initialize(context)
        val relationship = FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
            ?: mesh.getPeerInfo(peerID)?.noisePublicKey?.let {
                FavoritesPersistenceService.shared.getFavoriteStatus(it)
            }
        val isFavorite = relationship?.isFavorite == true
        val theyFavoritedUs = relationship?.theyFavoritedUs == true
        return ok("favorite_status")
            .put("peer", peerID)
            .put("is_favorite", isFavorite)
            .put("they_favorited_us", theyFavoritedUs)
            .put("is_mutual", isFavorite && theyFavoritedUs)
            .put(
                "star_state",
                when {
                    isFavorite -> "filled"
                    theyFavoritedUs -> "outlined_orange"
                    else -> "outlined"
                }
            )
    }

    private fun verificationSet(context: Context, peerID: String, enabled: Boolean): JSONObject {
        val mesh = mesh(context)
        val fingerprint = mesh.getPeerFingerprint(peerID)
            ?: return err("verification_set", "peer fingerprint is unavailable")
        SecureIdentityStateManager(context).setVerifiedFingerprint(fingerprint, enabled)
        return verificationStatus(context, peerID)
    }

    private fun verificationStatus(context: Context, peerID: String): JSONObject {
        val fingerprint = mesh(context).getPeerFingerprint(peerID)
        val verified = fingerprint != null &&
            SecureIdentityStateManager(context).getVerifiedFingerprints().any {
                it.equals(fingerprint, ignoreCase = true)
            }
        return ok("verification_status")
            .put("peer", peerID)
            .put("fingerprint", fingerprint ?: JSONObject.NULL)
            .put("verified", verified)
    }

    // MARK: - Raw packet injection

    private fun rawSend(context: Context, intent: Intent): JSONObject {
        val payloadHex = intent.requiredString("payload_hex")
        val typeStr = intent.requiredString("type")
        val peerID = intent.getStringExtra("peer")
        val ttl = intent.getIntExtra("ttl", 7)
        val type = typeStr.toUIntOrNull(16)?.toUByte()
            ?: return err("raw_send", "invalid type hex: $typeStr")
        val payload = hexToBytes(payloadHex)
            ?: return err("raw_send", "invalid payload_hex")
        val mesh = mesh(context)
        val packet = BitchatPacket(
            type = type,
            ttl = ttl.toUByte(),
            senderID = mesh.myPeerID,
            payload = payload
        )
        if (peerID != null) {
            TransportBridgeService.sendToPeerFromLocal(peerID, packet)
        } else {
            TransportBridgeService.broadcastFromLocal(RoutedPacket(packet))
        }
        return ok("raw_send")
            .put("type", typeStr)
            .put("payload_bytes", payload.size)
            .put("peer", peerID)
    }

    // MARK: - Transport / state

    private fun setBle(enabled: Boolean): JSONObject {
        val ble = MeshServiceHolder.meshService ?: return err("ble", "BLE service not running")
        ble.setBleTransportEnabled(enabled)
        return ok("ble").put("enabled", enabled)
    }

    private fun state(context: Context): JSONObject {
        val mesh = mesh(context)
        val peersJson = peerInfosJson(mesh, AppStateStore.peers.value)
        val sessions = JSONObject()
        AppStateStore.peers.value.forEach { peerID ->
            sessions.put(peerID, mesh.getSessionState(peerID).toString())
        }
        return ok("state")
            .put("peer_id", mesh.myPeerID)
            .put("nickname", AppStateStore.nickname.value)
            .put("peers", peersJson)
            .put("direct_peers", JSONArray(AppStateStore.directPeers.value.toList()))
            .put("sessions", sessions)
            .put("device_map", JSONObject(mesh.getDeviceAddressToPeerMapping() as Map<*, *>))
            .put("debug_status", mesh.getDebugStatus())
    }

    private fun clearResults(context: Context): JSONObject {
        val dir = File(context.cacheDir, "testhook/results")
        val count = dir.listFiles()?.count { it.delete() } ?: 0
        return ok("clear_results").put("deleted", count)
    }

    // MARK: - Helpers

    private fun mesh(context: Context): MeshService = MeshServiceHolder.getUnifiedOrCreate(context)

    private fun peerInfosJson(mesh: MeshService, peerIds: List<String>): JSONArray {
        val nicknames = mesh.getPeerNicknames()
        val rssi = mesh.getPeerRSSI()
        val arr = JSONArray()
        peerIds.forEach { id ->
            val info = mesh.getPeerInfo(id)
            arr.put(JSONObject()
                .put("id", id)
                .put("nickname", nicknames[id] ?: info?.nickname)
                .put("rssi", rssi[id])
                .put("direct", AppStateStore.directPeers.value.contains(id))
                .put("connected", info?.isConnected)
                .put("last_seen", info?.lastSeen)
                .put("session", mesh.getSessionState(id).toString())
                .put("fingerprint", mesh.getPeerFingerprint(id)))
        }
        return arr
    }

    private fun ok(cmd: String) = JSONObject().put("status", "ok").put("cmd", cmd)
    private fun err(cmd: String, message: String) =
        JSONObject().put("status", "error").put("cmd", cmd).put("error", message)

    private fun Intent.requiredString(name: String): String =
        getStringExtra(name) ?: throw IllegalArgumentException("missing required extra: $name")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.replace(" ", "")
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }
}
