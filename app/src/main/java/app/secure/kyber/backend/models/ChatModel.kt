package app.secure.kyber.backend.models

import androidx.room.Ignore

data class ChatModel(
    var onionAddress: String? = null,
    var name: String? = null,
    var lastMessage: String? = null,
    var time: String? = null,
    var reaction: String? = null, // <--- ADDED: To track the reaction
    var type: String? = null,     // <--- ADDED: To track if it's an image/video/audio
    @Ignore
    var unreadCount: Int? = null,
    @Ignore
    var avatarRes: String? = null
)