package app.secure.kyber.backend.models

import androidx.room.Ignore

data class ChatModel(
    var id: String?=null,
    var name: String?=null,
    var lastMessage: String?=null,
    var time: String?=null,
    @Ignore
    var unreadCount: Int?=null,
    @Ignore
    var avatarRes: String?=null
)