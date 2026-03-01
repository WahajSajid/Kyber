package app.secure.kyber.roomdb

import androidx.lifecycle.LiveData
import androidx.room.*
import app.secure.kyber.backend.models.ChatModel

@Dao
interface GroupMessageDao {

    @Query("SELECT * FROM group_messages WHERE group_id == :groupId ORDER BY time ASC")
    fun getGroupMessages(groupId: String): LiveData<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_messages WHERE group_id = :groupId ORDER BY time ASC")
    fun observeAllGroupMessages(groupId: String): kotlinx.coroutines.flow.Flow<MutableList<GroupMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroupMessage(groupMessage: GroupMessageEntity)

    @Update
    suspend fun updateGroupMessage(groupMessage: GroupMessageEntity)

    @Delete
    suspend fun deleteGroupMessage(groupMessage: GroupMessageEntity)

    @Query("SELECT * FROM group_messages WHERE group_id = :groupId ORDER BY CAST(time AS INTEGER) DESC LIMIT 1")
    suspend fun getLatestMessage(groupId: String): GroupMessageEntity?

    @Query(
        "WITH latest_time AS (\n" +
                "  SELECT group_id,\n" +
                "         MAX(CAST(time AS INTEGER)) AS maxTime\n" +
                "  FROM group_messages\n" +
                "  GROUP BY group_id\n" +
                "),\n" +
                "latest_row AS (\n" +
                "  SELECT m.*\n" +
                "  FROM group_messages m\n" +
                "  JOIN latest_time lt\n" +
                "    ON lt.group_id = m.group_id\n" +
                "   AND CAST(m.time AS INTEGER) = lt.maxTime\n" +
                "  -- break ties on identical time by picking the greatest id\n" +
                "  WHERE m.messageId = (\n" +
                "    SELECT MAX(m2.messageId)\n" +
                "    FROM group_messages m2\n" +
                "    WHERE m2.group_id = m.group_id\n" +
                "      AND CAST(m2.time AS INTEGER) = lt.maxTime\n" +
                "  )\n" +
                ")\n" +
                "SELECT lr.group_id AS id,\n" +
                "       c.groupName AS name,\n" +
                "       lr.msg  AS lastMessage,\n" +
                "       lr.time\n" +
                "FROM latest_row lr\n" +
                "LEFT JOIN `groups` c\n" +
                "  ON c.groupId= lr.group_id\n" +
                "ORDER BY CAST(lr.time AS INTEGER) DESC, lr.group_id DESC;"
    )
    fun observeAllLastMsgs(): kotlinx.coroutines.flow.Flow<List<ChatModel>>

}
