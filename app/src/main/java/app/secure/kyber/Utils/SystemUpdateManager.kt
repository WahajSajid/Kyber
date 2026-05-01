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

    /**
     * Broadcasts the updated public key to ALL accepted contacts via WorkManager.
     * Works when app is open, backgrounded, or completely closed.
     * On sender side: inserts a system bubble "Your public key has been updated".
     * On receiver side: the worker delivers "[Name]'s public key has been updated".
     * Only targets accepted contacts (isContact == true) — NOT message requests.
     */
    suspend fun sendKeyUpdate(context: Context, newPublicKeyBase64: String) {
        val myOnion = Prefs.getOnionAddress(context) ?: return
        val myName = Prefs.getName(context) ?: "User"
        val db = AppDb.get(context)

        // Only accepted contacts — not pending message requests
        val contacts = db.contactDao().getAll()
        if (contacts.isEmpty()) {
            Prefs.setLastBroadcastPublicKey(context, newPublicKeyBase64)
            return
        }

        val senderText = "Your public key has been updated"

        for (contact in contacts) {
            try {
                val messageId = java.util.UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis().toString()
                val receiverText = "$myName's public key has been updated"

                // Insert local system bubble for the sender (shown in their chat with this contact)
                db.messageDao().insert(
                    app.secure.kyber.roomdb.MessageEntity(
                        messageId = messageId,
                        msg = MessageEncryptionManager.encryptLocal(context, senderText).encryptedBlob,
                        senderOnion = contact.onionAddress,
                        time = timestamp,
                        isSent = true,
                        type = "KEY_UPDATE",
                        uploadState = "done"
                    )
                )

                // Enqueue WorkManager job — runs even when app is killed
                val request = TextUploadWorker.buildRequest(
                    messageId = messageId,
                    text = receiverText,
                    contactOnion = contact.onionAddress,
                    senderOnion = myOnion,
                    senderName = myName,
                    timestamp = timestamp,
                    isRequest = false,
                    disappearTtl = 0L,
                    newPublicKey = newPublicKeyBase64
                )

                WorkManager.getInstance(context).enqueueUniqueWork(
                    "key_update_${contact.onionAddress}_$messageId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (e: Exception) {
                android.util.Log.w("SystemUpdateManager", "Failed to send KEY_UPDATE to ${contact.onionAddress}: ${e.message}")
            }
        }

        // Record the key that was just broadcast so we don't re-broadcast the same key
        Prefs.setLastBroadcastPublicKey(context, newPublicKeyBase64)
    }

}
