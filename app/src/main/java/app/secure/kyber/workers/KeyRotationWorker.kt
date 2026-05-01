package app.secure.kyber.workers

import android.content.Context
import android.util.Base64.encodeToString
import android.util.Log
import android.widget.Toast
import androidx.hilt.work.HiltWorker
import androidx.work.*
import app.secure.kyber.Utils.SecureKeyManager
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.KeyEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.security.MessageDigest
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

            // --- DUPLICATE ROTATION GUARD ---
            // Skip rotation if the active key is still within the user-selected timer window
            // (minus a 30-minute grace period to allow the WorkManager trigger to fire naturally).
            if (!force && activeKey != null) {
                val timerMs = Prefs.getEncryptionTimerMs(context)
                if (timerMs > 0L) {
                    val ageMs = now - activeKey.activatedAt
                    val graceMs = TimeUnit.MINUTES.toMillis(30)
                    if (ageMs < timerMs - graceMs) {
                        Log.d(TAG, "Skipping rotation — active key is only ${TimeUnit.MILLISECONDS.toHours(ageMs)}h old (timer: ${TimeUnit.MILLISECONDS.toHours(timerMs)}h)")
                        return
                    }
                }
            }

            // 1. Generate new key pair
            val keyInfo = SecureKeyManager.generateNewKeyPair()
            val newKeyId = UUID.randomUUID().toString()


            // 2. Push new public key to backend FIRST to guarantee upload
            val onionAddress = Prefs.getOnionAddress(context) ?: ""
            if (onionAddress.isEmpty()) {
                Log.e("### Key Rotation Failed ###", "No onion address found. Cannot update public key.")
                throw Exception("Onion address not available")
            }
            try {
                val response = repository.updatePublicKey(onionAddress, keyInfo.publicKeyBase64)
                if (response.isSuccessful) {
                    Log.d("### Key Rotation Success ###", "Successfully pushed new public key to backend.")
                    // Immediately propagate new key to ALL accepted contacts via WorkManager
                    // (works even when app is backgrounded or completely closed)
                    app.secure.kyber.Utils.SystemUpdateManager.sendKeyUpdate(context, keyInfo.publicKeyBase64)
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    Log.e("### Key Rotation Failed ###", "Failed to push new public key to backend. Status: ${response.code()}, Error: $errorBody")
                }
            } catch (e: Exception) {
                Log.e("### Key Rotation Failed ###", "Failed to push new public key to backend. Aborting rotation.", e)
                throw e // Surface to WorkManager for retry
            }

            // 3. Archive all current active keys — reset activatedAt to rotation time so retention window starts now
            keyDao.archiveAllActiveKeys(now)

            // 4. Save new active key
            // expiresAt tracks when this key is due for rotation (informational; WorkManager drives actual rotation)
            val rotationIntervalMs = Prefs.getEncryptionTimerMs(context)
            val newKey = KeyEntity(
                keyId = newKeyId,
                publicKey = keyInfo.publicKeyBase64,
                privateKeyEncrypted = keyInfo.privateKeyEncrypted,
                createdAt = now,
                activatedAt = now,
                expiresAt = now + if (rotationIntervalMs > 0L) rotationIntervalMs else TimeUnit.DAYS.toMillis(1),
                status = "ACTIVE"
            )
            keyDao.insert(newKey)
            
            // Save to Prefs for quick access
            Prefs.setPublicKey(context, keyInfo.publicKeyBase64)

            // 5. Cleanup expired OLD_RETENTION keys and their associated messages.
            // The retention window mirrors the user-selected rotation interval so contacts
            // have a full cycle to receive messages encrypted with the previous key.
            val retentionWindowMs = if (rotationIntervalMs > 0L) rotationIntervalMs else TimeUnit.DAYS.toMillis(1)
            val retentionKeys = keyDao.getRetentionKeys()
            for (oldKey in retentionKeys) {
                if (oldKey.activatedAt + retentionWindowMs < now) {
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
            val timerMs = Prefs.getEncryptionTimerMs(context)

            if (timerMs <= 0L) {
                // "Never" selected — cancel any scheduled automatic rotation
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Encryption timer set to Never — automatic key rotation disabled")
                return
            }

            // Enforce WorkManager's 15-minute minimum interval floor
            val intervalMinutes = TimeUnit.MILLISECONDS.toMinutes(timerMs).coerceAtLeast(15L)
            val request = PeriodicWorkRequestBuilder<KeyRotationWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request
            )
            Log.d(TAG, "Key rotation scheduled every ${TimeUnit.MINUTES.toHours(intervalMinutes)}h (${intervalMinutes}min)")
        }
    }
}
