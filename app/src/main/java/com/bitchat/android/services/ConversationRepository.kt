package com.bitchat.android.services

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.MessageTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide, serialized persistence for private conversations.
 *
 * Android's SQLite database is deliberately kept behind this small repository instead of leaking
 * cursors or database threading into the mesh and UI layers. Every mutation is queued on one
 * writer so a delete/merge followed by a newly arriving message is applied in the same order that
 * [AppStateStore] publishes it.
 */
/** A message's delivery status as stored; [status] null means none recorded yet. */
data class StoredDeliveryStatus(val status: DeliveryStatus?)
data class PendingOutgoingDelivery(
    val conversationID: String,
    val message: BitchatMessage,
    val bluetoothAttempts: Int = 0,
    val bluetoothRetryAtMs: Long = 0L,
    val bluetoothMayHaveLeft: Boolean = false
)

class ConversationRepository internal constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "conversation-store").apply { isDaemon = true }
        }
        .asCoroutineDispatcher(),
    databaseName: String = ConversationDatabase.DEFAULT_DATABASE_NAME,
    storageCipher: ConversationStorageCipher = AndroidConversationStorageCipher()
) {
    companion object {
        private const val TAG = "ConversationRepository"

        @Volatile
        private var instance: ConversationRepository? = null

        fun getInstance(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(context.applicationContext).also {
                    instance = it
                }
            }

        fun tryGetInstance(): ConversationRepository? = instance
    }

    private val applicationContext = context.applicationContext
    private val database = ConversationDatabase(
        context = applicationContext,
        databaseName = databaseName,
        storageCipher = storageCipher
    )
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val initialized = AtomicBoolean(false)
    private val writeGeneration = AtomicLong()

    internal fun captureWriteGeneration(): Long = writeGeneration.get()

    // Every database mutation runs on the same serial writer. A transaction
    // already executing finishes before clear; later old submissions do nothing.
    private fun enqueueMutation(block: () -> Unit) {
        val generation = captureWriteGeneration()
        scope.launch {
            if (generation == captureWriteGeneration()) block()
        }
    }
    private val _storeState =
        MutableStateFlow<ConversationStoreState>(ConversationStoreState.Loading)
    val storeState: StateFlow<ConversationStoreState> = _storeState.asStateFlow()

    internal fun initialize(onLoaded: (PersistedConversationSnapshot) -> Unit) {
        if (!initialized.compareAndSet(false, true)) return
        enqueueSnapshotLoad(pruneFirst = true, onLoaded = onLoaded)
    }

    /**
     * Re-reads persisted history even when this process already initialized the repository.
     *
     * This is required when the app's controlled shutdown cleared [AppStateStore], but Android
     * reused the still-running process when the user immediately reopened the UI.
     */
    internal fun reload(onLoaded: (PersistedConversationSnapshot) -> Unit) {
        enqueueSnapshotLoad(pruneFirst = false, onLoaded = onLoaded)
    }

    private fun enqueueSnapshotLoad(
        pruneFirst: Boolean,
        onLoaded: (PersistedConversationSnapshot) -> Unit
    ) {
        enqueueMutation {
            try {
                if (pruneFirst) {
                    deleteStoredMedia(database.pruneToRetentionLimits())
                }
                onLoaded(database.loadInitialSnapshot())
                _storeState.value = ConversationStoreState.Ready
            } catch (error: Exception) {
                Log.e(TAG, "Unable to restore private conversations", error)
                _storeState.value = ConversationStoreState.Error(
                    error.message ?: "Unable to restore conversations"
                )
            }
        }
    }

    /**
     * Suspends until all persistence work queued before this call has finished.
     */
    suspend fun awaitPendingWrites() {
        withContext(dispatcher) { Unit }
    }

    internal suspend fun loadConversationAndWait(
        conversationID: String,
        expectedGeneration: Long = captureWriteGeneration()
    ): PersistedConversationSnapshot? = withContext(dispatcher) {
        if (expectedGeneration != captureWriteGeneration()) return@withContext null
        try {
            database.loadConversation(conversationID)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to load private conversation", error)
            _storeState.value = ConversationStoreState.Error(
                error.message ?: "Unable to load conversation"
            )
            null
        }
    }

    fun upsertMessage(
        conversationID: String,
        aliases: Set<String>,
        displayName: String?,
        message: BitchatMessage,
        isRead: Boolean
    ) {
        enqueueMutation {
            upsertMessageLocked(conversationID, aliases, displayName, message, isRead)
        }
    }

    suspend fun upsertMessageAndWait(
        conversationID: String,
        aliases: Set<String>,
        displayName: String?,
        message: BitchatMessage,
        isRead: Boolean,
        expectedGeneration: Long = captureWriteGeneration()
    ): Boolean = withContext(dispatcher) {
        if (expectedGeneration != captureWriteGeneration()) return@withContext false
        upsertMessageLocked(conversationID, aliases, displayName, message, isRead)
    }

    private fun upsertMessageLocked(
        conversationID: String,
        aliases: Set<String>,
        displayName: String?,
        message: BitchatMessage,
        isRead: Boolean
    ): Boolean = try {
        val result = database.upsertMessage(
            conversationID = conversationID,
            aliases = aliases,
            displayName = displayName,
            message = message,
            isRead = isRead
        )
        deleteStoredMedia(result.orphanedMediaPaths)
        _storeState.value = ConversationStoreState.Ready
        result.inserted
    } catch (error: Exception) {
        Log.e(TAG, "Unable to persist private message", error)
        _storeState.value = ConversationStoreState.Error(
            error.message ?: "Unable to save conversation"
        )
        false
    }

    /** Bound to [conversationID]: the database ignores a message of another conversation. */
    fun updateDeliveryStatus(
        conversationID: String,
        messageID: String,
        status: DeliveryStatus,
        transport: MessageTransport? = null
    ) {
        enqueueMutation {
            try {
                database.updateDeliveryStatus(conversationID, messageID, status, transport)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to persist delivery status: ${error.message}")
            }
        }
    }

    /** Waits for the bound update; true only when the message exists in [conversationID]. */
    internal suspend fun updateDeliveryStatusAndWait(
        conversationID: String,
        messageID: String,
        status: DeliveryStatus,
        transport: MessageTransport?,
        expectedGeneration: Long = captureWriteGeneration()
    ): Boolean = withContext(dispatcher) {
        if (expectedGeneration != captureWriteGeneration()) return@withContext false
        try {
            database.updateDeliveryStatus(conversationID, messageID, status, transport)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to persist delivery status: ${error.message}")
            false
        }
    }

    sealed interface StatusRead {
        data class Found(val status: DeliveryStatus?) : StatusRead
        data object Missing : StatusRead
        data object Unavailable : StatusRead
    }

    /** After every queued write (same single-thread dispatcher); an erase since the call wins. */
    internal suspend fun deliveryStatusAndWait(
        conversationID: String,
        messageID: String,
        expectedGeneration: Long = captureWriteGeneration()
    ): StatusRead = withContext(dispatcher) {
        if (expectedGeneration != captureWriteGeneration()) return@withContext StatusRead.Unavailable
        try {
            database.deliveryStatusIn(conversationID, messageID)?.let { StatusRead.Found(it.status) } ?: StatusRead.Missing
        } catch (error: Exception) {
            Log.e(TAG, "Unable to read delivery status: ${error.message}")
            StatusRead.Unavailable
        }
    }

    /** My messages still awaiting an app ack, after every queued write. */
    internal suspend fun pendingOutgoingAndWait(
        expectedGeneration: Long = captureWriteGeneration()
    ): List<PendingOutgoingDelivery>? = withContext(dispatcher) {
        if (expectedGeneration != captureWriteGeneration()) return@withContext null
        try {
            database.pendingOutgoing()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to read waiting messages: ${error.message}")
            null
        }
    }

    fun updateBluetoothRetryState(
        conversationID: String,
        messageID: String,
        attempts: Int,
        retryAtMs: Long,
        mayHaveLeft: Boolean
    ) {
        enqueueMutation {
            try {
                database.updateBluetoothRetryState(
                    conversationID, messageID, attempts, retryAtMs, mayHaveLeft
                )
            } catch (error: Exception) {
                Log.e(TAG, "Unable to persist Bluetooth retry state: ${error.message}")
            }
        }
    }

    fun markRead(messageID: String) {
        enqueueMutation {
            try {
                database.markRead(messageID)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to persist local read state: ${error.message}")
            }
        }
    }

    internal suspend fun setConversationReadAndWait(
        conversationID: String,
        isRead: Boolean,
        expectedGeneration: Long = captureWriteGeneration()
    ): ConversationReadResult = withContext(dispatcher) {
        if (expectedGeneration != captureWriteGeneration()) return@withContext ConversationReadResult(success = false)
        try {
            ConversationReadResult(
                success = true,
                affectedMessageID = database.setConversationRead(conversationID, isRead)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to update conversation read state", error)
            _storeState.value = ConversationStoreState.Error(
                error.message ?: "Unable to update conversation"
            )
            ConversationReadResult(success = false)
        }
    }

    fun mergeAliases(targetConversationID: String, aliases: Set<String>) {
        enqueueMutation {
            try {
                database.mergeAliases(targetConversationID, aliases)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to merge conversation aliases: ${error.message}")
            }
        }
    }

    fun updateConversationIdentity(
        conversationID: String,
        aliases: Set<String>,
        displayName: String
    ) {
        enqueueMutation {
            try {
                database.updateConversationIdentity(conversationID, aliases, displayName)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to persist conversation identity: ${error.message}")
            }
        }
    }

    fun deleteConversation(conversationID: String, aliases: Set<String>) {
        enqueueMutation {
            deleteConversationLocked(conversationID, aliases)
        }
    }

    suspend fun deleteConversationAndWait(
        conversationID: String,
        aliases: Set<String>,
        expectedGeneration: Long = captureWriteGeneration()
    ): Boolean = withContext(dispatcher) {
        if (expectedGeneration != captureWriteGeneration()) return@withContext false
        deleteConversationLocked(conversationID, aliases)
    }

    suspend fun restoreConversationAndWait(
        conversationID: String,
        aliases: Set<String>,
        displayName: String?,
        messages: List<BitchatMessage>,
        readMessageIDs: Set<String>,
        expectedGeneration: Long = captureWriteGeneration()
    ): Boolean = withContext(dispatcher) {
        if (expectedGeneration != captureWriteGeneration()) return@withContext false
        try {
            deleteStoredMedia(
                database.restoreConversation(
                    conversationID,
                    aliases,
                    displayName,
                    messages,
                    readMessageIDs
                )
            )
            _storeState.value = ConversationStoreState.Ready
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to restore deleted conversation", error)
            _storeState.value = ConversationStoreState.Error(
                error.message ?: "Unable to restore conversation"
            )
            false
        }
    }

    private fun deleteConversationLocked(
        conversationID: String,
        aliases: Set<String>
    ): Boolean = try {
        deleteStoredMedia(database.deleteConversation(conversationID, aliases))
        _storeState.value = ConversationStoreState.Ready
        true
    } catch (error: Exception) {
        Log.e(TAG, "Unable to delete private conversation", error)
        _storeState.value = ConversationStoreState.Error(
            error.message ?: "Unable to delete conversation"
        )
        false
    }

    fun deleteMessage(messageID: String) {
        enqueueMutation {
            try {
                deleteStoredMedia(database.deleteMessage(messageID))
            } catch (error: Exception) {
                Log.e(TAG, "Unable to delete private message: ${error.message}")
            }
        }
    }

    private fun deleteStoredMedia(paths: Collection<String>) {
        if (paths.isEmpty()) return
        com.bitchat.android.features.file.FileUtils.deleteStoredMediaPaths(
            applicationContext,
            paths
        )
    }

    internal fun closeForTest() {
        database.close()
    }
}

sealed interface ConversationStoreState {
    data object Loading : ConversationStoreState
    data object Ready : ConversationStoreState
    data class Error(val message: String) : ConversationStoreState
}

internal data class ConversationReadResult(
    val success: Boolean,
    val affectedMessageID: String? = null
)

internal data class ConversationUpsertResult(
    val inserted: Boolean,
    val orphanedMediaPaths: Set<String>
)

internal data class PersistedConversationSnapshot(
    val chats: Map<String, List<BitchatMessage>>,
    val readMessageIDs: Set<String>,
    val arrivalOrder: List<String>,
    val deletedMessageIDs: Set<String>,
    val displayNames: Map<String, String> = emptyMap(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val receivedAtByMessageID: Map<String, Long> = emptyMap(),
    val arrivalSequenceByMessageID: Map<String, Long> = emptyMap()
)

/**
 * Versioned SQLite schema for bounded private-message history.
 *
 * Conversation rows are intentionally tiny and remain until explicit deletion. Message rows are
 * bounded by per-conversation, global-row, and payload-byte limits. Media bytes stay in the app's
 * existing media storage; SQLite only stores the message metadata/path already present in content.
 */
internal class ConversationDatabase(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val maxMessagesPerConversation: Int = MAX_MESSAGES_PER_CONVERSATION,
    private val maxMessagesTotal: Int = MAX_MESSAGES_TOTAL,
    private val maxPayloadBytes: Long = MAX_PAYLOAD_BYTES,
    private val maxMediaBytes: Long = MAX_MEDIA_BYTES,
    private val storageCipher: ConversationStorageCipher =
        AndroidConversationStorageCipher()
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "ConversationDatabase"
        const val MAX_MESSAGES_PER_CONVERSATION = 1_000
        const val MAX_MESSAGES_TOTAL = 20_000
        const val MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L
        const val MAX_MEDIA_BYTES = 256L * 1024L * 1024L

        internal const val DEFAULT_DATABASE_NAME = "private_conversations.db"
        internal const val DATABASE_VERSION = 6
        private const val PRUNE_INTERVAL = 64
        private const val PRUNE_BATCH_SIZE = 256
    }

    private val applicationContext = context.applicationContext
    private var writesSinceGlobalPrune = 0

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
        // Overwrite cells removed while migrating legacy plaintext payload columns.
        db.rawQuery("PRAGMA secure_delete = ON", null).use { cursor ->
            if (cursor.moveToFirst()) Unit
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // A v1 database may have written plaintext pages to its WAL before the encrypted migration.
        // Truncating it after SQLite's upgrade transaction prevents those historical frames from
        // lingering after the scrubbed rows are committed.
        runCatching {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                if (cursor.moveToFirst()) Unit
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to truncate conversation WAL: ${error.message}")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                conversation_id TEXT COLLATE NOCASE PRIMARY KEY NOT NULL,
                display_name TEXT,
                display_name_ciphertext BLOB,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE conversation_aliases (
                alias TEXT COLLATE NOCASE PRIMARY KEY NOT NULL,
                conversation_id TEXT COLLATE NOCASE NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE private_messages (
                arrival_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT UNIQUE NOT NULL,
                conversation_id TEXT COLLATE NOCASE NOT NULL,
                sender TEXT NOT NULL,
                content TEXT NOT NULL,
                message_type INTEGER NOT NULL,
                sent_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                is_relay INTEGER NOT NULL,
                original_sender TEXT,
                is_private INTEGER NOT NULL,
                recipient_nickname TEXT,
                sender_peer_id TEXT,
                mentions_json TEXT,
                channel_name TEXT,
                encrypted_content BLOB,
                is_encrypted INTEGER NOT NULL,
                delivery_type INTEGER NOT NULL,
                delivery_text TEXT,
                delivery_at INTEGER,
                delivery_reached INTEGER,
                delivery_total INTEGER,
                sender_nostr_pubkey TEXT,
                payload_ciphertext BLOB NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                transport INTEGER NOT NULL DEFAULT 0,
                bluetooth_attempts INTEGER NOT NULL DEFAULT 0,
                bluetooth_retry_at INTEGER NOT NULL DEFAULT 0,
                bluetooth_may_have_left INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_private_messages_conversation_arrival " +
                "ON private_messages(conversation_id, arrival_sequence)"
        )
        db.execSQL(
            "CREATE INDEX idx_private_messages_read_arrival " +
                "ON private_messages(is_read, arrival_sequence)"
        )
        createAttachmentsTable(db)
        db.execSQL(
            "CREATE INDEX idx_conversation_aliases_conversation " +
                "ON conversation_aliases(conversation_id)"
        )
        db.execSQL(
            """
            CREATE TABLE deleted_private_messages (
                message_id TEXT PRIMARY KEY NOT NULL,
                deleted_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_deleted_private_messages_time " +
                "ON deleted_private_messages(deleted_at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version == 1) {
            migrateVersion1To2(db)
            version = 2
        }
        if (version == 2) {
            db.execSQL(
                "ALTER TABLE private_messages " +
                    "ADD COLUMN received_at INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "UPDATE private_messages SET received_at = sent_at WHERE received_at = 0"
            )
            version = 3
        }
        if (version == 3) {
            createAttachmentsTable(db)
            db.query(
                "private_messages",
                VERSION_4_MESSAGE_COLUMNS,
                null,
                null,
                null,
                null,
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    registerAttachmentLocked(db, cursor.toMessage())
                }
            }
            version = 4
        }
        if (version == 4) {
            // 0 = unknown (history from before the label), 1 = Bluetooth, 2 = Internet.
            db.execSQL("ALTER TABLE private_messages ADD COLUMN transport INTEGER NOT NULL DEFAULT 0")
            version = 5
        }
        if (version == 5) {
            db.execSQL("ALTER TABLE private_messages ADD COLUMN bluetooth_attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE private_messages ADD COLUMN bluetooth_retry_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE private_messages ADD COLUMN bluetooth_may_have_left INTEGER NOT NULL DEFAULT 0")
            version = 6
        }
        check(version == newVersion) {
            "Missing conversation database migration from $version to $newVersion"
        }
    }

    private fun migrateVersion1To2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN display_name_ciphertext BLOB")
        db.execSQL("ALTER TABLE private_messages ADD COLUMN payload_ciphertext BLOB")

        db.query(
            "conversations",
            arrayOf("conversation_id", "display_name"),
            "display_name IS NOT NULL AND display_name != ''",
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val conversationID = cursor.getString(0)
                val displayName = cursor.getString(1)
                db.update(
                    "conversations",
                    ContentValues().apply {
                        put(
                            "display_name_ciphertext",
                            encryptText(displayName, conversationDisplayNameAad(conversationID))
                        )
                        putNull("display_name")
                    },
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(conversationID)
                )
            }
        }

        db.query(
            "private_messages",
            LEGACY_MESSAGE_COLUMNS,
            null,
            null,
            null,
            null,
            "arrival_sequence ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val message = cursor.toLegacyMessage()
                db.update(
                    "private_messages",
                    ContentValues().apply {
                        put(
                            "payload_ciphertext",
                            encryptMessagePayload(message)
                        )
                        scrubLegacyPayloadColumns(this)
                    },
                    "message_id = ?",
                    arrayOf(message.id)
                )
            }
        }
    }

    private fun createAttachmentsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_attachments (
                message_id TEXT PRIMARY KEY NOT NULL,
                path_hash BLOB NOT NULL,
                path_ciphertext BLOB NOT NULL,
                byte_size INTEGER NOT NULL,
                FOREIGN KEY(message_id) REFERENCES private_messages(message_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_message_attachments_path_hash " +
                "ON message_attachments(path_hash)"
        )
    }

    fun loadSnapshot(): PersistedConversationSnapshot {
        return loadMessages(
            selection = null,
            selectionArgs = null,
            orderBy = "arrival_sequence ASC",
            displayNameConversationID = null
        )
    }

    /**
     * Loads one decrypted row per conversation for startup. Full histories are fetched only when
     * the user opens a chat, keeping cold-start work proportional to conversation count rather
     * than retained-message count.
     */
    fun loadInitialSnapshot(): PersistedConversationSnapshot {
        return loadMessages(
            selection = """
                arrival_sequence IN (
                    SELECT MAX(arrival_sequence)
                    FROM private_messages
                    GROUP BY conversation_id
                )
            """.trimIndent(),
            selectionArgs = null,
            orderBy = "arrival_sequence ASC",
            displayNameConversationID = null
        )
    }

    /** Private messages awaiting an app ack, including their bounded Bluetooth retry state. */
    fun pendingOutgoing(): List<PendingOutgoingDelivery> {
        val retryState = readableDatabase.query(
            "private_messages",
            arrayOf("message_id", "bluetooth_attempts", "bluetooth_retry_at", "bluetooth_may_have_left"),
            "(delivery_type = 1 OR (delivery_type = 2 AND transport = 1)) AND is_private = 1",
            null,
            null,
            null,
            null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.string("message_id"), Triple(
                        cursor.int("bluetooth_attempts"),
                        cursor.long("bluetooth_retry_at"),
                        cursor.int("bluetooth_may_have_left") != 0
                    ))
                }
            }
        }
        return loadMessages(
            selection = "(delivery_type = 1 OR (delivery_type = 2 AND transport = 1)) AND is_private = 1",
            selectionArgs = null,
            orderBy = "arrival_sequence ASC",
            displayNameConversationID = null
        ).let { snapshot ->
            snapshot.arrivalOrder.mapNotNull { id ->
                snapshot.chats.entries.firstNotNullOfOrNull { (conversationID, messages) ->
                    messages.firstOrNull { it.id == id }?.let { message ->
                        val retry = retryState[id]
                        PendingOutgoingDelivery(
                            conversationID = conversationID,
                            message = message,
                            bluetoothAttempts = retry?.first ?: 0,
                            bluetoothRetryAtMs = retry?.second ?: 0L,
                            bluetoothMayHaveLeft = retry?.third ?: false
                        )
                    }
                }
            }
        }
    }

    fun updateBluetoothRetryState(
        conversationID: String,
        messageID: String,
        attempts: Int,
        retryAtMs: Long,
        mayHaveLeft: Boolean
    ): Boolean {
        val db = writableDatabase
        val owner = db.rawQuery(
            "SELECT conversation_id FROM private_messages WHERE message_id = ?",
            arrayOf(messageID)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return false
        if (!resolveStoredConversationLocked(db, conversationID).equals(owner, ignoreCase = true)) return false
        return db.update(
            "private_messages",
            ContentValues().apply {
                put("bluetooth_attempts", attempts.coerceAtLeast(0))
                put("bluetooth_retry_at", retryAtMs.coerceAtLeast(0L))
                put("bluetooth_may_have_left", mayHaveLeft.asInt())
            },
            "message_id = ? AND conversation_id = ? AND delivery_type IN (1, 2)",
            arrayOf(messageID, owner)
        ) == 1
    }

    fun loadConversation(conversationID: String): PersistedConversationSnapshot {
        val resolved = resolveStoredConversationLocked(readableDatabase, conversationID)
        return loadMessages(
            selection = "conversation_id = ? COLLATE NOCASE",
            selectionArgs = arrayOf(resolved),
            orderBy = "arrival_sequence ASC",
            displayNameConversationID = resolved
        )
    }

    private fun loadMessages(
        selection: String?,
        selectionArgs: Array<String>?,
        orderBy: String,
        displayNameConversationID: String?
    ): PersistedConversationSnapshot {
        val chats = linkedMapOf<String, MutableList<BitchatMessage>>()
        val readIDs = linkedSetOf<String>()
        val arrivalOrder = mutableListOf<String>()
        val receivedAtByMessageID = linkedMapOf<String, Long>()
        val arrivalSequenceByMessageID = linkedMapOf<String, Long>()
        readableDatabase.query(
            "private_messages",
            MESSAGE_COLUMNS,
            selection,
            selectionArgs,
            null,
            null,
            orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val conversationID = cursor.string("conversation_id")
                val message = cursor.toMessage()
                chats.getOrPut(conversationID) { mutableListOf() }.add(message)
                arrivalOrder.add(message.id)
                receivedAtByMessageID[message.id] = cursor.long("received_at")
                arrivalSequenceByMessageID[message.id] = cursor.long("arrival_sequence")
                if (cursor.boolean("is_read")) readIDs.add(message.id)
            }
        }
        return PersistedConversationSnapshot(
            chats = chats.mapValues { it.value.toList() },
            readMessageIDs = readIDs,
            arrivalOrder = arrivalOrder,
            deletedMessageIDs = loadDeletedMessageIDs(),
            displayNames = loadConversationDisplayNames(displayNameConversationID),
            unreadCounts = loadUnreadCounts(),
            receivedAtByMessageID = receivedAtByMessageID,
            arrivalSequenceByMessageID = arrivalSequenceByMessageID
        )
    }

    private fun loadConversationDisplayNames(
        conversationID: String?
    ): Map<String, String> {
        val selection = conversationID?.let { "conversation_id = ? COLLATE NOCASE" }
        val selectionArgs = conversationID?.let { arrayOf(it) }
        return readableDatabase.query(
            "conversations",
            arrayOf("conversation_id", "display_name", "display_name_ciphertext"),
            selection,
            selectionArgs,
            null,
            null,
            null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val storedConversationID = cursor.string("conversation_id")
                    val encryptedColumn =
                        cursor.getColumnIndexOrThrow("display_name_ciphertext")
                    val encryptedName = if (cursor.isNull(encryptedColumn)) {
                        null
                    } else {
                        cursor.getBlob(encryptedColumn)
                    }
                    val displayName = encryptedName?.let {
                        storageCipher.decrypt(
                            it,
                            conversationDisplayNameAad(storedConversationID)
                        ).toString(Charsets.UTF_8)
                    } ?: cursor.nullableString("display_name")
                    displayName
                        ?.takeIf(String::isNotBlank)
                        ?.let { put(storedConversationID, it) }
                }
            }
        }
    }

    private fun loadDeletedMessageIDs(): Set<String> {
        readableDatabase.query(
            "deleted_private_messages",
            arrayOf("message_id"),
            null,
            null,
            null,
            null,
            "deleted_at ASC"
        ).use { cursor ->
            return buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun loadUnreadCounts(): Map<String, Int> =
        readableDatabase.rawQuery(
            """
            SELECT conversation_id, COUNT(*)
            FROM private_messages
            WHERE is_read = 0
            GROUP BY conversation_id
            """.trimIndent(),
            null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
            }
        }

    fun upsertMessage(
        conversationID: String,
        aliases: Set<String>,
        displayName: String?,
        message: BitchatMessage,
        isRead: Boolean
    ): ConversationUpsertResult {
        val normalizedID = conversationID.trim()
        if (normalizedID.isBlank()) {
            return ConversationUpsertResult(inserted = false, orphanedMediaPaths = emptySet())
        }
        val now = System.currentTimeMillis()
        val orphanedMediaPaths = linkedSetOf<String>()
        var messageInserted = false
        writableDatabase.inTransaction {
            if (isDeletedMessageLocked(this, message.id)) return@inTransaction
            mergeAliasesLocked(
                db = this,
                targetConversationID = normalizedID,
                aliases = aliases + normalizedID,
                displayName = displayName,
                now = now
            )
            val values = message.toContentValues(normalizedID, isRead)
            val inserted = insertWithOnConflict(
                "private_messages",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            messageInserted = inserted != -1L
            if (inserted != -1L) {
                registerAttachmentLocked(this, message)
            }
            if (inserted == -1L) {
                val existingConversation = rawQuery(
                    "SELECT conversation_id, is_read FROM private_messages WHERE message_id = ?",
                    arrayOf(message.id)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.string("conversation_id") to cursor.boolean("is_read")
                    } else {
                        null
                    }
                }
                if (existingConversation != null) {
                    val canonicalExisting = resolveStoredConversationLocked(
                        this,
                        existingConversation.first
                    )
                    if (!canonicalExisting.equals(normalizedID, ignoreCase = true)) {
                        mergeAliasesLocked(
                            db = this,
                            targetConversationID = normalizedID,
                            aliases = aliases + canonicalExisting,
                            displayName = displayName,
                            now = now
                        )
                    }
                    if (isRead && !existingConversation.second) {
                        update(
                            "private_messages",
                            ContentValues().apply { put("is_read", 1) },
                            "message_id = ?",
                            arrayOf(message.id)
                        )
                    }
                }
            }
            updateConversationMetadataLocked(this, normalizedID, displayName, now)
            orphanedMediaPaths += pruneConversationLocked(this, normalizedID)
        }

        writesSinceGlobalPrune += 1
        if (writesSinceGlobalPrune >= PRUNE_INTERVAL) {
            writesSinceGlobalPrune = 0
            orphanedMediaPaths += pruneToRetentionLimits()
        }
        return ConversationUpsertResult(
            inserted = messageInserted,
            orphanedMediaPaths = orphanedMediaPaths
        )
    }

    /**
     * Applies [status] to [messageID] only when it belongs to [conversationID] or one of its
     * aliases; returns whether it does. [transport] is recorded only when [status] is applied: a
     * refused downgrade changes nothing.
     */
    fun updateDeliveryStatus(
        conversationID: String,
        messageID: String,
        status: DeliveryStatus,
        transport: MessageTransport? = null
    ): Boolean {
        val db = writableDatabase
        var found = false
        var owner: String? = null
        val existing = db.rawQuery(
            """
            SELECT ${MESSAGE_COLUMNS.joinToString(",")}
            FROM private_messages WHERE message_id = ?
            """.trimIndent(),
            arrayOf(messageID)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                found = true
                owner = cursor.string("conversation_id")
                cursor.toMessage().deliveryStatus
            } else {
                null
            }
        }

        if (!found) return false
        if (!resolveStoredConversationLocked(db, conversationID).equals(owner, ignoreCase = true)) return false
        val mayReplace = when {
            status is DeliveryStatus.Failed ->
                existing !is DeliveryStatus.Delivered && existing !is DeliveryStatus.Read
            existing is DeliveryStatus.Failed -> true
            else -> statusPriority(status) >= statusPriority(existing)
        }
        // Matched: the message is in that conversation, even when the status is not an upgrade.
        if (!mayReplace) return true
        db.inTransaction {
            val current = rawQuery(
                "SELECT ${MESSAGE_COLUMNS.joinToString(",")} " +
                    "FROM private_messages WHERE message_id = ? AND conversation_id = ?",
                arrayOf(messageID, owner)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toMessage() else null
            } ?: return@inTransaction
            update(
                "private_messages",
                ContentValues().apply {
                    putAll(deliveryValues(status, includeSensitiveText = false))
                    if (status is DeliveryStatus.Delivered || status is DeliveryStatus.Read ||
                        status is DeliveryStatus.Failed || status is DeliveryStatus.Sending
                    ) {
                        put("bluetooth_attempts", 0)
                        put("bluetooth_retry_at", 0L)
                        put("bluetooth_may_have_left", 0)
                    }
                    put(
                        "payload_ciphertext",
                        encryptMessagePayload(current.copy(deliveryStatus = status))
                    )
                    if (transport != null) put("transport", transport.asColumn())
                },
                "message_id = ? AND conversation_id = ?",
                arrayOf(messageID, owner)
            )
        }
        return true
    }

    /** A message's stored status; null when it is not in [conversationID] (or an alias). */
    fun deliveryStatusIn(conversationID: String, messageID: String): StoredDeliveryStatus? {
        val db = readableDatabase
        val owner = resolveStoredConversationLocked(db, conversationID)
        return db.rawQuery(
            "SELECT delivery_type, delivery_at, delivery_reached, delivery_total FROM private_messages " +
                "WHERE message_id = ? AND conversation_id = ? COLLATE NOCASE",
            arrayOf(messageID, owner)
        ).use { cursor ->
            if (cursor.moveToFirst()) StoredDeliveryStatus(cursor.toDeliveryStatus(deliveryText = null)) else null
        }
    }

    fun markRead(messageID: String) {
        writableDatabase.update(
            "private_messages",
            ContentValues().apply { put("is_read", 1) },
            "message_id = ?",
            arrayOf(messageID)
        )
    }

    fun setConversationRead(conversationID: String, isRead: Boolean): String? {
        val resolved = resolveStoredConversationLocked(writableDatabase, conversationID)
        return writableDatabase.inTransaction {
            if (isRead) {
                update(
                    "private_messages",
                    ContentValues().apply { put("is_read", 1) },
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(resolved)
                )
                null
            } else {
                val latestMessageID = rawQuery(
                    """
                    SELECT message_id
                    FROM private_messages
                    WHERE conversation_id = ? COLLATE NOCASE
                    ORDER BY arrival_sequence DESC
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(resolved)
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                if (latestMessageID != null) {
                    update(
                        "private_messages",
                        ContentValues().apply { put("is_read", 0) },
                        "message_id = ?",
                        arrayOf(latestMessageID)
                    )
                }
                latestMessageID
            }
        }
    }

    fun mergeAliases(targetConversationID: String, aliases: Set<String>) {
        if (targetConversationID.isBlank()) return
        writableDatabase.inTransaction {
            mergeAliasesLocked(
                db = this,
                targetConversationID = targetConversationID,
                aliases = aliases + targetConversationID,
                displayName = null,
                now = System.currentTimeMillis()
            )
        }
    }

    fun updateConversationIdentity(
        conversationID: String,
        aliases: Set<String>,
        displayName: String
    ) {
        if (conversationID.isBlank() || displayName.isBlank()) return
        writableDatabase.inTransaction {
            mergeAliasesLocked(
                db = this,
                targetConversationID = conversationID,
                aliases = aliases + conversationID,
                displayName = displayName,
                now = System.currentTimeMillis()
            )
        }
    }

    fun deleteConversation(conversationID: String, aliases: Set<String>): Set<String> =
        writableDatabase.inTransaction {
            val ids = linkedSetOf<String>()
            (aliases + conversationID).forEach { value ->
                ids.add(resolveStoredConversationLocked(this, value))
                ids.add(value)
            }
            ids.filter { it.isNotBlank() }.forEach { id ->
                execSQL(
                    """
                    INSERT OR REPLACE INTO deleted_private_messages(message_id, deleted_at)
                    SELECT message_id, ? FROM private_messages
                    WHERE conversation_id = ? COLLATE NOCASE
                    """.trimIndent(),
                    arrayOf<Any>(System.currentTimeMillis(), id)
                )
            }
            val messageIDs = ids
                .filter(String::isNotBlank)
                .flatMapTo(linkedSetOf()) { id ->
                    queryMessageIDsLocked(this, id)
                }
            val attachmentCandidates = attachmentCandidatesLocked(this, messageIDs)
            ids.filter { it.isNotBlank() }.forEach { id ->
                delete(
                    "conversations",
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(id)
                )
            }
            pruneDeletedMessageIDsLocked(this)
            unreferencedAttachmentPathsLocked(this, attachmentCandidates)
        }

    fun deleteMessage(messageID: String): Set<String> =
        writableDatabase.inTransaction {
            val attachmentCandidates = attachmentCandidatesLocked(this, listOf(messageID))
            insertWithOnConflict(
                "deleted_private_messages",
                null,
                ContentValues().apply {
                    put("message_id", messageID)
                    put("deleted_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            delete("private_messages", "message_id = ?", arrayOf(messageID))
            delete(
                "conversations",
                """
                NOT EXISTS (
                    SELECT 1 FROM private_messages
                    WHERE private_messages.conversation_id = conversations.conversation_id
                )
                """.trimIndent(),
                null
            )
            pruneDeletedMessageIDsLocked(this)
            unreferencedAttachmentPathsLocked(this, attachmentCandidates)
        }

    fun restoreConversation(
        conversationID: String,
        aliases: Set<String>,
        displayName: String?,
        messages: List<BitchatMessage>,
        readMessageIDs: Set<String>
    ): Set<String> {
        if (messages.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        writableDatabase.inTransaction {
            mergeAliasesLocked(
                db = this,
                targetConversationID = conversationID,
                aliases = aliases + conversationID,
                displayName = displayName,
                now = now
            )
            messages.forEach { message ->
                delete(
                    "deleted_private_messages",
                    "message_id = ?",
                    arrayOf(message.id)
                )
                val inserted = insertWithOnConflict(
                    "private_messages",
                    null,
                    message.toContentValues(
                        conversationID = conversationID,
                        isRead = message.id in readMessageIDs
                    ),
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (inserted != -1L) registerAttachmentLocked(this, message)
            }
            updateConversationMetadataLocked(this, conversationID, displayName, now)
        }
        return pruneToRetentionLimits()
    }

    fun pruneToRetentionLimits(): Set<String> {
        val db = writableDatabase
        val orphanedMediaPaths = linkedSetOf<String>()
        db.inTransaction {
            rawQuery(
                "SELECT conversation_id FROM conversations",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    orphanedMediaPaths += pruneConversationLocked(this, cursor.getString(0))
                }
            }

            var stats = storageStatsLocked(this)
            while (
                stats.messageCount > maxMessagesTotal ||
                stats.payloadBytes > maxPayloadBytes ||
                stats.mediaBytes > maxMediaBytes
            ) {
                val candidateLimit = if (stats.messageCount > maxMessagesTotal) {
                    (stats.messageCount - maxMessagesTotal)
                        .coerceAtMost(PRUNE_BATCH_SIZE.toLong())
                        .toInt()
                } else {
                    // Payload sizes vary, so recalculate after each removal instead of
                    // discarding an entire batch unnecessarily.
                    1
                }
                val candidates = pruneCandidatesLocked(
                    db = this,
                    readOnly = true,
                    preserveLatest = true,
                    limit = candidateLimit
                ).ifEmpty {
                    pruneCandidatesLocked(
                        db = this,
                        readOnly = false,
                        preserveLatest = true,
                        limit = candidateLimit
                    )
                }.ifEmpty {
                    // A store with one message in every conversation still needs a hard bound.
                    // Prefer retiring read conversations before unread ones.
                    pruneCandidatesLocked(
                        db = this,
                        readOnly = true,
                        preserveLatest = false,
                        limit = candidateLimit
                    )
                }.ifEmpty {
                    pruneCandidatesLocked(
                        db = this,
                        readOnly = false,
                        preserveLatest = false,
                        limit = candidateLimit
                    )
                }
                if (candidates.isEmpty()) break
                tombstoneMessagesLocked(this, candidates)
                val attachmentCandidates = attachmentCandidatesLocked(this, candidates)
                candidates.forEach { messageID ->
                    delete("private_messages", "message_id = ?", arrayOf(messageID))
                }
                orphanedMediaPaths +=
                    unreferencedAttachmentPathsLocked(this, attachmentCandidates)
                deleteEmptyConversationsLocked(this)
                pruneDeletedMessageIDsLocked(this)
                stats = storageStatsLocked(this)
            }
        }
        db.rawQuery("PRAGMA incremental_vacuum(128)", null).use { }
        return orphanedMediaPaths
    }

    private fun mergeAliasesLocked(
        db: SQLiteDatabase,
        targetConversationID: String,
        aliases: Set<String>,
        displayName: String?,
        now: Long
    ) {
        val normalizedAliases = aliases
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val sourceIDs = normalizedAliases
            .mapTo(linkedSetOf()) { resolveStoredConversationLocked(db, it) }
            .plus(
                db.queryValues(
                    table = "conversation_aliases",
                    resultColumn = "conversation_id",
                    selectionValues = normalizedAliases
                )
            )
        val effectiveDisplayName = displayName
            ?: (listOf(targetConversationID) + sourceIDs + normalizedAliases)
                .asSequence()
                .mapNotNull { storedConversationDisplayNameLocked(db, it) }
                .firstOrNull()
        ensureConversationLocked(db, targetConversationID, effectiveDisplayName, now)

        sourceIDs
            .filterNot { it.equals(targetConversationID, ignoreCase = true) }
            .forEach { sourceID ->
                db.update(
                    "private_messages",
                    ContentValues().apply { put("conversation_id", targetConversationID) },
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(sourceID)
                )
                db.update(
                    "conversation_aliases",
                    ContentValues().apply { put("conversation_id", targetConversationID) },
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(sourceID)
                )
                db.delete(
                    "conversations",
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(sourceID)
                )
            }

        (normalizedAliases + sourceIDs + targetConversationID).forEach { alias ->
            db.insertWithOnConflict(
                "conversation_aliases",
                null,
                ContentValues().apply {
                    put("alias", alias)
                    put("conversation_id", targetConversationID)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        updateConversationMetadataLocked(
            db,
            targetConversationID,
            effectiveDisplayName,
            now
        )
    }

    private fun storedConversationDisplayNameLocked(
        db: SQLiteDatabase,
        conversationID: String
    ): String? = db.query(
        "conversations",
        arrayOf("conversation_id", "display_name", "display_name_ciphertext"),
        "conversation_id = ? COLLATE NOCASE",
        arrayOf(conversationID),
        null,
        null,
        null,
        "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val storedConversationID = cursor.string("conversation_id")
        cursor.blobOrNull("display_name_ciphertext")?.let {
            storageCipher.decrypt(
                it,
                conversationDisplayNameAad(storedConversationID)
            ).toString(Charsets.UTF_8)
        } ?: cursor.nullableString("display_name")
    }

    private fun ensureConversationLocked(
        db: SQLiteDatabase,
        conversationID: String,
        displayName: String?,
        now: Long
    ) {
        db.insertWithOnConflict(
            "conversations",
            null,
            ContentValues().apply {
                put("conversation_id", conversationID)
                putNull("display_name")
                if (!displayName.isNullOrBlank()) {
                    put(
                        "display_name_ciphertext",
                        encryptText(displayName, conversationDisplayNameAad(conversationID))
                    )
                }
                put("created_at", now)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun updateConversationMetadataLocked(
        db: SQLiteDatabase,
        conversationID: String,
        displayName: String?,
        now: Long
    ) {
        db.update(
            "conversations",
            ContentValues().apply {
                if (!displayName.isNullOrBlank()) {
                    putNull("display_name")
                    put(
                        "display_name_ciphertext",
                        encryptText(displayName, conversationDisplayNameAad(conversationID))
                    )
                }
                put("updated_at", now)
            },
            "conversation_id = ? COLLATE NOCASE",
            arrayOf(conversationID)
        )
    }

    private fun resolveStoredConversationLocked(db: SQLiteDatabase, value: String): String {
        if (value.isBlank()) return value
        return db.rawQuery(
            "SELECT conversation_id FROM conversation_aliases WHERE alias = ? COLLATE NOCASE",
            arrayOf(value)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else value
        }
    }

    private fun isDeletedMessageLocked(db: SQLiteDatabase, messageID: String): Boolean =
        db.longForQuery(
            "SELECT COUNT(*) FROM deleted_private_messages WHERE message_id = ?",
            arrayOf(messageID)
        ) > 0L

    private fun pruneDeletedMessageIDsLocked(db: SQLiteDatabase) {
        val excess = (
            db.longForQuery(
                "SELECT COUNT(*) FROM deleted_private_messages",
                emptyArray()
            ) - maxMessagesTotal
            ).coerceAtLeast(0L)
        if (excess == 0L) return
        db.execSQL(
            """
            DELETE FROM deleted_private_messages
            WHERE message_id IN (
                SELECT message_id FROM deleted_private_messages
                ORDER BY deleted_at ASC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(excess)
        )
    }

    private fun pruneConversationLocked(
        db: SQLiteDatabase,
        conversationID: String
    ): Set<String> {
        val count = db.longForQuery(
            "SELECT COUNT(*) FROM private_messages WHERE conversation_id = ? COLLATE NOCASE",
            arrayOf(conversationID)
        )
        val excess = (count - maxMessagesPerConversation).coerceAtLeast(0L)
        if (excess == 0L) return emptySet()
        return db.rawQuery(
            """
            SELECT message_id
            FROM private_messages
            WHERE conversation_id = ? COLLATE NOCASE
                AND arrival_sequence < (
                    SELECT MAX(arrival_sequence)
                    FROM private_messages
                    WHERE conversation_id = ? COLLATE NOCASE
                )
            ORDER BY is_read DESC, arrival_sequence ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(conversationID, conversationID, excess.toString())
        ).use { cursor ->
            val ids = mutableListOf<String>()
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
            val attachmentCandidates = attachmentCandidatesLocked(db, ids)
            tombstoneMessagesLocked(db, ids)
            ids.forEach { db.delete("private_messages", "message_id = ?", arrayOf(it)) }
            pruneDeletedMessageIDsLocked(db)
            unreferencedAttachmentPathsLocked(db, attachmentCandidates)
        }
    }

    private fun storageStatsLocked(db: SQLiteDatabase): ConversationStorageStats =
        db.rawQuery(
            """
            SELECT COUNT(*),
                COALESCE(SUM(
                    COALESCE(length(payload_ciphertext), 0)
                ), 0),
                COALESCE((
                    SELECT SUM(unique_attachment.byte_size)
                    FROM (
                        SELECT MAX(byte_size) AS byte_size
                        FROM message_attachments
                        GROUP BY path_hash
                    ) AS unique_attachment
                ), 0)
            FROM private_messages
            """.trimIndent(),
            null
        ).use { cursor ->
            cursor.moveToFirst()
            ConversationStorageStats(
                messageCount = cursor.getLong(0),
                payloadBytes = cursor.getLong(1),
                mediaBytes = cursor.getLong(2)
            )
        }

    private fun registerAttachmentLocked(db: SQLiteDatabase, message: BitchatMessage) {
        if (message.type !in MEDIA_MESSAGE_TYPES) return
        val path = appOwnedCanonicalPath(message.content) ?: return
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        db.insertWithOnConflict(
            "message_attachments",
            null,
            ContentValues().apply {
                put("message_id", message.id)
                put("path_hash", MessageDigest.getInstance("SHA-256").digest(pathBytes))
                put(
                    "path_ciphertext",
                    storageCipher.encrypt(pathBytes, attachmentPathAad(message.id))
                )
                put("byte_size", File(path).takeIf(File::isFile)?.length() ?: 0L)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun queryMessageIDsLocked(
        db: SQLiteDatabase,
        conversationID: String
    ): List<String> =
        db.query(
            "private_messages",
            arrayOf("message_id"),
            "conversation_id = ? COLLATE NOCASE",
            arrayOf(conversationID),
            null,
            null,
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun attachmentCandidatesLocked(
        db: SQLiteDatabase,
        messageIDs: Collection<String>
    ): List<AttachmentCandidate> {
        if (messageIDs.isEmpty()) return emptyList()
        return buildList {
            messageIDs.toList().chunked(400).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.rawQuery(
                    """
                    SELECT message_id, path_hash, path_ciphertext
                    FROM message_attachments
                    WHERE message_id IN ($placeholders)
                    """.trimIndent(),
                    chunk.toTypedArray()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val messageID = cursor.getString(0)
                        val path = storageCipher.decrypt(
                            cursor.getBlob(2),
                            attachmentPathAad(messageID)
                        ).toString(Charsets.UTF_8)
                        add(
                            AttachmentCandidate(
                                pathHash = cursor.getBlob(1),
                                canonicalPath = path
                            )
                        )
                    }
                }
            }
        }
    }

    private fun unreferencedAttachmentPathsLocked(
        db: SQLiteDatabase,
        candidates: Collection<AttachmentCandidate>
    ): Set<String> = candidates
        .filter { candidate ->
            db.rawQuery(
                "SELECT COUNT(*) FROM message_attachments WHERE hex(path_hash) = ?",
                arrayOf(candidate.pathHash.toHex().uppercase())
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0) == 0L
            }
        }
        .mapTo(linkedSetOf(), AttachmentCandidate::canonicalPath)

    private fun appOwnedCanonicalPath(value: String): String? {
        val file = runCatching { File(value.trim()).canonicalFile }.getOrNull() ?: return null
        val roots = listOf(applicationContext.filesDir, applicationContext.cacheDir)
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return file.path.takeIf { path ->
            roots.any { root ->
                path == root.path || path.startsWith(root.path + File.separator)
            }
        }
    }

    private fun attachmentPathAad(messageID: String): ByteArray =
        "attachment:$messageID:path".toByteArray(Charsets.UTF_8)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class ConversationStorageStats(
        val messageCount: Long,
        val payloadBytes: Long,
        val mediaBytes: Long
    )

    private data class AttachmentCandidate(
        val pathHash: ByteArray,
        val canonicalPath: String
    )

    private val MEDIA_MESSAGE_TYPES = setOf(
        BitchatMessageType.Audio,
        BitchatMessageType.Image,
        BitchatMessageType.File
    )

    private fun pruneCandidatesLocked(
        db: SQLiteDatabase,
        readOnly: Boolean,
        preserveLatest: Boolean,
        limit: Int
    ): List<String> {
        val readClause = if (readOnly) "AND candidate.is_read = 1" else ""
        val newerMessageClause = if (preserveLatest) {
            """
            AND EXISTS (
                SELECT 1
                FROM private_messages AS newer
                WHERE newer.conversation_id = candidate.conversation_id
                    AND newer.arrival_sequence > candidate.arrival_sequence
            )
            """.trimIndent()
        } else {
            ""
        }
        return db.rawQuery(
            """
            SELECT candidate.message_id
            FROM private_messages AS candidate
            WHERE 1 = 1
            $newerMessageClause
            $readClause
            ORDER BY candidate.arrival_sequence ASC
            LIMIT $limit
            """.trimIndent(),
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun deleteEmptyConversationsLocked(db: SQLiteDatabase) {
        db.delete(
            "conversations",
            """
            NOT EXISTS (
                SELECT 1 FROM private_messages
                WHERE private_messages.conversation_id = conversations.conversation_id
            )
            """.trimIndent(),
            null
        )
    }

    private fun tombstoneMessagesLocked(
        db: SQLiteDatabase,
        messageIDs: Collection<String>
    ) {
        val deletedAt = System.currentTimeMillis()
        messageIDs.forEach { messageID ->
            db.insertWithOnConflict(
                "deleted_private_messages",
                null,
                ContentValues().apply {
                    put("message_id", messageID)
                    put("deleted_at", deletedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    private fun SQLiteDatabase.queryValues(
        table: String,
        resultColumn: String,
        selectionValues: Set<String>
    ): Set<String> {
        if (selectionValues.isEmpty()) return emptySet()
        val placeholders = selectionValues.joinToString(",") { "?" }
        return rawQuery(
            "SELECT $resultColumn FROM $table WHERE alias IN ($placeholders)",
            selectionValues.toTypedArray()
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun SQLiteDatabase.longForQuery(sql: String, args: Array<String>): Long =
        rawQuery(sql, args).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    private fun BitchatMessage.toContentValues(
        conversationID: String,
        isRead: Boolean
    ): ContentValues = ContentValues().apply {
        put("message_id", id)
        put("conversation_id", conversationID)
        // Version 1 columns remain for lossless migration compatibility, but sensitive values are
        // scrubbed and only the authenticated encrypted payload is populated for new writes.
        put("sender", "")
        put("content", "")
        put("message_type", type.ordinal)
        put("sent_at", timestamp.time)
        put("received_at", System.currentTimeMillis())
        put("is_relay", isRelay.asInt())
        putNull("original_sender")
        put("is_private", isPrivate.asInt())
        putNull("recipient_nickname")
        putNull("sender_peer_id")
        putNull("mentions_json")
        putNull("channel_name")
        putNull("encrypted_content")
        put("is_encrypted", isEncrypted.asInt())
        putAll(deliveryValues(deliveryStatus, includeSensitiveText = false))
        putNull("sender_nostr_pubkey")
        put("payload_ciphertext", encryptMessagePayload(this@toContentValues))
        put("is_read", isRead.asInt())
        put("transport", transport.asColumn())
    }

    private fun MessageTransport?.asColumn(): Int = when (this) {
        null -> 0
        MessageTransport.BLUETOOTH -> 1
        MessageTransport.INTERNET -> 2
    }

    private fun Cursor.transportOrNull(): MessageTransport? =
        getColumnIndex("transport").takeIf { it >= 0 }?.let { index ->
            when (getInt(index)) {
                1 -> MessageTransport.BLUETOOTH
                2 -> MessageTransport.INTERNET
                else -> null
            }
        }

    private fun deliveryValues(
        status: DeliveryStatus?,
        includeSensitiveText: Boolean = true
    ): ContentValues = ContentValues().apply {
        when (status) {
            null -> put("delivery_type", 0)
            DeliveryStatus.Sending -> put("delivery_type", 1)
            DeliveryStatus.Sent -> put("delivery_type", 2)
            is DeliveryStatus.Delivered -> {
                put("delivery_type", 3)
                if (includeSensitiveText) put("delivery_text", status.to) else putNull("delivery_text")
                put("delivery_at", status.at.time)
            }
            is DeliveryStatus.Read -> {
                put("delivery_type", 4)
                if (includeSensitiveText) put("delivery_text", status.by) else putNull("delivery_text")
                put("delivery_at", status.at.time)
            }
            is DeliveryStatus.Failed -> {
                put("delivery_type", 5)
                if (includeSensitiveText) put("delivery_text", status.reason) else putNull("delivery_text")
            }
            is DeliveryStatus.PartiallyDelivered -> {
                put("delivery_type", 6)
                put("delivery_reached", status.reached)
                put("delivery_total", status.total)
            }
        }
    }

    private fun statusPriority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Failed -> 0
        DeliveryStatus.Sending -> 1
        DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
    }

    private fun Cursor.toMessage(): BitchatMessage {
        val messageID = string("message_id")
        val encryptedPayload = blobOrNull("payload_ciphertext")
            ?: error("Conversation message $messageID has no encrypted payload")
        val payload = decryptMessagePayload(messageID, encryptedPayload)
        return BitchatMessage(
            id = messageID,
            sender = payload.sender,
            content = payload.content,
            type = BitchatMessageType.entries.getOrElse(int("message_type")) {
                BitchatMessageType.Message
            },
            timestamp = Date(long("sent_at")),
            isRelay = boolean("is_relay"),
            originalSender = payload.originalSender,
            isPrivate = boolean("is_private"),
            recipientNickname = payload.recipientNickname,
            senderPeerID = payload.senderPeerID,
            mentions = payload.mentions,
            channel = payload.channel,
            encryptedContent = payload.encryptedContent,
            isEncrypted = boolean("is_encrypted"),
            deliveryStatus = toDeliveryStatus(payload.deliveryText),
            senderNostrPubkey = payload.senderNostrPubkey,
            transport = transportOrNull()
        )
    }

    private fun Cursor.toLegacyMessage(): BitchatMessage = BitchatMessage(
        id = string("message_id"),
        sender = string("sender"),
        content = string("content"),
        type = BitchatMessageType.entries.getOrElse(int("message_type")) {
            BitchatMessageType.Message
        },
        timestamp = Date(long("sent_at")),
        isRelay = boolean("is_relay"),
        originalSender = nullableString("original_sender"),
        isPrivate = boolean("is_private"),
        recipientNickname = nullableString("recipient_nickname"),
        senderPeerID = nullableString("sender_peer_id"),
        mentions = nullableString("mentions_json")?.let(::jsonStringList),
        channel = nullableString("channel_name"),
        encryptedContent = blobOrNull("encrypted_content"),
        isEncrypted = boolean("is_encrypted"),
        deliveryStatus = toDeliveryStatus(),
        senderNostrPubkey = nullableString("sender_nostr_pubkey")
    )

    private fun Cursor.toDeliveryStatus(deliveryText: String? = nullableString("delivery_text")):
        DeliveryStatus? = when (int("delivery_type")) {
        1 -> DeliveryStatus.Sending
        2 -> DeliveryStatus.Sent
        3 -> DeliveryStatus.Delivered(
            to = deliveryText.orEmpty(),
            at = Date(nullableLong("delivery_at") ?: 0L)
        )
        4 -> DeliveryStatus.Read(
            by = deliveryText.orEmpty(),
            at = Date(nullableLong("delivery_at") ?: 0L)
        )
        5 -> DeliveryStatus.Failed(deliveryText.orEmpty())
        6 -> DeliveryStatus.PartiallyDelivered(
            reached = nullableInt("delivery_reached") ?: 0,
            total = nullableInt("delivery_total") ?: 0
        )
        else -> null
    }

    private fun jsonStringList(json: String): List<String> {
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }

    private fun Cursor.index(column: String): Int = getColumnIndexOrThrow(column)
    private fun Cursor.string(column: String): String = getString(index(column))
    private fun Cursor.nullableString(column: String): String? =
        index(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(column: String): Int = getInt(index(column))
    private fun Cursor.nullableInt(column: String): Int? =
        index(column).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.long(column: String): Long = getLong(index(column))
    private fun Cursor.nullableLong(column: String): Long? =
        index(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.boolean(column: String): Boolean = int(column) != 0
    private fun Cursor.blobOrNull(column: String): ByteArray? =
        index(column).let { if (isNull(it)) null else getBlob(it) }
    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private fun encryptMessagePayload(message: BitchatMessage): ByteArray {
        val json = JSONObject().apply {
            put("sender", message.sender)
            put("content", message.content)
            putNullable("original_sender", message.originalSender)
            putNullable("recipient_nickname", message.recipientNickname)
            putNullable("sender_peer_id", message.senderPeerID)
            putNullable("mentions_json", message.mentions?.let(::JSONArray))
            putNullable("channel_name", message.channel)
            putNullable(
                "encrypted_content",
                message.encryptedContent?.let {
                    Base64.encodeToString(it, Base64.NO_WRAP)
                }
            )
            putNullable("delivery_text", message.deliveryStatus.sensitiveText())
            putNullable("sender_nostr_pubkey", message.senderNostrPubkey)
        }
        return storageCipher.encrypt(
            json.toString().toByteArray(Charsets.UTF_8),
            messagePayloadAad(message.id)
        )
    }

    private fun decryptMessagePayload(
        messageID: String,
        encryptedPayload: ByteArray
    ): StoredMessagePayload {
        val json = JSONObject(
            storageCipher.decrypt(encryptedPayload, messagePayloadAad(messageID))
                .toString(Charsets.UTF_8)
        )
        return StoredMessagePayload(
            sender = json.getString("sender"),
            content = json.getString("content"),
            originalSender = json.optionalString("original_sender"),
            recipientNickname = json.optionalString("recipient_nickname"),
            senderPeerID = json.optionalString("sender_peer_id"),
            mentions = json.optionalArray("mentions_json")?.let(::jsonStringList),
            channel = json.optionalString("channel_name"),
            encryptedContent = json.optionalString("encrypted_content")?.let {
                Base64.decode(it, Base64.NO_WRAP)
            },
            deliveryText = json.optionalString("delivery_text"),
            senderNostrPubkey = json.optionalString("sender_nostr_pubkey")
        )
    }

    private fun encryptText(value: String, associatedData: ByteArray): ByteArray =
        storageCipher.encrypt(value.toByteArray(Charsets.UTF_8), associatedData)

    private fun messagePayloadAad(messageID: String): ByteArray =
        "message:$messageID".toByteArray(Charsets.UTF_8)

    private fun conversationDisplayNameAad(conversationID: String): ByteArray =
        "conversation:$conversationID:display-name".toByteArray(Charsets.UTF_8)

    private fun scrubLegacyPayloadColumns(values: ContentValues) {
        values.put("sender", "")
        values.put("content", "")
        values.putNull("original_sender")
        values.putNull("recipient_nickname")
        values.putNull("sender_peer_id")
        values.putNull("mentions_json")
        values.putNull("channel_name")
        values.putNull("encrypted_content")
        values.putNull("delivery_text")
        values.putNull("sender_nostr_pubkey")
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optionalArray(key: String): JSONArray? =
        if (!has(key) || isNull(key)) null else getJSONArray(key)

    private fun jsonStringList(array: JSONArray): List<String> =
        buildList(array.length()) {
            for (index in 0 until array.length()) add(array.getString(index))
        }

    private fun DeliveryStatus?.sensitiveText(): String? = when (this) {
        is DeliveryStatus.Delivered -> to
        is DeliveryStatus.Read -> by
        is DeliveryStatus.Failed -> reason
        else -> null
    }

    private data class StoredMessagePayload(
        val sender: String,
        val content: String,
        val originalSender: String?,
        val recipientNickname: String?,
        val senderPeerID: String?,
        val mentions: List<String>?,
        val channel: String?,
        val encryptedContent: ByteArray?,
        val deliveryText: String?,
        val senderNostrPubkey: String?
    )

    private val MESSAGE_COLUMNS = arrayOf(
        "arrival_sequence",
        "message_id",
        "conversation_id",
        "sender",
        "content",
        "message_type",
        "sent_at",
        "received_at",
        "is_relay",
        "original_sender",
        "is_private",
        "recipient_nickname",
        "sender_peer_id",
        "mentions_json",
        "channel_name",
        "encrypted_content",
        "is_encrypted",
        "delivery_type",
        "delivery_text",
        "delivery_at",
        "delivery_reached",
        "delivery_total",
        "sender_nostr_pubkey",
        "payload_ciphertext",
        "is_read",
        "transport"
    )

    /** Upgrade steps before version 5 run while the transport column does not exist yet. */
    private val VERSION_4_MESSAGE_COLUMNS = MESSAGE_COLUMNS.filterNot { it == "transport" }.toTypedArray()

    private val LEGACY_MESSAGE_COLUMNS = VERSION_4_MESSAGE_COLUMNS.filterNot {
        it == "payload_ciphertext" || it == "received_at"
    }.toTypedArray()
}
