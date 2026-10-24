package com.bitchat.android.services

import android.content.Context
import android.content.SharedPreferences
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import com.bitchat.android.util.safeDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** QR enrollment has its own provenance; upstream manual verification cannot grant it. */
class WeshContactStore internal constructor(
    private val identity: SecureIdentityStateManager,
    private val blockPreferences: SharedPreferences
) {
    /**
     * [npub] comes only from a signed QR proven over Noise; null means Bluetooth-only.
     * [npubProven]: the QR also carried a valid proof by the npub key (NPUB-PROOF rules).
     * [npubWarning]: an npub conflict or downgrade the user should review. It survives every
     * path except a clean QR re-scan of this contact, and blocks Internet delivery meanwhile.
     * [alias] and [memo] are typed on this phone and never leave it; null when not set.
     */
    data class Contact(
        val noiseHex: String,
        val nickname: String,
        val npub: String? = null,
        val npubProven: Boolean = false,
        val npubWarning: Boolean = false,
        val alias: String? = null,
        val memo: String? = null
    ) {
        /** What this phone shows for the contact: the local alias, else the name from the QR code. */
        val displayName: String get() = alias ?: nickname
        /** False: the UI asks to rescan the QR before Internet delivery is possible. */
        val internetReachable: Boolean get() = npub != null && !npubWarning
        /** The npub Internet delivery may use: none for Bluetooth-only or flagged contacts. */
        val deliveryNpub: String? get() = npub.takeIf { internetReachable }
        val key: ByteArray get() = noiseHex.dataFromHexString()!!
        val conversationID: String get() = ContactIdentityResolver.contactConversationIdForNoiseKey(key)
    }
    private val storageKey = "wesh_qr_contacts_v1"
    private val _loadFailed = MutableStateFlow(false)
    /** True when stored contacts could not be read; writes stay blocked so they are never overwritten. */
    val loadFailed = _loadFailed.asStateFlow()
    private val _contacts = MutableStateFlow(load())
    val contacts = _contacts.asStateFlow()
    /** Noise keys (hex) of [contacts], rebuilt with every change, so a lookup never hashes keys. */
    @Volatile private var enrolledKeys: Set<String> = _contacts.value.mapTo(HashSet()) { it.noiseHex }

    private fun publish(contacts: List<Contact>) {
        enrolledKeys = contacts.mapTo(HashSet()) { it.noiseHex }
        _contacts.value = contacts
    }

    private fun load(): List<Contact> = try {
        val array = JSONArray(identity.getSecureValue(storageKey) ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val row = array.getJSONObject(index)
            val hex = row.getString("noise")
            val name = row.getString("nickname")
            val npub = row.optString("npub").takeIf(WeshNpubPolicy::isValid)
            val proven = npub != null && row.optBoolean("npubProven", false)
            val warning = row.optBoolean("npubWarning", false)
            if (!hex.matches(Regex("[0-9a-f]{64}")) || name.toByteArray().size > 255) null
            else Contact(hex, displayName(name), npub, proven, warning, alias = if (row.isNull("alias")) null else cleanAlias(row.optString("alias")),
                memo = if (row.isNull("memo")) null else cleanMemo(row.optString("memo")))
        }.distinctBy { it.noiseHex }.takeLast(256).also { _loadFailed.value = false }
    } catch (_: Exception) {
        _loadFailed.value = true
        emptyList()
    }

    /** Retries reading the stored contacts; returns true once they are readable. */
    @Synchronized fun retryLoad(): Boolean {
        val loaded = load()
        if (!_loadFailed.value) publish(loaded)
        return !_loadFailed.value
    }

    /**
     * QR enrollment after a successful Noise challenge: evaluates [WeshNpubPolicy.enroll] on the
     * current contacts and persists under one lock, so no other enrollment can interleave.
     * Returns null, writing nothing, when the stored contacts could not be read.
     */
    @Synchronized fun enroll(
        key: ByteArray,
        nickname: String,
        npub: String?,
        proof: Boolean?,
        ownedNpubs: Set<String>?
    ): WeshNpubPolicy.Enrollment? {
        if (_loadFailed.value) return null
        val decision = WeshNpubPolicy.enroll(npub, proof, ownedNpubs, _contacts.value, key.hexEncodedString())
        if (!record(key, nickname, decision)) return null
        return decision
    }

    /**
     * Applies a decision; evicted contacts lose the npub and are flagged. Caller holds the lock.
     * Refuses (false) after a failed load so an unreadable list is never overwritten.
     */
    @Synchronized internal fun record(key: ByteArray, nickname: String, enrollment: WeshNpubPolicy.Enrollment? = null): Boolean {
        require(key.size == 32 && nickname.toByteArray().size <= 255)
        if (_loadFailed.value) return false
        val hex = key.hexEncodedString()
        val existing = _contacts.value.firstOrNull { it.noiseHex == hex }
        val others = _contacts.value.filterNot { it.noiseHex == hex }.map {
            if (enrollment != null && it.noiseHex in enrollment.evict && it.npub == enrollment.npub && !it.npubProven) {
                it.copy(npub = null, npubProven = false, npubWarning = true)
            } else it
        }
        var npub = enrollment?.npub?.takeIf(WeshNpubPolicy::isValid)
        var warning = enrollment?.warning == true || (enrollment?.kept == true && existing?.npubWarning == true)
        // Invariant re-check: nobody else may still hold the npub being bound.
        if (npub != null && others.any { it.npub == npub }) {
            npub = null
            warning = true
        }
        val self = Contact(hex, displayName(nickname), npub, npub != null && enrollment?.proven == true, warning, existing?.alias, existing?.memo)
        persist((others + self).takeLast(256))
        return true
    }

    /**
     * Sets the local [alias] and [memo] of an enrolled contact; blank clears either. Returns false,
     * writing nothing, when the contacts could not be read or the contact is not enrolled.
     */
    @Synchronized fun setNote(conversationID: String, alias: String, memo: String): Boolean {
        if (_loadFailed.value) return false
        val contact = contact(conversationID) ?: return false
        persist(_contacts.value.map { if (it.noiseHex == contact.noiseHex) it.copy(alias = cleanAlias(alias), memo = cleanMemo(memo)) else it })
        return true
    }
    /** Bumped by every removal or wipe, so a verification checked before it never enrolls after it. */
    @Volatile var removalEpoch = 0L
        private set

    @Synchronized fun remove(conversationID: String): Boolean {
        removalEpoch++
        if (_loadFailed.value) return false
        val remaining = _contacts.value.filterNot { it.conversationID == conversationID }
        if (remaining.size == _contacts.value.size) return false
        persist(remaining)
        return true
    }
    /** Panic wipe is the only write allowed after a failed load: it removes what could not be read. */
    @Synchronized fun clear() {
        removalEpoch++
        persist(emptyList())
        _loadFailed.value = false
    }
    /** Inbound resolution: the proven holder, else a single unproven holder; ambiguous is null. */
    fun forNpub(npub: String): Contact? {
        val holders = _contacts.value.filter { it.npub == npub }
        return holders.firstOrNull { it.npubProven } ?: holders.singleOrNull()
    }
    /** Cheap enough for every inbound packet: a set lookup, no per-contact hashing. */
    fun isEnrolledKey(noisePublicKey: ByteArray): Boolean = noisePublicKey.hexEncodedString() in enrolledKeys
    fun contact(conversationID: String): Contact? = _contacts.value.firstOrNull { it.conversationID == conversationID }
    fun forFingerprint(fingerprint: String): Contact? = _contacts.value.firstOrNull {
        ContactIdentityResolver.fingerprintHex(it.key) == fingerprint
    }
    fun isBlocked(key: ByteArray): Boolean = isFingerprintBlocked(ContactIdentityResolver.fingerprintHex(key))
    fun isFingerprintBlocked(fingerprint: String): Boolean = runCatching {
        blockPreferences.getStringSet("blocked_users", emptySet()).orEmpty().contains(fingerprint)
    }.getOrDefault(true)
    fun blockedFingerprints(): Set<String> = blockPreferences.getStringSet("blocked_users", emptySet()).orEmpty().toSet()
    /**
     * A QR nickname is remote input: stripped once here before it is kept; what is shown applies the
     * remote rule. Empty when nothing visible is left, so it shows unnamed rather than as hex.
     */
    private fun displayName(nickname: String): String = safeDisplayName(nickname, "")
    private fun persist(contacts: List<Contact>) {
        val array = JSONArray()
        contacts.forEach { array.put(JSONObject().put("noise", it.noiseHex).put("nickname", it.nickname).putOpt("npub", it.npub)
            .putOpt("npubProven", if (it.npubProven) true else null)
            .putOpt("npubWarning", if (it.npubWarning) true else null)
            .putOpt("alias", it.alias).putOpt("memo", it.memo)) }
        identity.storeSecureValue(storageKey, array.toString())
        publish(contacts)
    }
    companion object {
        const val MAX_MEMO_LENGTH = 500

        /** Same sanitizer as a QR name, bounded like a nickname; nothing visible left is no alias. */
        internal fun cleanAlias(raw: String): String? =
            codePointPrefix(safeDisplayName(raw, ""), AppConstants.UI.MAX_NICKNAME_LENGTH).trim().ifEmpty { null }

        /** Line breaks kept, other control characters stripped, bounded; blank is no memo. */
        internal fun cleanMemo(raw: String): String? =
            codePointPrefix(raw.filter { it == '\n' || !it.isISOControl() }, MAX_MEMO_LENGTH).trim().ifBlank { null }

        internal fun codePointPrefix(text: String, max: Int): String =
            if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

        @Volatile private var instance: WeshContactStore? = null
        fun get(context: Context): WeshContactStore = instance ?: synchronized(this) {
            instance ?: WeshContactStore(SecureIdentityStateManager(context.applicationContext),
                context.applicationContext.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)).also { instance = it }
        }
        internal fun installForTesting(store: WeshContactStore) { instance = store }
        internal fun resetForTesting() { instance = null }
    }
}
