package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_keys")
data class KeyEntity(
    @PrimaryKey val keyId: String, // Fingerprint or UUID
    val publicKey: String,         // Base64 encoded
    val privateKeyEncrypted: String, // Encrypted private key material
    val createdAt: Long,
    val activatedAt: Long,
    val expiresAt: Long,
    val status: String             // ACTIVE, OLD_RETENTION, EXPIRED
)
