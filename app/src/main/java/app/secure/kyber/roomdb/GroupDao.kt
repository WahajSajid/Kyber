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

    @Query("SELECT * FROM `groups` ORDER BY groupName")
    fun observeAll(): Flow<List<GroupsEntity>>

    @Query("SELECT * FROM `groups` ORDER BY timeSpan DESC")
    fun getAll(): LiveData<List<GroupsEntity>>

    @Query("SELECT createdAt FROM `groups` where groupId = :groupId")
    suspend fun getCreationDate(groupId:String):Long

    @Query("SELECT noOfMembers FROM `groups` where groupId = :groupId")
    suspend fun getNoOfMembers(groupId: String): Int
    
    @Query("SELECT * FROM `groups` WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: String): GroupsEntity?
}
