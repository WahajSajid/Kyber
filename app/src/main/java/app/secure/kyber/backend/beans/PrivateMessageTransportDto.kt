package app.secure.kyber.backend.beans

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PrivateMessageTransportDto(
    @Json(name = "messageId") val messageId: String,
    @Json(name = "msg") val msg: String,
    @Json(name = "senderOnion") val senderOnion: String,
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "type") val type: String = "TEXT",
    @Json(name = "uri") val uri: String? = null,
    @Json(name = "ampsJson") val ampsJson: String = "",
    @Json(name = "reaction") val reaction: String = "",
    @Json(name = "isRequest") val isRequest: Boolean = false,
    @Json(name = "senderName") val senderName: String = "",
    @Json(name = "isAcceptance") val isAcceptance: Boolean = false,
    
    // Encryption metadata
    @Json(name = "iv") val iv: String? = null,
    @Json(name = "senderKeyFingerprint") val senderKeyFingerprint: String? = null,
    @Json(name = "recipientKeyFingerprint") val recipientKeyFingerprint: String? = null,
    @Json(name = "senderPublicKey") val senderPublicKey: String? = null
)
