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
    /**
     * The human-readable display name of the sender.
     * Populated on every outgoing message so the receiver can learn the
     * sender's name without a separate profile-exchange round-trip.
     * Used specifically to:
     *   (a) cache the request sender's name on the receiver side so it can
     *       be shown in MessageRequestsFragment and saved on acceptance, and
     *   (b) carry the receiver's name back to the sender in the acceptance
     *       acknowledgement message.
     */
    @Json(name = "senderName") val senderName: String = "",
    /**
     * True on the single acknowledgement message the receiver sends back when
     * they accept a message request.  The sender's side uses this flag to
     * automatically save the receiver as a contact (completing the two-sided
     * contact creation) and to suppress the message from appearing in the
     * conversation thread.
     */
    @Json(name = "isAcceptance") val isAcceptance: Boolean = false
)