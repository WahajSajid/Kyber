package app.secure.kyber.Utils

import android.content.Context
import android.util.Base64
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.KeyEntity
import app.secure.kyber.roomdb.MessageEntity
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * High-level manager for all encryption needs in the app.
 * Uses the asymmetric key pair lifecycle for both remote messaging and local storage.
 */
object MessageEncryptionManager {
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    // --- REMOTE MESSAGING ---

    /**
     * Encrypts a message for a recipient using ECDH shared secret.
     */
    suspend fun encryptMessage(
        context: Context,
        recipientPublicKeyBase64: String,
        plainText: String
    ): RemoteEncryptionResult {
        val db = AppDb.get(context)
        val activeKey = db.keyDao().getActiveKey() ?: throw IllegalStateException("No active key found")
        
        val recipientPublicKey = SecureKeyManager.decodePublicKey(recipientPublicKeyBase64)
        val myPrivateKey = SecureKeyManager.decryptPrivateKey(activeKey.privateKeyEncrypted)
        val sharedSecret = SecureKeyManager.computeSharedSecret(myPrivateKey, recipientPublicKey)
        
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        return RemoteEncryptionResult(
            encryptedPayload = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            senderKeyFingerprint = SecureKeyManager.getFingerprintFromBase64(activeKey.publicKey),
            recipientKeyFingerprint = SecureKeyManager.getFingerprintFromBase64(recipientPublicKeyBase64),
            senderPublicKeyBase64 = activeKey.publicKey
        )
    }

