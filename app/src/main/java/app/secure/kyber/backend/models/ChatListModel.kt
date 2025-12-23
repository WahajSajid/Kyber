package app.secure.kyber.backend.models

import app.secure.kyber.backend.models.ChatModel


data class ChatListModel(
    val chats: List<ChatModel>?=null
)