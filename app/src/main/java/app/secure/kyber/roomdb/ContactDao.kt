package app.secure.kyber.roomdb

// data/ContactDao.kt
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contacts: ContactEntity): Long

    @Query("SELECT * FROM contacts ORDER BY name")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY name ASC")
    suspend fun getAll(): List<ContactEntity>

    @Delete
    suspend fun delete(contact: ContactEntity)


    @Query("DELETE FROM contacts")
    suspend fun clear()
}
