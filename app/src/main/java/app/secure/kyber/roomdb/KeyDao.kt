package app.secure.kyber.roomdb

import androidx.room.*

@Dao
interface KeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: KeyEntity)

    @Update
    suspend fun update(key: KeyEntity)

    @Query("SELECT * FROM user_keys WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveKey(): KeyEntity?

    @Query("SELECT * FROM user_keys WHERE status = 'OLD_RETENTION' ORDER BY activatedAt DESC")
    suspend fun getRetentionKeys(): List<KeyEntity>

    @Query("SELECT * FROM user_keys WHERE keyId = :keyId LIMIT 1")
    suspend fun getKeyById(keyId: String): KeyEntity?

    @Query("DELETE FROM user_keys WHERE status = 'EXPIRED'")
    suspend fun deleteExpiredKeys()

    @Query("UPDATE user_keys SET status = :newStatus WHERE keyId = :keyId")
    suspend fun updateStatus(keyId: String, newStatus: String)

    @Query("SELECT * FROM user_keys ORDER BY createdAt DESC")
    suspend fun getAllKeys(): List<KeyEntity>
}
