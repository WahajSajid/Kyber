package app.secure.kyber.roomdb

import androidx.lifecycle.LiveData
import app.secure.kyber.backend.models.ChatModel
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val dao: MessageDao) {

    suspend fun saveMsg(
        messageId: String,
        msg: String,
        senderOnion: String,
        timestamp: String,
        isSent: Boolean,
        type: String = "TEXT",
        uri: String? = null,
        ampsJson: String? = null,
        apiMessageId: String? = null,
        reaction: String = "",
        isRequest: Boolean = false,
        keyFingerprint: String? = null
    ) {
        dao.insert(
            MessageEntity(
                messageId = messageId,
                msg = msg,
                senderOnion = senderOnion,
                time = timestamp,
                isSent = isSent,
                type = type,
                uri = uri,
                ampsJson = ampsJson ?: "",
                apiMessageId = apiMessageId,
                reaction = reaction,
                isRequest = isRequest,
                keyFingerprint = keyFingerprint
            )
        )
    }

    suspend fun updateMsg(message: MessageEntity) {
        dao.update(message)
    }

    suspend fun deleteMsg(message: MessageEntity) {
        dao.delete(message)
    }

    suspend fun deleteAllBySender(senderOnion: String) {
        dao.deleteAllBySender(senderOnion)
    }

    suspend fun getAllOnce(): List<MessageEntity> = dao.getAll()

    fun observeAll(senderOnion: String): Flow<List<MessageEntity>> =
        dao.observeAll(senderOnion)

    /**
     * Observes only the [limit] most-recent messages (DESC from DB, caller reverses to ASC).
     * Emits on every insert/update so real-time messages still appear instantly.
     */
    fun observeRecent(senderOnion: String, limit: Int): Flow<List<MessageEntity>> =
        dao.observeRecent(senderOnion, limit)

    /**
     * One-shot load of messages older than [beforeTime], newest-first, page-limited.
     */
    suspend fun getOlderMessages(senderOnion: String, beforeTime: Long, limit: Int): List<MessageEntity> =
        dao.getOlderMessages(senderOnion, beforeTime, limit)

    fun observeAllLastMsgs(): Flow<List<ChatModel>> =
        dao.observeAllLastMsgs()

    fun observeIncomingRequests(): Flow<List<ChatModel>> =
        dao.observeIncomingRequests()

    suspend fun getMessageByMessageId(messageId: String): MessageEntity? =
        dao.getMessageByMessageId(messageId)

    suspend fun updateUploadProgress(messageId: String, state: String, progress: Int) =
        dao.updateUploadProgress(messageId, state, progress)

    suspend fun setUploadDone(messageId: String, path: String?) =
        dao.setUploadDone(messageId, "done", path)

    suspend fun setUploadFailed(messageId: String) =
        dao.updateUploadProgress(messageId, "failed", 0)

    suspend fun updateDownloadProgress(messageId: String, state: String, progress: Int) =
        dao.updateDownloadProgress(messageId, state, progress)

    suspend fun setDownloadDone(messageId: String, path: String?) =
        dao.setDownloadDone(messageId, "done", path)

    suspend fun setRemoteMediaId(messageId: String, mediaId: String) =
        dao.setRemoteMediaId(messageId, mediaId)

    suspend fun getByRemoteMediaId(mediaId: String): MessageEntity? =
        dao.getByRemoteMediaId(mediaId)

    suspend fun getByMessageId(messageId: String): MessageEntity? =
        dao.getByMessageId(messageId)

}


class GroupMessageRepository(private val dao: GroupMessageDao) {

    suspend fun saveMsg(message: GroupMessageEntity) {
        dao.insertGroupMessage(message)
    }

    suspend fun updateMsg(message: GroupMessageEntity) {
        dao.updateGroupMessage(message)
    }

    suspend fun deleteMsg(message: GroupMessageEntity) {
        dao.deleteGroupMessage(message)
    }

    suspend fun deleteAllGroupMessages(groupId: String) {
        dao.deleteByGroupId(groupId)
    }

    suspend fun getLatestMessage(groupId: String): GroupMessageEntity? {
        return dao.getLatestMessage(groupId)
    }

    suspend fun getAllGroupMessages(groupId: String, now: Long = System.currentTimeMillis()): androidx.lifecycle.LiveData<List<GroupMessageEntity>> =
        dao.getGroupMessages(groupId = groupId, now = now)

    fun observeAll(groupId: String, now: Long = System.currentTimeMillis()): Flow<MutableList<GroupMessageEntity>> =
        dao.observeAllGroupMessages(groupId = groupId, now = now)

    /**
     * Observes all group messages as a Flow (for paginated Fragment use).
     */
    fun observeFlow(groupId: String, now: Long = System.currentTimeMillis()): Flow<List<GroupMessageEntity>> =
        dao.observeAllGroupMessages(groupId = groupId, now = now)

    fun observeAllLastMsgs(): Flow<List<ChatModel>> =
        dao.observeAllLastMsgs()
}


class ContactRepository(private val dao: ContactDao) {

    suspend fun saveContact(onionAddress: String, name: String, publicKey: String? = null) {
        val existing = dao.get(onionAddress)
        dao.insert(ContactEntity(
            onionAddress = onionAddress, 
            name = name, 
            publicKey = publicKey ?: existing?.publicKey,
            keyVersion = existing?.keyVersion,
            lastKeyUpdate = if (publicKey != null) System.currentTimeMillis() else (existing?.lastKeyUpdate ?: 0L)
        ))
    }

    suspend fun getContact(onionAddress: String): ContactEntity? {
        return dao.get(onionAddress)
    }

    suspend fun getAllOnce(): List<ContactEntity> = dao.getAll()
    fun observeAll(): Flow<List<ContactEntity>> = dao.observeAll()
    fun observeContact(onionAddress: String): Flow<ContactEntity?> = dao.observeContact(onionAddress)
}


class GroupRepository(private val dao: GroupDao) {

    suspend fun saveGroup(group: GroupsEntity) {
        dao.insert(group)
    }

    suspend fun updateGroup(group: GroupsEntity) {
        dao.update(group)
    }

    suspend fun getGroupById(groupId: String): GroupsEntity? {
        return dao.getGroupById(groupId)
    }

    fun getAllOnce(): androidx.lifecycle.LiveData<List<GroupsEntity>> = dao.getAll()

    fun observeAll(): Flow<List<GroupsEntity>> = dao.observeAll()

    suspend fun getNoOfMembers(groupId: String): Int = dao.getNoOfMembers(groupId)

    suspend fun getCreationDate(groupId: String): Long = dao.getCreationDate(groupId)

    suspend fun incrementUnread(groupId: String) = dao.incrementUnread(groupId)

    suspend fun resetUnread(groupId: String) = dao.resetUnread(groupId)
}