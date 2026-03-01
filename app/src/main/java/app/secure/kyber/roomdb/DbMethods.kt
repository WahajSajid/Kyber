package app.secure.kyber.roomdb

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.secure.kyber.backend.models.ChatModel
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val dao: MessageDao) {

    suspend fun saveMsg(msg: String, senderId: String, timestamp: String, isSent: Boolean, type: String = "TEXT", uri: String? = null, ampsJson: String? = null) {

        dao.insert(
            MessageEntity(
                msg = msg,
                senderId = senderId,
                time = timestamp,
                isSent = isSent,
                type = type,
                uri = uri,
                ampsJson = ampsJson ?: ""
            )
        )
    }

    suspend fun updateMsg(message: MessageEntity) {
        dao.update(message)
    }

    suspend fun deleteMsg(message: MessageEntity) {
        dao.delete(message)
    }

    suspend fun getAllOnce(): List<MessageEntity> = dao.getAll()
    fun observeAll(senderId: String): kotlinx.coroutines.flow.Flow<List<MessageEntity>> =
        dao.observeAll(senderId)

    fun observeAllLastMsgs(): kotlinx.coroutines.flow.Flow<List<ChatModel>> =
        dao.observeAllLastMsgs()
}


class GroupMessageRepository(private val dao: GroupMessageDao) {

    suspend fun saveMsg(
        message: GroupMessageEntity
    ) {

        dao.insertGroupMessage(
            message
        )
    }

    suspend fun updateMsg(message: GroupMessageEntity) {
        dao.updateGroupMessage(message)
    }

    suspend fun deleteMsg(message: GroupMessageEntity) {
        dao.deleteGroupMessage(message)
    }

    suspend fun getLatestMessage(groupId: String): GroupMessageEntity? {
        return dao.getLatestMessage(groupId)
    }

    suspend fun getAllGroupMessages(groupId: String): LiveData<List<GroupMessageEntity>> =
        dao.getGroupMessages(groupId = groupId)

    fun observeAll(groupId: String): kotlinx.coroutines.flow.Flow<MutableList<GroupMessageEntity>> =
        dao.observeAllGroupMessages(groupId = groupId)

    fun observeAllLastMsgs(): kotlinx.coroutines.flow.Flow<List<ChatModel>> =
        dao.observeAllLastMsgs()
}


class ContactRepository(private val dao: ContactDao) {

    suspend fun saveContact(id: String, name: String) {

        dao.insert(
            ContactEntity(
                id = id,
                name = name
            )
        )
    }

    suspend fun getAllOnce(): List<ContactEntity> = dao.getAll()
    fun observeAll(): Flow<List<ContactEntity>> = dao.observeAll()
}


class GroupRepository(private val dao: GroupDao) {

    suspend fun saveGroup(group: GroupsEntity) {

        dao.insert(
            group
        )
    }

    suspend fun updateGroup(group: GroupsEntity) {
        dao.update(group)
    }

    suspend fun getGroupById(groupId: String): GroupsEntity? {
        return dao.getGroupById(groupId)
    }

    fun getAllOnce(): LiveData<List<GroupsEntity>> = dao.getAll()

    fun observeAll(): Flow<List<GroupsEntity>> =
        dao.observeAll()

    suspend fun getNoOfMembers(groupId:String): Int = dao.getNoOfMembers(groupId)

    suspend fun getCreationDate(groupId:String): Long = dao.getCreationDate(groupId)


}
