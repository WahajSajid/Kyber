package app.secure.kyber.roomdb

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow


@Dao
interface GroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(groups: GroupsEntity): Long

    @Update
    suspend fun update(groups: GroupsEntity)

    @Query("SELECT * FROM `groups` ORDER BY timeSpan DESC, createdAt DESC")
    fun observeAll(): Flow<List<GroupsEntity>>

    @Query("SELECT * FROM `groups` ORDER BY timeSpan DESC")
    fun getAll(): LiveData<List<GroupsEntity>>

    @Query("SELECT * FROM `groups`")
    suspend fun getAllGroupsList(): List<GroupsEntity>

    @Query("SELECT createdAt FROM `groups` where groupId = :groupId")
    suspend fun getCreationDate(groupId: String): Long

    @Query("SELECT noOfMembers FROM `groups` where groupId = :groupId")
    suspend fun getNoOfMembers(groupId: String): Int

    @Query("SELECT * FROM `groups` WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: String): GroupsEntity?

    @Query("SELECT groupName FROM `groups` WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupName(groupId: String): String?

    /** Increment unread count by 1 for the given group (called when a new incoming message arrives) */
    @Query("UPDATE `groups` SET newMessagesCount = newMessagesCount + 1 WHERE groupId = :groupId")
    suspend fun incrementUnread(groupId: String)

    /** Reset unread count to 0 (called when the user opens the group chat) */
    @Query("UPDATE `groups` SET newMessagesCount = 0 WHERE groupId = :groupId")
    suspend fun resetUnread(groupId: String)

    /** Update last message preview and time for the group list */
    @Query("UPDATE `groups` SET lastMessage = :lastMsg, timeSpan = :time WHERE groupId = :groupId")
    suspend fun updateLastMessage(groupId: String, lastMsg: String, time: Long)

    @Query("DELETE FROM `groups` WHERE groupId = :groupId")
    suspend fun deleteById(groupId: String)

    @Query("DELETE FROM `groups` WHERE groupId NOT IN (:groupIds)")
    suspend fun deleteGroupsNotIn(groupIds: List<String>)

    @Query("DELETE FROM `groups`")
    suspend fun deleteAll()
}
