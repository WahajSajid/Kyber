package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "group_messages")
data class GroupMessageEntity(
    @PrimaryKey val messageId: String,
    val group_id: String,
    val msg: String,
    val senderId: String,
    val senderName: String,
    val time: String,
    val isSent: Boolean,
    val type: String = "TEXT",
    val uri: String? = null,
    val ampsJson: String? = null,
    var reaction: String = ""
)
