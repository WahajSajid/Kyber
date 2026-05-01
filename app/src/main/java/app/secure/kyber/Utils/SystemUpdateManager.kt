package app.secure.kyber.Utils

import android.content.Context
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.workers.TextUploadWorker
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import java.util.UUID

object SystemUpdateManager {

    suspend fun sendDisappearingUpdate(context: Context, contactOnion: String, newLabel: String) {
        val myOnion = Prefs.getOnionAddress(context) ?: return
        val myName = Prefs.getName(context) ?: "User"
        val timestamp = System.currentTimeMillis().toString()
        val messageId = UUID.randomUUID().toString()

        // 1. Text content
        val senderText = "You have set disappearing messages to $newLabel"
        val receiverText = "$myName has set disappearing messages to $newLabel"

        // 2. Insert locally for sender (as a sent message)
        val db = AppDb.get(context)
        db.messageDao().insert(
            MessageEntity(
                messageId = messageId,
                msg = MessageEncryptionManager.encryptLocal(context, senderText).encryptedBlob,
                senderOnion = contactOnion,
                time = timestamp,
                isSent = true,
                type = "DISAPPEAR_SYSTEM",
                uploadState = "done" // We mark it done locally
            )
        )

        // 3. Send to receiver (the worker will pick up DISAPPEAR_SYSTEM from the DB entry)
        val request = TextUploadWorker.buildRequest(
            messageId = messageId,
            text = receiverText,
            contactOnion = contactOnion,
            senderOnion = myOnion,
            senderName = myName,
            timestamp = timestamp,
            isRequest = false,
            disappearTtl = 0L // System messages don't disappear
        )

        WorkManager.getInstance(context).enqueueUniqueWork(
            "disappear_update_$messageId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun broadcastGlobalDisappearingUpdate(context: Context, newLabel: String) {
        val db = AppDb.get(context)
        val contacts = db.contactDao().getAll()
        for (contact in contacts) {
            // Only send if no chat-specific override is set for this contact
            if (Prefs.getChatSpecificDisappearingStatus(context, contact.onionAddress) == null) {
                sendDisappearingUpdate(context, contact.onionAddress, newLabel)
            }
        }
    }

    suspend fun sendGroupDisappearingUpdate(
        context: Context,
        groupId: String,
        newLabel: String,
        groupManager: app.secure.kyber.GroupCreationBackend.GroupManager,
        vms: app.secure.kyber.roomdb.roomViewModel.GroupMessagesViewModel
    ) {
        val myOnion = Prefs.getOnionAddress(context) ?: return
        val myName = Prefs.getName(context) ?: "User"

        val text = "$myName has set disappearing messages to $newLabel"

        groupManager.sendMessage(
            groupId = groupId,
            senderId = myOnion,
            senderName = myName,
            messageText = text,
            groupMessagesViewModel = vms,
            type = "DISAPPEAR_SYSTEM",
            context = context
        )
    }

}
