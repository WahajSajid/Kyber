package app.secure.kyber.dataClasses

data class Group(
    val groupId: String = "",
    val groupName: String = "",
    val groupImage: String = "",
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val members: Map<String, Map<String, String>> = emptyMap(),
    val lastMessage: String = "",
    val lastMessageTime: String = "0",
    val lastMessageSenderId: String = "",
    val newMessagesCount: Int = 0,
    val anonymous: Boolean = false,
    val anonymousAliases: Map<String, String> = emptyMap(),
    val groupExpiresAt: Long = 0L,
)
