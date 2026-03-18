package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val msg: String,
    val senderOnion: String,
    val time: String,
    val isSent: Boolean,
    val type: String = "TEXT",
    val uri: String? = null,
    val ampsJson: String = "",
    var reaction: String = ""
)
