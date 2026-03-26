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

    // FIX: Cast time to INTEGER for numeric sorting
    @Query("SELECT * FROM messages ORDER BY CAST(time AS INTEGER) ASC")
    suspend fun getAll(): List<MessageEntity>

    // FIX: Cast time to INTEGER for numeric sorting
    @Query("SELECT * FROM messages WHERE senderOnion = :senderOnion ORDER BY CAST(time AS INTEGER) ASC")
    suspend fun getBySender(senderOnion: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageByMessageId(messageId: String): MessageEntity?

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    // FIX: Dynamically count unread private messages based on the highest seen ID
    @Query("SELECT COUNT(*) FROM messages WHERE senderOnion = :senderOnion AND isSent = 0 AND id > :lastSeenId")
    suspend fun getUnreadCount(senderOnion: String, lastSeenId: Long): Int

    // FIX: Cast time to INTEGER for numeric sorting
    @Query("SELECT * FROM messages WHERE senderOnion = :senderOnion ORDER BY CAST(time AS INTEGER) ASC")
    fun observeAll(senderOnion: String): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    @Query("WITH latest_time AS (\n" +
            "  SELECT senderOnion,\n" +
            "         MAX(CAST(CASE WHEN updatedAt != '' THEN updatedAt ELSE time END AS INTEGER)) AS maxTime\n" +
            "  FROM messages\n" +
            "  GROUP BY senderOnion\n" +
            "),\n" +
            "latest_row AS (\n" +
            "  SELECT m.*\n" +
            "  FROM messages m\n" +
            "  JOIN latest_time lt\n" +
            "    ON lt.senderOnion = m.senderOnion\n" +
            "   AND CAST(CASE WHEN m.updatedAt != '' THEN m.updatedAt ELSE m.time END AS INTEGER) = lt.maxTime\n" +
            "  WHERE m.id = (\n" +
            "    SELECT MAX(m2.id)\n" +
            "    FROM messages m2\n" +
            "    WHERE m2.senderOnion = m.senderOnion\n" +
            "      AND CAST(CASE WHEN m2.updatedAt != '' THEN m2.updatedAt ELSE m2.time END AS INTEGER) = lt.maxTime\n" +
            "  )\n" +
            ")\n" +
            "SELECT lr.senderOnion AS onionAddress,\n" +
            "       c.name AS name,\n" +
            "       lr.msg  AS lastMessage,\n" +
            "       CASE WHEN lr.updatedAt != '' THEN lr.updatedAt ELSE lr.time END AS time,\n" +
            "       lr.reaction AS reaction,\n" +
            "       lr.type AS type\n" +
            "FROM latest_row lr\n" +
            "LEFT JOIN contacts c\n" +
            "  ON c.onionAddress = lr.senderOnion\n" +
            "ORDER BY CAST(CASE WHEN lr.updatedAt != '' THEN lr.updatedAt ELSE lr.time END AS INTEGER) DESC, lr.id DESC;")
    fun observeAllLastMsgs(): kotlinx.coroutines.flow.Flow<List<ChatModel>>
}