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

    // FIX: Safely route to the decrypted metadata payload
    val ampsJson: String get() = decryptedAmpsJson

    var reaction: String get() = entity.reaction
        set(value) { entity.reaction = value }
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

    // FIX: Properly and securely decrypt the URI for media messages on the receiver side
    // Includes a fallback to handle unencrypted paths cleanly (e.g. from background services).
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

    // FIX: Decrypt the waveform amps JSON mapping!
    // Resolves audio duration and playback failures directly.
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