package app.secure.kyber.roomdb

import androidx.room.*
import app.secure.kyber.backend.models.ChatModel

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY CAST(time AS INTEGER) ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE senderOnion = :senderOnion ORDER BY CAST(time AS INTEGER) ASC")
    suspend fun getBySender(senderOnion: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE senderOnion = :senderOnion")
    suspend fun deleteAllBySender(senderOnion: String)

    @Query("UPDATE messages SET expiresAt = 1 WHERE senderOnion = :senderOnion AND CAST(time AS INTEGER) <= CAST(:cutoffTime AS INTEGER) AND type != 'WIPE_RES'")
    suspend fun expireAllBySender(senderOnion: String, cutoffTime: String)

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageByMessageId(messageId: String): MessageEntity?

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM messages WHERE senderOnion = :senderOnion AND isSent = 0 AND id > :lastSeenId AND type NOT IN ('DISAPPEAR_SYSTEM', 'KEY_UPDATE')")
    suspend fun getUnreadCount(senderOnion: String, lastSeenId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE senderOnion = :senderOnion AND isSent = 1)")
    suspend fun hasOutgoingMessages(senderOnion: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE senderOnion = :senderOnion AND isSent = 0)")
    suspend fun hasIncomingMessages(senderOnion: String): Boolean

    @Query("SELECT * FROM messages WHERE senderOnion = :senderOnion ORDER BY CAST(time AS INTEGER) ASC")
    fun observeAll(senderOnion: String): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    @Query("UPDATE messages SET uploadState = :state, uploadProgress = :progress WHERE messageId = :messageId")
    suspend fun updateUploadProgress(messageId: String, state: String, progress: Int)

    @Query("UPDATE messages SET downloadState = :state, downloadProgress = :progress WHERE messageId = :messageId")
    suspend fun updateDownloadProgress(messageId: String, state: String, progress: Int)

    @Query("SELECT * FROM messages WHERE downloadState = :state")
    suspend fun getMessagesByDownloadState(state: String): List<MessageEntity>

    @Query("UPDATE messages SET uploadState = :state, localFilePath = :path, uploadProgress = 100 WHERE messageId = :messageId")
    suspend fun setUploadDone(messageId: String, state: String, path: String?)

    @Query("UPDATE messages SET localFilePath = :path WHERE messageId = :messageId")
    suspend fun setLocalFilePath(messageId: String, path: String)

    @Query("UPDATE messages SET downloadState = :state, localFilePath = :path, downloadProgress = 100 WHERE messageId = :messageId")
    suspend fun setDownloadDone(messageId: String, state: String, path: String?)

    @Query("UPDATE messages SET remoteMediaId = :mediaId WHERE messageId = :messageId")
    suspend fun setRemoteMediaId(messageId: String, mediaId: String)

    @Query("UPDATE messages SET time = :time, expiresAt = :expiresAt WHERE messageId = :messageId")
    suspend fun updateSentTime(messageId: String, time: String, expiresAt: Long)

    @Query("SELECT * FROM messages WHERE remoteMediaId = :mediaId AND isSent = 0 LIMIT 1")
    suspend fun getByRemoteMediaId(mediaId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE expiresAt > 0 AND expiresAt < :currentTimeMillis")
    suspend fun getExpiredMessages(currentTimeMillis: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE expiresAt > 0 AND expiresAt < :currentTimeMillis")
    suspend fun deleteExpiredMessages(currentTimeMillis: Long)

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): MessageEntity?

    @Query("UPDATE messages SET thumbnailPath = :path WHERE messageId = :messageId")
    suspend fun setThumbnailPath(messageId: String, path: String)

    @Query("DELETE FROM messages WHERE keyFingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String)

    /**
     * Normal accepted chat list.
     */
    @Query("""
        WITH latest_time AS (
          SELECT senderOnion,
                 MAX(CAST(CASE WHEN updatedAt != '' THEN updatedAt ELSE time END AS INTEGER)) AS maxTime
          FROM messages
          WHERE type NOT IN ('DISAPPEAR_SYSTEM', 'KEY_UPDATE')
          GROUP BY senderOnion
        ),
        latest_row AS (
          SELECT m.*
          FROM messages m
          JOIN latest_time lt
            ON lt.senderOnion = m.senderOnion
           AND CAST(CASE WHEN m.updatedAt != '' THEN m.updatedAt ELSE m.time END AS INTEGER) = lt.maxTime
          WHERE m.id = (
            SELECT MAX(m2.id)
            FROM messages m2
            WHERE m2.senderOnion = m.senderOnion
              AND CAST(CASE WHEN m2.updatedAt != '' THEN m2.updatedAt ELSE m2.time END AS INTEGER) = lt.maxTime
          )
        )
        SELECT lr.senderOnion AS onionAddress,
               c.name AS name,
               lr.msg  AS lastMessage,
               CASE WHEN lr.updatedAt != '' THEN lr.updatedAt ELSE lr.time END AS time,
               lr.reaction AS reaction,
               lr.type AS type,
               lr.keyFingerprint AS keyFingerprint,
               lr.iv AS iv
        FROM latest_row lr
        LEFT JOIN contacts c
          ON c.onionAddress = lr.senderOnion
        WHERE (
            c.onionAddress IS NOT NULL
            OR lr.senderOnion IN (SELECT senderOnion FROM messages WHERE isSent = 1)
        )
        ORDER BY CAST(CASE WHEN lr.updatedAt != '' THEN lr.updatedAt ELSE lr.time END AS INTEGER) DESC, lr.id DESC
    """)
    fun observeAllLastMsgs(): kotlinx.coroutines.flow.Flow<List<ChatModel>>

    /**
     * Incoming pending message requests.
     */
    @Query("""
        WITH latest_time AS (
          SELECT senderOnion,
                 MAX(CAST(time AS INTEGER)) AS maxTime
          FROM messages
          WHERE isSent = 0 AND type NOT IN ('DISAPPEAR_SYSTEM', 'KEY_UPDATE')
          GROUP BY senderOnion
        ),
        latest_row AS (
          SELECT m.*
          FROM messages m
          JOIN latest_time lt
            ON lt.senderOnion = m.senderOnion
           AND CAST(m.time AS INTEGER) = lt.maxTime
          WHERE m.id = (
            SELECT MAX(m2.id)
            FROM messages m2
            WHERE m2.senderOnion = m.senderOnion
              AND CAST(m2.time AS INTEGER) = lt.maxTime
              AND m2.isSent = 0
          )
        )
        SELECT lr.senderOnion AS onionAddress,
               NULL AS name,
               lr.msg AS lastMessage,
               lr.time AS time,
               lr.reaction AS reaction,
               lr.type AS type,
               lr.keyFingerprint AS keyFingerprint,
               lr.iv AS iv
        FROM latest_row lr
        WHERE lr.senderOnion NOT IN (SELECT onionAddress FROM contacts WHERE isContact = 1)
          AND lr.senderOnion NOT IN (SELECT senderOnion FROM messages WHERE isSent = 1)
          AND lr.senderOnion IN (
              SELECT senderOnion FROM messages WHERE isRequest = 1 AND isSent = 0
          )
        ORDER BY CAST(lr.time AS INTEGER) DESC
    """)
    fun observeIncomingRequests(): kotlinx.coroutines.flow.Flow<List<ChatModel>>

    /**
     * Paginated query: returns the [limit] most-recent messages for a conversation,
     * in ascending order (oldest first within the batch so RecyclerView shows newest at bottom).
     * Used for the initial render — shows messages quickly without decrypting the full history.
     */
    @Query("""
        SELECT * FROM messages
        WHERE senderOnion = :senderOnion
        ORDER BY CAST(time AS INTEGER) DESC
        LIMIT :limit
    """)
    fun observeRecent(senderOnion: String, limit: Int): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    /**
     * One-shot fetch of messages older than [beforeTime], newest-first, page-limited.
     * Caller reverses the results so they can be prepended to the top of the list.
     */
    @Query("""
        SELECT * FROM messages
        WHERE senderOnion = :senderOnion
          AND CAST(time AS INTEGER) < :beforeTime
        ORDER BY CAST(time AS INTEGER) DESC
        LIMIT :limit
    """)
    suspend fun getOlderMessages(senderOnion: String, beforeTime: Long, limit: Int): List<MessageEntity>

    // ── Message status: Delivered / Seen ──────────────────────────────────────

    /** Mark a single sent message as delivered by the recipient device. */
    @Query("UPDATE messages SET deliveredAt = :ts WHERE messageId = :messageId AND isSent = 1 AND deliveredAt = 0")
    suspend fun updateDeliveredAt(messageId: String, ts: Long)

    /**
     * Mark all sent messages to a given contact as delivered.
     * Called by SyncWorker when the first incoming message from that contact is processed,
     * confirming the contact's device is online and able to pull messages.
     */
    @Query("UPDATE messages SET deliveredAt = :ts WHERE senderOnion = :senderOnion AND isSent = 1 AND deliveredAt = 0")
    suspend fun markAllDeliveredForContact(senderOnion: String, ts: Long)

    /**
     * Mark all sent messages to a contact as seen.
     * Called when the local user opens the private chat (recipient-side event piggybacked
     * via the next outgoing message's seen-receipt field, or tracked locally for self-view).
     *
     * In practice this is called on the *sender's* device when the recipient opens
     * the chat — which is signalled by the recipient sending any outgoing message or
     * when the ChatFragment receives a "seen" marker from the contact's device.
     *
     * For now, we call this on the *receiver's* own device when they open the chat
     * so our *own* sent messages to that person are flagged seen (the sender would need
     * a separate receipt channel to know; this self-marks for local UX consistency).
     */
    @Query("UPDATE messages SET seenAt = :ts WHERE senderOnion = :senderOnion AND isSent = 1 AND seenAt = 0")
    suspend fun markAllSeenForContact(senderOnion: String, ts: Long)

    /**
     * Get the count of received messages from a contact that we have not seen yet.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE senderOnion = :senderOnion AND isSent = 0 AND seenAt = 0 AND type NOT IN ('DISAPPEAR_SYSTEM', 'KEY_UPDATE')")
    suspend fun getUnreadReceivedCount(senderOnion: String): Int

    /**
     * Mark all received messages from a contact as seen locally so we don't keep sending receipts.
     */
    @Query("UPDATE messages SET seenAt = :ts WHERE senderOnion = :senderOnion AND isSent = 0 AND seenAt = 0")
    suspend fun markReceivedMessagesAsSeen(senderOnion: String, ts: Long)
}


