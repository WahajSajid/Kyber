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
    val messageId: String, // Sender-generated unique ID
    val apiMessageId: String? = null, // ID from the Tor Gateway API
    val msg: String, // Encrypted text
    val senderOnion: String,
    val time: String,
    val isSent: Boolean,
    val type: String = "TEXT",
    val uri: String? = null, // Encrypted URI/path
    val ampsJson: String = "",
    var reaction: String = ""
)

/**
 * UI Model for private chat messages.
 * Contains decrypted values for display purposes.
 * Wraps the original [entity] for Room operations.
 */
data class MessageUiModel(
    val entity: MessageEntity,
    val decryptedMsg: String,
    val decryptedUri: String?
) {
    val id: Long get() = entity.id
    val messageId: String get() = entity.messageId
    val msg: String get() = entity.msg // Encrypted raw string
    val senderOnion: String get() = entity.senderOnion
    val time: String get() = entity.time
    val isSent: Boolean get() = entity.isSent
    val type: String get() = entity.type
    val ampsJson: String get() = entity.ampsJson
    var reaction: String get() = entity.reaction
        set(value) { entity.reaction = value }
    val apiMessageId: String? get() = entity.apiMessageId
}

fun MessageEntity.toUiModel(): MessageUiModel {
    return MessageUiModel(
        entity = this,
        decryptedMsg = EncryptionUtils.decrypt(this.msg),
        decryptedUri = if (this.uri != null) EncryptionUtils.decrypt(this.uri) else null
    )
}
