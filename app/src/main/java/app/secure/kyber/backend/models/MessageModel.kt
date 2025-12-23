package app.secure.kyber.backend.models

data class MessageModel(
    val id: String,
    val text: String,
    val senderId: String,
    val createdAt: String,
    val isSent: Boolean
)
