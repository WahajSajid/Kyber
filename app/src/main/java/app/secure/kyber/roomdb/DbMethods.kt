package app.secure.kyber.roomdb

import app.secure.kyber.backend.models.ChatModel

class MessageRepository(private val dao: MessageDao) {

    suspend fun saveMsg(msg: String, senderId: String, timestamp: String, isSent: Boolean) {

        dao.insert(
            MessageEntity(
                msg = msg,
                senderId = senderId,
                time = timestamp,
                isSent = isSent// string as requested
            )
        )
    }

    suspend fun getAllOnce(): List<MessageEntity> = dao.getAll()
    fun observeAll(senderId:String): kotlinx.coroutines.flow.Flow<List<MessageEntity>> = dao.observeAll(senderId)

    fun observeAllLastMsgs(): kotlinx.coroutines.flow.Flow<List<ChatModel>> = dao.observeAllLastMsgs()
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
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ContactEntity>> = dao.observeAll()
}
