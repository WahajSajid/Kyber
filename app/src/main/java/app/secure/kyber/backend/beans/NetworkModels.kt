package app.secure.kyber.backend.beans

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Empty request for POST endpoints that require {}
@JsonClass(generateAdapter = true)
class EmptyRequest

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
    @Json(name = "nodeIds") val nodeIds: List<String>? = null,
    @Json(name = "entryNode") val entryNode: String,
    @Json(name = "middleNode") val middleNode: String,
    @Json(name = "exitNode") val exitNode: String,
    @Json(name = "createdAt") val createdAt: String,
    @Json(name = "expiresAt") val expiresAt: String
)

// Hidden Services
@JsonClass(generateAdapter = true)
data class HiddenServiceMessageResponse(
    @Json(name = "messages") val messages: List<ApiMessage> = emptyList(),
    @Json(name = "count") val count: Int = 0
)

@JsonClass(generateAdapter = true)
data class ApiMessage(
    @Json(name = "id") val id: String,
    @Json(name = "senderOnion") val senderOnion: String?,
    @Json(name = "payload") val payload: String,
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "ttl") val ttl: Long? = null
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

@JsonClass(generateAdapter = true)
data class UpdatePublicKeyRequest(
    @Json(name = "publicKey") val publicKey: String
)

@JsonClass(generateAdapter = true)
data class UpdatePublicKeyResponse(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "message") val message: String? = ""
)

// Admin / License
@JsonClass(generateAdapter = true)
data class LicenseKeyRequest(
    @Json(name = "licenseKey") val licenseKey: String
)

@JsonClass(generateAdapter = true)
data class LicenseActionResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String?,
    @Json(name = "keyHash") val keyHash: String? = null,
    @Json(name = "createdAt") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class LicenseValidateResponse(
    @Json(name = "valid") val valid: Boolean,
    @Json(name = "isActive") val isActive: Boolean? = null,
    @Json(name = "isUsed") val isUsed: Boolean? = null,
    @Json(name = "message") val message: String?
)

@JsonClass(generateAdapter = true)
data class LicenseListResponse(
    @Json(name = "licenseKeys") val licenseKeys: List<LicenseKeyInfo>,
    @Json(name = "count") val count: Int
)

@JsonClass(generateAdapter = true)
data class LicenseKeyInfo(
    @Json(name = "id") val id: String,
    @Json(name = "keyHash") val keyHash: String,
    @Json(name = "isActive") val isActive: Boolean,
    @Json(name = "isUsed") val isUsed: Boolean,
    @Json(name = "usedAt") val usedAt: String? = null,
    @Json(name = "usedByUserId") val usedByUserId: String? = null,
    @Json(name = "createdAt") val createdAt: String
)
