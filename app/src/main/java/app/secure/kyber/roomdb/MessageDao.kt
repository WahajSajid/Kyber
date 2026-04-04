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
               lr.type AS type,
               lr.keyFingerprint AS keyFingerprint,
               lr.iv AS iv
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
