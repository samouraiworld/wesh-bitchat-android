package com.bitchat.android.identity

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import android.util.Base64
import android.util.Log
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.util.hexEncodedString
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException

/**
 * Manages persistent identity storage and peer ID rotation - 100% compatible with iOS implementation
 * 
 * Handles:
 * - Static identity key persistence across app sessions
 * - Secure storage using Android EncryptedSharedPreferences
 * - Fingerprint calculation and identity validation
 */
class SecureIdentityStateManager {
    
    companion object {
        private const val TAG = "SecureIdentityStateManager"
        private const val PREFS_NAME = "bitchat_identity"
        private const val KEY_STATIC_PRIVATE_KEY = "static_private_key"
        private const val KEY_STATIC_PUBLIC_KEY = "static_public_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"
        private const val KEY_VERIFIED_FINGERPRINTS = "verified_fingerprints"
        private const val KEY_CACHED_PEER_FINGERPRINTS = "cached_peer_fingerprints"
        private const val KEY_CACHED_PEER_NOISE_KEYS = "cached_peer_noise_keys"
        private const val KEY_CACHED_NOISE_FINGERPRINTS = "cached_noise_fingerprints"
        private const val KEY_CACHED_FINGERPRINT_NICKNAMES = "cached_fingerprint_nicknames"
        /** Written by builds that sent media; now only erased, here and by the panic wipe. */
        private const val KEY_LEGACY_PRIVATE_MEDIA_CAPABILITY_PINS = "private_media_capability_pins_v1"
        private const val KEY_AUTHENTICATED_PEER_STATES = "authenticated_peer_states_v1"

        // BLE, Wi-Fi Aware, and Noise services each hold their own manager
        // instance over the same encrypted preferences. Serialize peer-state updates
        // process-wide so concurrent proofs cannot lose one another or
        // race a panic wipe.
        private val peerStateLock = Any()
        private var peerStateEpoch = 0L
        @Volatile private var encryptedPreferences: SharedPreferences? = null

        private fun openPreferences(context: Context): SharedPreferences =
            encryptedPreferences ?: synchronized(this) {
                encryptedPreferences ?: run {
                    val masterKey = MasterKey.Builder(context.applicationContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context.applicationContext, PREFS_NAME, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                }.also { encryptedPreferences = it }
            }
    }
    
    private val prefs: SharedPreferences
    private val lock = Any()
    private var peerStateEpochAtCreation: Long

    constructor(context: Context) {
        peerStateEpochAtCreation = synchronized(peerStateLock) {
            peerStateEpoch
        }
        prefs = openPreferences(context)
    }

    /** Test-only storage injection; production always uses encrypted prefs. */
    internal constructor(prefs: SharedPreferences, testOnly: Boolean) {
        require(testOnly) { "Plain SharedPreferences are test-only" }
        peerStateEpochAtCreation = synchronized(peerStateLock) {
            peerStateEpoch
        }
        this.prefs = prefs
    }
    
    // Missing both entries is first use. Partial, corrupt or unreadable storage must not
    // become a replacement identity. Exceptions propagate to the startup gate for retry.
    private fun readIdentityPair(privateName: String, publicName: String): Pair<ByteArray, ByteArray>? {
        val (privateText, publicText) = DurableIdentityPreferences.read(prefs, privateName, publicName)
        if (privateText == null && publicText == null) return null
        check(privateText != null && publicText != null) { "Incomplete stored identity" }
        val privateKey = Base64.decode(privateText, Base64.DEFAULT)
        val publicKey = Base64.decode(publicText, Base64.DEFAULT)
        check(privateKey.size == 32 && publicKey.size == 32) { "Invalid stored identity" }
        return privateKey to publicKey
    }

    // MARK: - Static Key Management
    
    /**
     * Load saved static key pair
     * Returns (privateKey, publicKey) or null if none exists
     */
    fun loadStaticKey(): Pair<ByteArray, ByteArray>? = readIdentityPair(KEY_STATIC_PRIVATE_KEY, KEY_STATIC_PUBLIC_KEY)

    /**
     * Save static key pair to secure storage
     */
    fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            // Validate key sizes
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }
            
            val privateKeyString = android.util.Base64.encodeToString(privateKey, android.util.Base64.DEFAULT)
            val publicKeyString = android.util.Base64.encodeToString(publicKey, android.util.Base64.DEFAULT)
            
            DurableIdentityPreferences.write(prefs, mapOf(
                KEY_STATIC_PRIVATE_KEY to privateKeyString, KEY_STATIC_PUBLIC_KEY to publicKeyString
            ))
            
