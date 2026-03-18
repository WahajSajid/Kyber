package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val onionAddress: String,
    val name: String
)
