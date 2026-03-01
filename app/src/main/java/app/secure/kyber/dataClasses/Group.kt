package app.secure.kyber.dataClasses

data class Group(
    val groupId: String = "",
    val groupName: String = "",
    val groupImage: String = "",
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val members: Map<String, Map<String, String>> = emptyMap(), // userId to true mapping
    val lastMessage: String = "",
    val lastMessageTime:  Long = 0,
    val lastMessageSenderId: String = "",
    val newMessagesCount: Int = 0,
)
