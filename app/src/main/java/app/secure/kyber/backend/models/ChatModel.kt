package app.secure.kyber.backend.models

data class ChatModel(
    val id: String?=null,
    val name: String?=null,
    val lastMessage: String?=null,
    val time: String?=null,
    val unreadCount: Int?=null,
    val avatarRes: String?=null
)