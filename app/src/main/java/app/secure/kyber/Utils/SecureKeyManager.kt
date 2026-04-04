package app.secure.kyber.Utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureKeyManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "kyber_master_key"
    private const val AES_GCM = "AES/GCM/NoPadding"

    init {
        initMasterKey()
    }

    private fun initMasterKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        return keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
    }

    data class GeneratedKeyInfo(
        val publicKeyBase64: String,
        val privateKeyEncrypted: String
    )

    fun generateNewKeyPair(): GeneratedKeyInfo {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(256)
        val keyPair = keyPairGenerator.generateKeyPair()

        val publicKeyBase64 = encodePublicKey(keyPair.public)
        
        // Encrypt private key with master key
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val iv = cipher.iv
        val encryptedPrivateKey = cipher.doFinal(keyPair.private.encoded)
        
        // Combine IV and encrypted data
        val combined = iv + encryptedPrivateKey
        val privateKeyEncryptedBase64 = Base64.encodeToString(combined, Base64.NO_WRAP)

        return GeneratedKeyInfo(publicKeyBase64, privateKeyEncryptedBase64)
    }

    fun decryptPrivateKey(encryptedBase64: String): PrivateKey {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = combined.sliceArray(0 until 12)
        val encryptedData = combined.sliceArray(12 until combined.size)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), GCMParameterSpec(128, iv))
        val privateKeyEncoded = cipher.doFinal(encryptedData)

        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyEncoded))
    }

    fun computeSharedSecret(privateKey: PrivateKey, otherPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(otherPublicKey, true)
        return keyAgreement.generateSecret()
    }

    fun encodePublicKey(publicKey: PublicKey): String {
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun decodePublicKey(publicKeyBase64: String): PublicKey {
        val keyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
    }

    fun getFingerprint(publicKey: PublicKey): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(publicKey.encoded)
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
    
    fun getFingerprintFromBase64(publicKeyBase64: String): String {
        return getFingerprint(decodePublicKey(publicKeyBase64))
    }
}
