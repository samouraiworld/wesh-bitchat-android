package com.bitchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.random.Random

/**
 * Handles data persistence operations for the chat system
 */
class DataManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DataManager"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
    
    private val _favoritePeers = mutableSetOf<String>()
    private val _blockedUsers = mutableSetOf<String>()
    
    val favoritePeers: Set<String> get() = _favoritePeers
    val blockedUsers: Set<String> get() = _blockedUsers
    
    // MARK: - Nickname Management
    
    fun loadNickname(): String {
        val savedNickname = prefs.getString("nickname", null)
        return if (savedNickname != null) {
            savedNickname
        } else {
            val randomNickname = "anon${Random.nextInt(1000, 9999)}"
            saveNickname(randomNickname)
            randomNickname
        }
    }
    
    fun saveNickname(nickname: String) {
        prefs.edit().putString("nickname", nickname).apply()
    }
    
    // MARK: - Favorites Management
    
    fun loadFavorites() {
        val savedFavorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        _favoritePeers.addAll(savedFavorites)
        Log.d(TAG, "Loaded ${savedFavorites.size} favorite users from storage: $savedFavorites")
    }
    
    fun saveFavorites() {
        prefs.edit().putStringSet("favorites", _favoritePeers).apply()
        Log.d(TAG, "Saved ${_favoritePeers.size} favorite users to storage: $_favoritePeers")
    }
    
    fun addFavorite(fingerprint: String) {
        val wasAdded = _favoritePeers.add(fingerprint)
        Log.d(TAG, "addFavorite: fingerprint=$fingerprint, wasAdded=$wasAdded")
        saveFavorites()
        logAllFavorites()
    }
    
    fun removeFavorite(fingerprint: String) {
        val wasRemoved = _favoritePeers.remove(fingerprint)
        Log.d(TAG, "removeFavorite: fingerprint=$fingerprint, wasRemoved=$wasRemoved")
        saveFavorites()
        logAllFavorites()
    }
    
    fun isFavorite(fingerprint: String): Boolean {
        val result = _favoritePeers.contains(fingerprint)
        Log.d(TAG, "isFavorite check: fingerprint=$fingerprint, result=$result")
        return result
    }
    
    fun logAllFavorites() {
        Log.i(TAG, "=== ALL FAVORITE USERS ===")
        Log.i(TAG, "Total favorites: ${_favoritePeers.size}")
        _favoritePeers.forEach { fingerprint ->
            Log.i(TAG, "Favorite fingerprint: $fingerprint")
        }
        Log.i(TAG, "========================")
    }
    
    // MARK: - Blocked Users Management
    
    @Synchronized
    fun loadBlockedUsers() {
        val savedBlockedUsers = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        _blockedUsers.clear()
        _blockedUsers.addAll(savedBlockedUsers)
    }
    
    fun saveBlockedUsers() {
        prefs.edit().putStringSet("blocked_users", _blockedUsers).apply()
    }
    
    fun addBlockedUser(fingerprint: String) {
        _blockedUsers.add(fingerprint)
        saveBlockedUsers()
    }
    
    fun removeBlockedUser(fingerprint: String) {
        _blockedUsers.remove(fingerprint)
        saveBlockedUsers()
    }
    
    @Synchronized
    fun isUserBlocked(fingerprint: String): Boolean {
        return _blockedUsers.contains(fingerprint)
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllData() {
        _favoritePeers.clear()
        _blockedUsers.clear()
        // Clears the whole file, including joined_channels, password_protected_channels and
        // channel_creators that an older build may still hold.
        prefs.edit().clear().apply()
    }
}
