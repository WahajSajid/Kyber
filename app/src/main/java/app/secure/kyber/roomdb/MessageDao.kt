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

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageByMessageId(messageId: String): MessageEntity?

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM messages WHERE senderOnion = :senderOnion AND isSent = 0 AND id > :lastSeenId")
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

    @Query("UPDATE messages SET uploadState = :state, localFilePath = :path, uploadProgress = 100 WHERE messageId = :messageId")
    suspend fun setUploadDone(messageId: String, state: String, path: String?)

    @Query("UPDATE messages SET downloadState = :state, localFilePath = :path, downloadProgress = 100 WHERE messageId = :messageId")
    suspend fun setDownloadDone(messageId: String, state: String, path: String?)

    @Query("UPDATE messages SET remoteMediaId = :mediaId WHERE messageId = :messageId")
    suspend fun setRemoteMediaId(messageId: String, mediaId: String)

    @Query("SELECT * FROM messages WHERE remoteMediaId = :mediaId AND isSent = 0 LIMIT 1")
    suspend fun getByRemoteMediaId(mediaId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): MessageEntity?


    /**
     * Normal accepted chat list.
     *
     * A conversation belongs here when:
     *   (a) the sender IS a known contact (accepted), OR
     *   (b) we have sent at least one message to that onion (we initiated).
     *
     * Additionally, the latest message for that conversation must NOT be an
     * un-accepted inbound request — i.e. the latest row must not have
     * isRequest = 1 while the sender is still unknown and we never replied.
     * We achieve this by requiring the same contact/outgoing conditions on
     * the final SELECT rather than only on the CTE filter.
     */
    @Query("""
        WITH latest_time AS (
          SELECT senderOnion,
                 MAX(CAST(CASE WHEN updatedAt != '' THEN updatedAt ELSE time END AS INTEGER)) AS maxTime
          FROM messages
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
               lr.type AS type
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
     *
     * A conversation is a "request" when ALL of the following are true:
     *   1. Every message from that sender has isSent = 0 (we never replied).
     *   2. The sender is NOT in the contacts table (not yet accepted).
     *   3. At least one of the inbound messages carries isRequest = 1
     *      (the sender flagged it as a first-contact message).
     *
     * Condition 3 is the key fix: it means purely structural coincidences
     * (e.g. messages from an unknown onion that happen to pass condition 1+2
     * but were not flagged as requests) do not pollute the requests list.
     */
    @Query("""
        WITH latest_time AS (
          SELECT senderOnion,
                 MAX(CAST(time AS INTEGER)) AS maxTime
          FROM messages
          WHERE isSent = 0
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
               lr.type AS type
        FROM latest_row lr
        WHERE lr.senderOnion NOT IN (SELECT onionAddress FROM contacts)
          AND lr.senderOnion NOT IN (SELECT senderOnion FROM messages WHERE isSent = 1)
          AND lr.senderOnion IN (
              SELECT senderOnion FROM messages WHERE isRequest = 1 AND isSent = 0
          )
        ORDER BY CAST(lr.time AS INTEGER) DESC
    """)
    fun observeIncomingRequests(): kotlinx.coroutines.flow.Flow<List<ChatModel>>
}