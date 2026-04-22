package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.secure.kyber.Utils.MessageEncryptionManager

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
    val thumbnailPath: String? = null,   // local path to thumbnail image
    val keyFingerprint: String? = null,  // Fingerprint of the key used to encrypt/decrypt
    val iv: String? = null,              // IV for AES-GCM decryption
    val expiresAt: Long = 0L,             // Timestamp when the message expires (0 = no expiry)
    val replyToText: String = ""           // Text of the message this is replying to (empty = not a reply)
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
    val thumbnailPath: String? get() = entity.thumbnailPath
    val keyFingerprint: String? get() = entity.keyFingerprint
    val iv: String? get() = entity.iv
    val expiresAt: Long get() = entity.expiresAt

    // FIX: Safely route to the decrypted metadata payload
    val ampsJson: String get() = decryptedAmpsJson

    var reaction: String get() = entity.reaction
        set(value) { entity.reaction = value }
    var updatedAt: String get() = entity.updatedAt
        set(value) { entity.updatedAt = value }

    val apiMessageId: String? get() = entity.apiMessageId
    val replyToText: String get() = entity.replyToText
}

suspend fun MessageEntity.toUiModel(context: android.content.Context): MessageUiModel {
    val decryptedMsg = MessageEncryptionManager.decryptSmart(
        context, this.msg, this.senderOnion, this.keyFingerprint, this.iv
    )

    val decryptedUri = if (!this.uri.isNullOrBlank()) {
        MessageEncryptionManager.decryptSmart(
            context, this.uri!!, this.senderOnion, this.keyFingerprint, this.iv
        )
    } else null

    val decryptedAmps = if (!this.ampsJson.isNullOrBlank()) {
        MessageEncryptionManager.decryptSmart(
            context, this.ampsJson, this.senderOnion, this.keyFingerprint, this.iv
        )
    } else ""

    return MessageUiModel(
        entity = this,
        decryptedMsg = decryptedMsg,
        decryptedUri = decryptedUri,
        decryptedAmpsJson = decryptedAmps
    )
}
