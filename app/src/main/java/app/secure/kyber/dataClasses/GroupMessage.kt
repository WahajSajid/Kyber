package app.secure.kyber.dataClasses

data class GroupMessage(
    val messageId: String = "",
    val groupId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val msg: String = "",
    val time: String = System.currentTimeMillis().toString(),
    val type: String = "TEXT", // TEXT, IMAGE, VIDEO
    val uri: String? = null,
    val isSent: Boolean = false
)
