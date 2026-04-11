package app.secure.kyber.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import com.google.android.gms.tasks.Tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MessageCleanupWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "MessageCleanupWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDb.get(context)
        val messageDao = db.messageDao()
        val groupsDao = db.groupsMessagesDao()
        val groupDao = db.groupsDao()
        val myOnion = Prefs.getOnionAddress(context)

        val currentTime = System.currentTimeMillis()

        try {
            // Cleanup Expired Retention Keys independent of Rotation cycles
            val keyDao = db.keyDao()
            val rotationIntervalMs = app.secure.kyber.backend.common.Prefs.getEncryptionTimerMs(context)
            val retentionWindowMs = if (rotationIntervalMs > 0L) rotationIntervalMs else java.util.concurrent.TimeUnit.DAYS.toMillis(1)
            val retentionKeys = keyDao.getRetentionKeys()
            for (oldKey in retentionKeys) {
                if (oldKey.activatedAt + retentionWindowMs < currentTime) {
                    val fingerprint = app.secure.kyber.Utils.SecureKeyManager.getFingerprintFromBase64(oldKey.publicKey)
                    // Delete all local messages that depend on this expired key
                    messageDao.deleteByFingerprint(fingerprint)
                    keyDao.updateStatus(oldKey.keyId, "EXPIRED")
                }
            }
            keyDao.deleteExpiredKeys()

            // Cleanup Private Messages
            val expiredPrivateMessages = messageDao.getExpiredMessages(currentTime)
            expiredPrivateMessages.forEach { msg ->
                // Delete associated media files if localFilePath is present
                msg.localFilePath?.let { path ->
                    try {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete media for private message ${msg.messageId}")
                    }
                }
                // Delete thumbnail file if thumbnailPath is present
                msg.thumbnailPath?.let { path ->
                    try {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete thumbnail for private message ${msg.messageId}")
                    }
                }
            }
            if (expiredPrivateMessages.isNotEmpty()) {
                messageDao.deleteExpiredMessages(currentTime)
                Log.d(TAG, "Deleted ${expiredPrivateMessages.size} expired private messages")
            }

            // Cleanup Group Messages
            val expiredGroupMessages = groupsDao.getExpiredGroupMessages(currentTime)
            expiredGroupMessages.forEach { msg ->
                msg.localFilePath?.let { path ->
                    try {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete media for group message ${msg.messageId}")
                    }
                }
                // Delete thumbnail file if thumbnailPath is present
                msg.thumbnailPath?.let { path ->
                    try {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete thumbnail for group message ${msg.messageId}")
                    }
                }
            }
            if (expiredGroupMessages.isNotEmpty()) {
                groupsDao.deleteExpiredGroupMessages(currentTime)
                Log.d(TAG, "Deleted ${expiredGroupMessages.size} expired group messages")
            }

            // Cleanup Expired Groups globally (Burn Time)
            val allGroups = groupDao.getAllGroupsList()
            for (group in allGroups) {
                if (group.groupExpiresAt > 0L && currentTime >= group.groupExpiresAt) {
                    if (myOnion == group.createdBy) {
                        try {
                            // Extract member list from Firebase because Room doesn't hold standard member IDs here.
                            // But deleteGroupEverywhere fetches members if needed?
                            // Let's just use GroupManager().deleteGroupEverywhere()
                            val database = com.google.firebase.database.FirebaseDatabase.getInstance("https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/")
                            val groupSnapshot = await(database.getReference("groups").child(group.groupId).get())
                            val allMemberIds = groupSnapshot.child("members").children.mapNotNull { it.child("id").getValue(String::class.java) }
                            if (allMemberIds.isNotEmpty()) {
                                app.secure.kyber.GroupCreationBackend.GroupManager().deleteGroupEverywhere(group.groupId, allMemberIds)
                            }
                        } catch (e: Exception) { Log.e(TAG, "Failed deleting expired group ${group.groupId}", e) }
                    }
                    groupDao.deleteById(group.groupId)
                    groupsDao.deleteByGroupId(group.groupId)
                    Log.d(TAG, "Deleted expired group ${group.groupId}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up messages", e)
            Result.retry()
        }
    }
}
