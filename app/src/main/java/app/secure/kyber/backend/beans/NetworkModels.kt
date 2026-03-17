package app.secure.kyber.backend.beans

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Auth
@JsonClass(generateAdapter = true)
data class RegisterRequest(
    @Json(name = "licenseKey") val licenseKey: String,
    @Json(name = "publicKey") val publicKey: String,
    @Json(name = "usernameHash") val usernameHash: String? = null,
    @Json(name = "usernameEncrypted") val usernameEncrypted: String? = null
)

@JsonClass(generateAdapter = true)
data class RegisterResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "userId") val userId: String?,
    @Json(name = "onionAddress") val onionAddress: String?,
    @Json(name = "sessionToken") val sessionToken: String?,
    @Json(name = "expiresAt") val expiresAt: String?,
    @Json(name = "error") val error: String?
)

// Circuit
@JsonClass(generateAdapter = true)
data class CircuitResponse(
    @Json(name = "circuitId") val circuitId: String,
    @Json(name = "entryNode") val entryNode: String,
    @Json(name = "middleNode") val middleNode: String,
    @Json(name = "exitNode") val exitNode: String,
    @Json(name = "createdAt") val createdAt: String,
    @Json(name = "expiresAt") val expiresAt: String
)

// Messaging
@JsonClass(generateAdapter = true)
data class SendMessageRequest(
    @Json(name = "receiverOnion") val receiverOnion: String,
    @Json(name = "payload") val payload: String,
    @Json(name = "circuitId") val circuitId: String
)

@JsonClass(generateAdapter = true)
data class SendMessageResponse(
    @Json(name = "messageId") val messageId: String,
    @Json(name = "status") val status: String,
    @Json(name = "circuitId") val circuitId: String,
    @Json(name = "sentAt") val sentAt: String
)

// Discovery
@JsonClass(generateAdapter = true)
data class DiscoverySearchResponse(
    @Json(name = "found") val found: Boolean,
    @Json(name = "onionAddress") val onionAddress: String?,
    @Json(name = "publicKey") val publicKey: String?
)

@JsonClass(generateAdapter = true)
data class PublicKeyResponse(
    @Json(name = "onionAddress") val onionAddress: String,
    @Json(name = "publicKey") val publicKey: String
)

// Admin / License
@JsonClass(generateAdapter = true)
data class LicenseValidateResponse(
    @Json(name = "valid") val valid: Boolean,
    @Json(name = "isActive") val isActive: Boolean? = null,
    @Json(name = "isUsed") val isUsed: Boolean? = null,
    @Json(name = "message") val message: String?
)
