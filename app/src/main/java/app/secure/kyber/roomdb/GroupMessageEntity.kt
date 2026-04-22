package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "group_messages")
data class GroupMessageEntity(
    @PrimaryKey val messageId: String,
    val group_id: String,
    val msg: String,
    val senderOnion: String,
    val senderName: String,
    val time: String,
    val isSent: Boolean,
    val type: String = "TEXT",
    val uri: String? = null,
    val ampsJson: String = "",
    var reaction: String = "",
    val uploadState: String = "done", // "uploading", "done", "failed"
    val downloadState: String = "done", // "downloading", "done", "failed"
    val uploadProgress: Int = 100,
    val downloadProgress: Int = 100,
    val localFilePath: String? = null,
    val remoteMediaId: String? = null,
    val mediaDurationMs: Long = 0,
    val mediaSizeBytes: Long = 0,
    val expiresAt: Long = 0L,
    val thumbnailPath: String? = null,   // local path to thumbnail JPEG
    val replyToText: String = ""           // Text of the message this is replying to (empty = not a reply)
)

