package app.secure.kyber.Utils

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionUtils {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    // In a real production app, this key should be stored in the Android Keystore.
    // For this implementation, we'll use a derived key or a placeholder to demonstrate the flow.
    private val key = SecretKeySpec("KyberSecureMsgKeyKyberSecureMsgK".toByteArray(), "AES")
    private val iv = IvParameterSpec("KyberMsgIV123456".toByteArray())

    fun encrypt(plainText: String?): String {
        if (plainText == null) return ""
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            val encrypted = cipher.doFinal(plainText.toByteArray())
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            plainText
        }
    }

    fun decrypt(encryptedText: String?): String {
        if (encryptedText == null || encryptedText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decodedValue = Base64.decode(encryptedText, Base64.NO_WRAP)
            val decrypted = cipher.doFinal(decodedValue)
            String(decrypted)
        } catch (e: Exception) {
            encryptedText
        }
    }
}
