package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["apiMessageId"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val apiMessageId: String? = null, // ID from the Tor Gateway API
    val msg: String,
    val senderOnion: String,
    val time: String,
    val isSent: Boolean,
    val type: String = "TEXT",
    val uri: String? = null,
    val ampsJson: String = "",
    var reaction: String = ""
)
