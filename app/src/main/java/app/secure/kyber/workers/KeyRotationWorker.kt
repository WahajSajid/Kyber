package app.secure.kyber.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import app.secure.kyber.Utils.SecureKeyManager
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.KeyEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import java.util.concurrent.TimeUnit

@HiltWorker
class KeyRotationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: KyberRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            rotateKeys(context, repository)
            Result.success()
        } catch (e: Exception) {
            Log.e("KeyRotationWorker", "Error rotating keys", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "KeyRotationWorker"
        private const val WORK_NAME = "key_rotation_work"

        suspend fun rotateKeys(context: Context, repository: KyberRepository, force: Boolean = false) {
            val db = AppDb.get(context)
            val keyDao = db.keyDao()
            val messageDao = db.messageDao()
            val contactDao = db.contactDao()

            val activeKey = keyDao.getActiveKey()
            val now = System.currentTimeMillis()

            // --- DUPLICATE ROTATION GUARD (Fix 1) ---
            // If an active key exists and was activated less than 23 hours ago,
            // skip rotation to prevent double-rotation race conditions between
            // manual and auto WorkManager triggers.
            if (!force && activeKey != null) {
                val ageMs = now - activeKey.activatedAt
                if (ageMs < TimeUnit.HOURS.toMillis(23)) {
                    Log.d(TAG, "Skipping rotation — active key is only ${TimeUnit.MILLISECONDS.toHours(ageMs)}h old")
                    return
                }
            }

            // 1. Generate new key pair
            val keyInfo = SecureKeyManager.generateNewKeyPair()
            val newKeyId = UUID.randomUUID().toString()

            // 2. Archive active key if it exists
            activeKey?.let {
                keyDao.updateStatus(it.keyId, "OLD_RETENTION")
            }

            // 3. Save new active key
            val newKey = KeyEntity(
                keyId = newKeyId,
                publicKey = keyInfo.publicKeyBase64,
                privateKeyEncrypted = keyInfo.privateKeyEncrypted,
                createdAt = now,
                activatedAt = now,
                expiresAt = now + TimeUnit.DAYS.toMillis(1),
                status = "ACTIVE"
            )
            keyDao.insert(newKey)
            
            // Save to Prefs for quick access
            Prefs.setPublicKey(context, keyInfo.publicKeyBase64)

            // 4. Push new public key to backend
            val licenseKey = Prefs.getLicense(context) ?: ""
            try {
                 repository.register(licenseKey, keyInfo.publicKeyBase64)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push new public key to backend", e)
            }

            // 5. Proactively refresh all saved contacts' public keys (Fix 2)
            // This ensures that even if a contact rotated their key without messaging us,
            // we fetch and store their latest key from the backend after our own rotation.
            try {
                val allContacts = contactDao.getAll()
                for (contact in allContacts) {
                    try {
                        val resp = repository.getPublicKey(contact.onionAddress)
                        if (resp.isSuccessful) {
                            val freshKey = resp.body()?.publicKey
                            if (!freshKey.isNullOrBlank() && freshKey != contact.publicKey) {
                                contactDao.insert(
                                    contact.copy(
                                        publicKey = freshKey,
                                        lastKeyUpdate = now
                                    )
                                )
                                Log.d(TAG, "Refreshed public key for contact ${contact.onionAddress}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not refresh key for ${contact.onionAddress}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Contact key refresh loop failed: ${e.message}")
            }

            // 6. Cleanup expired keys and messages
            val retentionKeys = keyDao.getRetentionKeys()
            for (oldKey in retentionKeys) {
                // Retention window is 24 hours after rotation
                if (oldKey.activatedAt + TimeUnit.DAYS.toMillis(1) < now) {
                    val fingerprint = SecureKeyManager.getFingerprintFromBase64(oldKey.publicKey)
                    
                    // Delete all local messages that depend on this expired key
                    messageDao.deleteByFingerprint(fingerprint)
                    
                    // Mark key as EXPIRED
                    keyDao.updateStatus(oldKey.keyId, "EXPIRED")
                }
            }
            keyDao.deleteExpiredKeys()
            
            Log.d(TAG, "Key rotation completed successfully")
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeyRotationWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request
            )
        }
    }
}
