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
    @Json(name = "reaction") val reaction: String = ""
)