    /**
     * Decrypts an incoming message from a sender.
     * @param myKeyFingerprint The fingerprint of MY key that the sender used.
     */
    suspend fun decryptMessage(
        context: Context,
        senderPublicKeyBase64: String,
        myKeyFingerprint: String,
        encryptedPayload: String,
        ivBase64: String
    ): String {
        val decryptedBytes = decryptMessageRaw(
            context,
            senderPublicKeyBase64,
            myKeyFingerprint,
            Base64.decode(encryptedPayload, Base64.NO_WRAP),
            ivBase64
        )
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * Decrypts raw encrypted bytes from a sender.
     */
    suspend fun decryptMessageRaw(
        context: Context,
        senderPublicKeyBase64: String,
        myKeyFingerprint: String,
        encryptedData: ByteArray,
        ivBase64: String
    ): ByteArray {
        val db = AppDb.get(context)
        
        val myKey = findLocalKeyByFingerprint(db, myKeyFingerprint)
            ?: throw IllegalStateException("No matching local key found for fingerprint: $myKeyFingerprint")
        
        val senderPublicKey = SecureKeyManager.decodePublicKey(senderPublicKeyBase64)
        val myPrivateKey = SecureKeyManager.decryptPrivateKey(myKey.privateKeyEncrypted)
        val sharedSecret = SecureKeyManager.computeSharedSecret(myPrivateKey, senderPublicKey)
        
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        
        val cipher = Cipher.getInstance(AES_GCM)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        
        return cipher.doFinal(encryptedData)
    }

    // --- LOCAL STORAGE ---

    /**
     * Encrypts data for local storage using a specific local key pair (usually the active one).
     */
    suspend fun encryptLocal(context: Context, plainText: String?, keyFingerprint: String? = null): LocalEncryptionResult {
        if (plainText.isNullOrEmpty()) return LocalEncryptionResult("", "", "")
        
        val db = AppDb.get(context)
        val keyToUse = if (keyFingerprint != null) {
            findLocalKeyByFingerprint(db, keyFingerprint) ?: db.keyDao().getActiveKey()
        } else {
            db.keyDao().getActiveKey()
        } ?: throw IllegalStateException("No valid key found for local encryption")
        
        val myPrivateKey = SecureKeyManager.decryptPrivateKey(keyToUse.privateKeyEncrypted)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(myPrivateKey.encoded)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val actualFingerprint = SecureKeyManager.getFingerprintFromBase64(keyToUse.publicKey)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        
        // Format for backward compatibility or simple storage
        val combinedBlob = "$actualFingerprint:$ivBase64:$dataBase64"
        
        return LocalEncryptionResult(
            encryptedBlob = combinedBlob,
            fingerprint = actualFingerprint,
            iv = ivBase64
        )
    }

    /**
     * Decrypts locally stored data.
     */
    suspend fun decryptLocal(context: Context, encryptedBlob: String?): String {
        if (encryptedBlob.isNullOrEmpty()) return ""
        val parts = encryptedBlob.split(":")
        if (parts.size != 3) return encryptedBlob // Fallback
        
        val fingerprint = parts[0]
        val iv = try { Base64.decode(parts[1], Base64.NO_WRAP) } catch(e:Exception) { return encryptedBlob }
        val data = try { Base64.decode(parts[2], Base64.NO_WRAP) } catch(e:Exception) { return encryptedBlob }
        
        val db = AppDb.get(context)
        val key = findLocalKeyByFingerprint(db, fingerprint) ?: return "[Expired/Missing Key]"
        
        val myPrivateKey = SecureKeyManager.decryptPrivateKey(key.privateKeyEncrypted)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(myPrivateKey.encoded)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        val decryptedBytes = cipher.doFinal(data)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * Unified decryption method that tries local then remote if necessary.
     */
    suspend fun decryptSmart(
        context: Context,
        encryptedText: String,
        senderOnion: String,
        keyFingerprint: String?,
        iv: String?
    ): String {
        if (encryptedText.isBlank()) return ""

        // 1. Try local decryption first (it handles the check itself)
        val localDecrypted = decryptLocal(context, encryptedText)
        if (localDecrypted != encryptedText) {
            return localDecrypted
        }

        // 2. If it wasn't a local blob format, try remote if metadata is present
        if (!keyFingerprint.isNullOrBlank() && !iv.isNullOrBlank()) {
            try {
                val db = AppDb.get(context)
                val contact = db.contactDao().get(senderOnion)
                var otherPublicKey = contact?.publicKey ?: context.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                    .getString("pending_key_$senderOnion", null)

                if (otherPublicKey != null) {
                    return decryptMessage(context, otherPublicKey, keyFingerprint, encryptedText, iv)
                }
            } catch (e: Exception) {
                return "[Decryption Failed]"
            }
        }

        return encryptedText
    }

    /**
     * Raw version of decryptSmart for binary data.
     */
    suspend fun decryptSmartRaw(
        context: Context,
        encryptedData: ByteArray,
        senderOnion: String,
        keyFingerprint: String?,
        iv: String?
    ): ByteArray {
        if (encryptedData.isEmpty()) return byteArrayOf()

        // Remote metadata present?
        if (!keyFingerprint.isNullOrBlank() && !iv.isNullOrBlank()) {
            try {
                val db = AppDb.get(context)
                val contact = db.contactDao().get(senderOnion)
                var otherPublicKey = contact?.publicKey ?: context.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                    .getString("pending_key_$senderOnion", null)

                if (otherPublicKey != null) {
                    return decryptMessageRaw(context, otherPublicKey, keyFingerprint, encryptedData, iv)
                }
            } catch (e: Exception) {
                android.util.Log.e("MessageEncryption", "decryptSmartRaw remote failed", e)
            }
        }

        return encryptedData // Fallback if no metadata or decryption failed
    }

    private suspend fun findLocalKeyByFingerprint(db: AppDb, fingerprint: String): KeyEntity? {
        val allKeys = db.keyDao().getAllKeys()
        return allKeys.find { SecureKeyManager.getFingerprintFromBase64(it.publicKey) == fingerprint }
    }

    data class RemoteEncryptionResult(
        val encryptedPayload: String,
        val iv: String,
        val senderKeyFingerprint: String,
        val recipientKeyFingerprint: String,
        val senderPublicKeyBase64: String
    )

    data class LocalEncryptionResult(
        val encryptedBlob: String,
        val fingerprint: String,
        val iv: String
    )
}
