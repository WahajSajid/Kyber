package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val onionAddress: String,
    val name: String,
    val publicKey: String? = null,
    val keyVersion: String? = null,
    val lastKeyUpdate: Long = 0L,
    val shortId: String? = null,
    val isContact: Boolean = true
)
