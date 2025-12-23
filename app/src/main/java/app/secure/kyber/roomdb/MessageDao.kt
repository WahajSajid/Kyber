package app.secure.kyber.roomdb

import androidx.room.*
import app.secure.kyber.backend.models.ChatModel

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY time ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE senderId = :sender ORDER BY time ASC")
    suspend fun getBySender(sender: String): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT * FROM messages WHERE senderId = :sender ORDER BY time ASC")
    fun observeAll(sender: String): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    @Query("WITH latest_time AS (\n" +
            "  SELECT senderId,\n" +
            "         MAX(CAST(time AS INTEGER)) AS maxTime\n" +
            "  FROM messages\n" +
            "  GROUP BY senderId\n" +
            "),\n" +
            "latest_row AS (\n" +
            "  SELECT m.*\n" +
            "  FROM messages m\n" +
            "  JOIN latest_time lt\n" +
            "    ON lt.senderId = m.senderId\n" +
            "   AND CAST(m.time AS INTEGER) = lt.maxTime\n" +
            "  -- break ties on identical time by picking the greatest id\n" +
            "  WHERE m.id = (\n" +
            "    SELECT MAX(m2.id)\n" +
            "    FROM messages m2\n" +
            "    WHERE m2.senderId = m.senderId\n" +
            "      AND CAST(m2.time AS INTEGER) = lt.maxTime\n" +
            "  )\n" +
            ")\n" +
            "SELECT lr.senderId AS id,\n" +
            "       c.name AS name,\n" +
            "       lr.msg  AS lastMessage,\n" +
            "       lr.time\n" +
            "FROM latest_row lr\n" +
            "LEFT JOIN contacts c\n" +
            "  ON c.id = lr.senderId\n" +
            "ORDER BY CAST(lr.time AS INTEGER) DESC, lr.id DESC;")
    fun observeAllLastMsgs(): kotlinx.coroutines.flow.Flow<List<ChatModel>>

}
