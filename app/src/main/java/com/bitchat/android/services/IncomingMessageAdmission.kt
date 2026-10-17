package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.runBlocking

/**
 * Reflects an incoming transport message into process-wide state before any downstream effects.
 *
 * Private-message admission is authoritative: a duplicate or a message rejected while panic mode
 * is wiping state must not continue to UI delegates, unread tracking, haptics, or notifications.
 * Public messages retain their existing best-effort behavior if state reflection fails. Wesh has a
 * single public room: a message a BitChat peer tagged for a named channel is dropped, never shown.
 */
internal object IncomingMessageAdmission {
    /** Wesh admits private messages only from identities enrolled by QR on this phone. */
    class QrEnrollment(
        val fingerprintOf: (peerID: String) -> String?,
        val isEnrolled: (fingerprint: String) -> Boolean,
        val onRequest: (peerID: String, nickname: String, fingerprint: String?) -> Unit = WeshMessageRequests::record
    ) {
        internal fun admits(message: BitchatMessage): Boolean {
            if (isEnrolledSender(message)) return true
            val peerID = message.senderPeerID?.takeIf(String::isNotBlank) ?: return false
            runCatching { onRequest(peerID, message.sender, runCatching { fingerprintOf(peerID) }.getOrNull()) }
            return false
        }

        internal fun isEnrolledSender(message: BitchatMessage): Boolean {
            val peerID = message.senderPeerID?.takeIf(String::isNotBlank) ?: return false
            val fingerprint = runCatching { fingerprintOf(peerID) }.getOrNull() ?: return false
            return runCatching { isEnrolled(fingerprint) }.getOrDefault(false)
        }
    }

    /** Fingerprints blocked on this phone; unset means admission fails closed for every sender. */
    @Volatile private var blockedFingerprints: (() -> Set<String>)? = null

    /** Installs the persisted block list; every transport calls this before delivering. */
    fun install(context: android.content.Context) {
        val prefs = context.applicationContext.getSharedPreferences("bitchat_prefs", android.content.Context.MODE_PRIVATE)
        blockedFingerprints = { prefs.getStringSet("blocked_users", emptySet()).orEmpty() }
    }

    internal fun installForTesting(blocked: (() -> Set<String>)?) { blockedFingerprints = blocked }

    /**
     * The single block gate for every transport: session-hidden peer IDs and blocked long-term
     * fingerprints. Any lookup failure, or a block list that was never installed, blocks.
     */
    internal fun isSenderBlocked(peerID: String, senderFingerprint: (String) -> String?): Boolean = runCatching {
        if (SessionHiddenPeers.contains(peerID)) return@runCatching true
        val blocked = blockedFingerprints ?: return@runCatching true
        val fingerprint = senderFingerprint(peerID) ?: return@runCatching false
        fingerprint in blocked()
    }.getOrDefault(true)

    /**
     * A delivery ACK may answer only an unblocked sender that admission would accept, so a
     * stranger never learns this phone received anything. Duplicates of such a sender still
     * get their ACK, so its retries can stop.
     */
    fun mayAcknowledge(
        message: BitchatMessage,
        qrEnrollment: QrEnrollment?,
        senderFingerprint: (String) -> String?
    ): Boolean {
        val peerID = message.senderPeerID?.takeIf(String::isNotBlank) ?: return false
        if (isSenderBlocked(peerID, senderFingerprint)) return false
        return qrEnrollment == null || qrEnrollment.isEnrolledSender(message)
    }

    fun admitToAppState(
        message: BitchatMessage,
        qrEnrollment: QrEnrollment?,
        senderFingerprint: (String) -> String?
    ): Boolean = if (message.senderPeerID?.let { isSenderBlocked(it, senderFingerprint) } == true) false
    else if (message.isPrivate && qrEnrollment != null && !qrEnrollment.admits(message)) false else try {
        when {
            message.isPrivate -> {
                val peerID = message.senderPeerID?.takeIf(String::isNotBlank)
                    ?: return false
                // Mesh transport callbacks run on their background service workers. Wait for the
                // serialized SQLite transaction so a notification can never advertise a message
                // that an immediate process death would lose.
                runBlocking {
                    AppStateStore.addPrivateMessageDurably(peerID, message)
                }
            }

            message.channel != null -> false

            else -> {
                AppStateStore.addPublicMessage(message)
                true
            }
        }
    } catch (_: Exception) {
        // Preserve the pre-existing best-effort dispatch for public messages, but never
        // bypass private-message admission when persistence or canonicalization fails.
        !message.isPrivate
    }
}