            Log.d(TAG, "Saved static identity key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save static key: ${e.message}")
            throw e
        }
    }

    // MARK: - Retired signing key

    /**
     * Earlier builds made and stored a second Ed25519 pair here that nothing ever read; the identity
     * signing key lives in EncryptionService. Removes it; true once neither entry is left.
     */
    fun forgetRetiredSigningKey(): Boolean = try {
        val present = prefs.contains(KEY_SIGNING_PRIVATE_KEY) || prefs.contains(KEY_SIGNING_PUBLIC_KEY)
        // Committed synchronously: the result says whether the key is really gone.
        val committed = !present || prefs.edit().remove(KEY_SIGNING_PRIVATE_KEY).remove(KEY_SIGNING_PUBLIC_KEY).commit()
        committed && !prefs.contains(KEY_SIGNING_PRIVATE_KEY) && !prefs.contains(KEY_SIGNING_PUBLIC_KEY)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to remove the retired signing key: ${e.message}")
        false
    }
    
    // MARK: - Fingerprint Generation
    
    /**
     * Generate fingerprint from public key (SHA-256 hash)
     */
    fun generateFingerprint(publicKeyData: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyData)
        return hash.hexEncodedString()
    }
    
    /**
     * Validate fingerprint format
     */
    fun isValidFingerprint(fingerprint: String): Boolean {
        // SHA-256 fingerprint should be 64 hex characters
        return fingerprint.matches(Regex("^[a-fA-F0-9]{64}$"))
    }

    // MARK: - Verified Fingerprints

    fun getVerifiedFingerprints(): Set<String> {
        return prefs.getStringSet(KEY_VERIFIED_FINGERPRINTS, emptySet())?.toSet() ?: emptySet()
    }

    fun isVerifiedFingerprint(fingerprint: String): Boolean {
        return getVerifiedFingerprints().contains(fingerprint)
    }

    fun setVerifiedFingerprint(fingerprint: String, verified: Boolean) {
        if (!isValidFingerprint(fingerprint)) return
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_VERIFIED_FINGERPRINTS, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (verified) {
                current.add(fingerprint)
            } else {
                current.remove(fingerprint)
            }
            prefs.edit { putStringSet(KEY_VERIFIED_FINGERPRINTS, current) }
        }
    }

    fun getCachedPeerFingerprint(peerID: String): String? {
        val pid = peerID.lowercase()
        // Reading is safe without lock for SharedPreferences, but synchronizing ensures memory visibility
        // if we are paranoid, but SharedPreferences is generally thread-safe for reads.
        // However, to ensure we don't read a partial update (unlikely with SP), we can leave it.
        // The critical part is the write.
        val entries = prefs.getStringSet(KEY_CACHED_PEER_FINGERPRINTS, emptySet()) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$pid:") } ?: return null
        return entry.substringAfter(':').takeIf { isValidFingerprint(it) }
    }

    fun cachePeerFingerprint(peerID: String, fingerprint: String) {
        if (!isValidFingerprint(fingerprint)) return
        val pid = peerID.lowercase()
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_CACHED_PEER_FINGERPRINTS, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$pid:") }
            current.add("$pid:$fingerprint")
            prefs.edit { putStringSet(KEY_CACHED_PEER_FINGERPRINTS, current) }
        }
    }

    fun getCachedNoiseKey(peerID: String): String? {
        val pid = peerID.lowercase()
        val entries = prefs.getStringSet(KEY_CACHED_PEER_NOISE_KEYS, emptySet()) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$pid=") } ?: return null
        return entry.substringAfter('=').takeIf { it.matches(Regex("^[a-fA-F0-9]{64}$")) }
    }

    fun cachePeerNoiseKey(peerID: String, noiseKeyHex: String) {
        if (!noiseKeyHex.matches(Regex("^[a-fA-F0-9]{64}$"))) return
        val pid = peerID.lowercase()
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_CACHED_PEER_NOISE_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$pid=") }
            current.add("$pid=${noiseKeyHex.lowercase()}")
            prefs.edit { putStringSet(KEY_CACHED_PEER_NOISE_KEYS, current) }
        }
    }

    fun getCachedNoiseFingerprint(noiseKeyHex: String): String? {
        val key = noiseKeyHex.lowercase()
        val entries = prefs.getStringSet(KEY_CACHED_NOISE_FINGERPRINTS, emptySet()) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$key=") } ?: return null
        return entry.substringAfter('=').takeIf { isValidFingerprint(it) }
    }

    fun cacheNoiseFingerprint(noiseKeyHex: String, fingerprint: String) {
        if (!isValidFingerprint(fingerprint)) return
        if (!noiseKeyHex.matches(Regex("^[a-fA-F0-9]{64}$"))) return
        val key = noiseKeyHex.lowercase()
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_CACHED_NOISE_FINGERPRINTS, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$key=") }
            current.add("$key=$fingerprint")
            prefs.edit { putStringSet(KEY_CACHED_NOISE_FINGERPRINTS, current) }
        }
    }

    fun getCachedFingerprintNickname(fingerprint: String): String? {
        if (!isValidFingerprint(fingerprint)) return null
        val key = fingerprint.lowercase()
        val entries = prefs.getStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES, emptySet()) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$key=") } ?: return null
        val encoded = entry.substringAfter('=')
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    fun cacheFingerprintNickname(fingerprint: String, nickname: String) {
        if (!isValidFingerprint(fingerprint)) return
        val key = fingerprint.lowercase()
        val encoded = Base64.encodeToString(nickname.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$key=") }
            current.add("$key=$encoded")
            prefs.edit { putStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES, current) }
        }
    }

    // MARK: - Authenticated peer state

    /** Persist capabilities and Ed25519 key from a decoded Noise 0x21 proof in one edit. */
    @SuppressLint("UseKtx")
    fun storeAuthenticatedPeerState(
        fingerprint: String,
        state: AuthenticatedPeerState,
        onCommitted: () -> Unit = {}
    ): Boolean {
        if (!isValidFingerprint(fingerprint) || state.signingPublicKey.size != 32) return false
        val normalizedFingerprint = fingerprint.lowercase()
        return synchronized(peerStateLock) {
            // A controller that survived panic must not republish pre-wipe proof state.
            if (peerStateEpochAtCreation != peerStateEpoch) return@synchronized false
            val records = prefs.getStringSet(KEY_AUTHENTICATED_PEER_STATES, emptySet())
                ?.toMutableSet() ?: mutableSetOf()
            records.removeAll { it.startsWith("$normalizedFingerprint:") }
            val capabilitiesHex = java.lang.Long.toUnsignedString(state.capabilities.rawValue, 16)
            records.add(
                "$normalizedFingerprint:$capabilitiesHex:${state.signingPublicKey.hexEncodedString()}"
            )

            val editor = prefs.edit()
                .putStringSet(KEY_AUTHENTICATED_PEER_STATES, records)
                .remove(KEY_LEGACY_PRIVATE_MEDIA_CAPABILITY_PINS)
            // This result is a security boundary: do not publish the Ed key in memory unless the
            // encrypted identity record was durably committed.
            editor.commit().also { committed ->
                if (committed) onCommitted()
            }
        }
    }

    fun getAuthenticatedPeerState(fingerprint: String): AuthenticatedPeerState? {
        if (!isValidFingerprint(fingerprint)) return null
        return synchronized(peerStateLock) {
            if (peerStateEpochAtCreation != peerStateEpoch) return@synchronized null
            val prefix = "${fingerprint.lowercase()}:"
            val record = prefs.getStringSet(KEY_AUTHENTICATED_PEER_STATES, emptySet())
                ?.firstOrNull { it.startsWith(prefix) } ?: return@synchronized null
            val fields = record.split(':', limit = 3)
            if (fields.size != 3) return@synchronized null
            val capabilities = runCatching {
                PeerCapabilities(java.lang.Long.parseUnsignedLong(fields[1], 16))
            }.getOrNull() ?: return@synchronized null
            val signingKeyHex = fields[2]
            if (!signingKeyHex.matches(Regex("^[0-9a-f]{64}$"))) return@synchronized null
            val signingKey = runCatching {
                signingKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }.getOrNull() ?: return@synchronized null
            AuthenticatedPeerState(capabilities, signingKey)
        }
    }

    fun getAuthenticatedSigningKey(fingerprint: String): ByteArray? =
        getAuthenticatedPeerState(fingerprint)?.signingPublicKey?.copyOf()
    
    // MARK: - Peer ID Rotation Management (removed)
    // Android now derives peer ID from the persisted Noise identity fingerprint.
    // No timed peer ID rotation is performed here.
    
    // MARK: - Identity Validation
    
    /**
     * Validate that a public key is valid for Curve25519
     */
    fun validatePublicKey(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        
        // Check for all-zero key (invalid point)
        if (publicKey.all { it == 0.toByte() }) return false
        
        // Check for other known invalid points
        val invalidPoints = setOf(
            ByteArray(32) { 0x00.toByte() }, // All zeros
            ByteArray(32) { 0xFF.toByte() }, // All ones
            // Add other known invalid Curve25519 points if needed
        )
        
        return !invalidPoints.any { it.contentEquals(publicKey) }
    }
    
    /**
     * Validate that a private key is valid for Curve25519
     */
    fun validatePrivateKey(privateKey: ByteArray): Boolean {
        if (privateKey.size != 32) return false
        
        // Check for all-zero key
        if (privateKey.all { it == 0.toByte() }) return false
        
        // Check that clamping bits are correct for Curve25519
        val clampedKey = privateKey.clone()
        clampedKey[0] = (clampedKey[0].toInt() and 248).toByte()
        clampedKey[31] = (clampedKey[31].toInt() and 127).toByte()
        clampedKey[31] = (clampedKey[31].toInt() or 64).toByte()
        
        // After clamping, the key should not be all zeros
        return !clampedKey.all { it == 0.toByte() }
    }
    
    // MARK: - Debug Information
    
    /**
     * Get debug information about identity state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Identity State Manager Debug ===")
        
        val hasIdentity = prefs.contains(KEY_STATIC_PRIVATE_KEY)
        appendLine("Has identity: $hasIdentity")
        
        if (hasIdentity) {
            try {
                val keyPair = loadStaticKey()
                if (keyPair != null) {
                    val fingerprint = generateFingerprint(keyPair.second)
                    appendLine("Identity fingerprint: ${fingerprint.take(16)}...")
                    appendLine("Key validation: private=${validatePrivateKey(keyPair.first)}, public=${validatePublicKey(keyPair.second)}")
                }
            } catch (e: Exception) {
                appendLine("Key validation failed: ${e.message}")
            }
        }
    }
    
    // MARK: - Emergency Clear
    
    /**
     * Clear all identity data (for panic mode)
     */
    @SuppressLint("UseKtx")
    fun clearIdentityData() {
        try {
            synchronized(peerStateLock) {
                peerStateEpoch += 1
                peerStateEpochAtCreation = peerStateEpoch
                DurableIdentityPreferences.clear(prefs)
            }
            Log.w(TAG, "All identity data cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear identity data: ${e.message}")
        }
    }
    
    internal fun clearIdentityDataForPanic(): Boolean = synchronized(peerStateLock) {
        peerStateEpoch += 1
        peerStateEpochAtCreation = peerStateEpoch
        val committed = try {
            DurableIdentityPreferences.clear(prefs)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        // A failed commit may already have emptied the process cache. Both
        // the disk acknowledgment and an independent absence check are required.
        val absent = try {
            prefs.all.isEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        committed && absent
    }

    /**
     * Check if identity data exists
     */
    fun hasIdentityData(): Boolean {
        return prefs.contains(KEY_STATIC_PRIVATE_KEY) && prefs.contains(KEY_STATIC_PUBLIC_KEY)
    }
    
    // MARK: - Public SharedPreferences Access (for favorites and Nostr data)
    
    /**
     * Store a string value in secure preferences
     */
    fun storeSecureValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /** Store a value synchronously when the caller must not report success before disk accepts it. */
    internal fun storeSecureValueDurably(key: String, value: String): Boolean =
        runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)
    
    /**
     * Retrieve a string value from secure preferences
     */
    fun getSecureValue(key: String): String? {
        return prefs.getString(key, null)
    }
    
    /**
     * Remove a value from secure preferences
     */
    fun removeSecureValue(key: String) {
        prefs.edit().remove(key).apply()
    }
    
    /**
     * Check if a key exists in secure preferences
     */
    fun hasSecureValue(key: String): Boolean {
        return prefs.contains(key)
    }
    
    /**
     * Clear specific keys from secure preferences
     */
    fun clearSecureValues(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { key ->
            editor.remove(key)
        }
        editor.apply()
    }

    internal fun clearSecureValuesForPanic(vararg keys: String): Boolean {
        val committed = try {
            clearSecureValuesSynchronously(*keys)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        // Probe every owned key even after a failed write or another failed probe.
        val absent = keys.map { key ->
            try {
                !hasSecureValue(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }.all { it }
        return committed && absent
    }

    /** Use for panic paths that must finish the disk mutation before identity reset continues. */
    fun clearSecureValuesSynchronously(vararg keys: String): Boolean {
        val editor = prefs.edit()
        keys.forEach(editor::remove)
        return editor.commit()
    }
}
