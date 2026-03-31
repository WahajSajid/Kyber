package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.secure.kyber.Utils.EncryptionUtils

@Entity(
    tableName = "messages",
    indices = [Index(value = ["apiMessageId"], unique = true), Index(value = ["messageId"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val messageId: String,
    val apiMessageId: String? = null,
    val msg: String, // Encrypted text
    val senderOnion: String,
    val time: String,
    val isSent: Boolean,
    val type: String = "TEXT",
    val uri: String? = null, // Encrypted URI/path
    val ampsJson: String = "",
    var reaction: String = "",
    var updatedAt: String = "",
    val isRequest: Boolean = false,
    val uploadState: String = "done",      // "pending","uploading","done","failed"
    val downloadState: String = "done",    // "pending","downloading","done","failed"
    val uploadProgress: Int = 100,         // 0-100
    val downloadProgress: Int = 100,       // 0-100
    val localFilePath: String? = null,     // absolute path once file is local
    val remoteMediaId: String? = null,     // chunk group id for reassembly
    val mediaDurationMs: Long = 0L,
    val mediaSizeBytes: Long = 0L,
    val thumbnailPath: String? = null   // local path to thumbnail image
)

/**
 * UI Model for private chat messages.
 * Contains decrypted values for display purposes.
 * Wraps the original [entity] for Room operations.
 */
data class MessageUiModel(
    val entity: MessageEntity,
    val decryptedMsg: String,
    val decryptedUri: String?,
    val decryptedAmpsJson: String = ""
) {
    val id: Long get() = entity.id
    val messageId: String get() = entity.messageId
    val msg: String get() = entity.msg
    val senderOnion: String get() = entity.senderOnion
    val time: String get() = entity.time
    val isSent: Boolean get() = entity.isSent
    val type: String get() = entity.type
    val isRequest: Boolean get() = entity.isRequest
    val uploadState: String get() = entity.uploadState
    val downloadState: String get() = entity.downloadState
    val uploadProgress: Int get() = entity.uploadProgress
    val downloadProgress: Int get() = entity.downloadProgress
    val localFilePath: String? get() = entity.localFilePath
    val remoteMediaId: String? get() = entity.remoteMediaId
    val mediaDurationMs: Long get() = entity.mediaDurationMs

    // Add to MessageUiModel:
    val thumbnailPath: String? get() = entity.thumbnailPath

    // FIX: Safely route to the decrypted metadata payload
    val ampsJson: String get() = decryptedAmpsJson

    var reaction: String get() = entity.reaction
        set(value) { entity.reaction = value }
    var updatedAt: String get() = entity.updatedAt
        set(value) { entity.updatedAt = value }

    val apiMessageId: String? get() = entity.apiMessageId
}

fun MessageEntity.toUiModel(): MessageUiModel {
    // Safely decrypt the message text / caption
    val decryptedMsg = try {
        val rawMsg = EncryptionUtils.decrypt(this.msg)
        if (rawMsg.isNotBlank()) rawMsg else this.msg
    } catch (e: Exception) {
        this.msg
    }

    val decryptedUri: String? = if (!this.uri.isNullOrBlank()) {
        try {
            val rawUri = EncryptionUtils.decrypt(this.uri)
            if (rawUri.isNotBlank()) rawUri else this.uri
        } catch (e: Exception) {
            this.uri
        }
    } else {
        null
    }

    val decryptedAmps: String = if (!this.ampsJson.isNullOrBlank()) {
        try {
            val rawAmps = EncryptionUtils.decrypt(this.ampsJson)
            if (rawAmps.isNotBlank()) rawAmps else this.ampsJson
        } catch (e: Exception) {
            this.ampsJson
        }
    } else {
        ""
    }

    return MessageUiModel(
        entity = this,
        decryptedMsg = decryptedMsg,
        decryptedUri = decryptedUri,
        decryptedAmpsJson = decryptedAmps
    )
}
