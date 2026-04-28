package app.secure.kyber.backend.models

import androidx.room.Ignore

data class ChatModel(
    var onionAddress: String? = null,
    var name: String? = null,
    var lastMessage: String? = null,
    var time: String? = null,
    var reaction: String? = null,
    var type: String? = null,
    var keyFingerprint: String? = null,
    var iv: String? = null,
    @Ignore
    var unreadCount: Int? = null,
    @Ignore
    var avatarRes: String? = null,
    var contactId:String? = null
)