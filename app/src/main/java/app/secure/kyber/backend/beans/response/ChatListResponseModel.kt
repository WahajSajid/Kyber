package app.secure.kyber.backend.beans.response

import app.secure.kyber.backend.models.ChatListModel
import app.secure.kyber.backend.models.ChatModel
import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class ChatListResponse(
    val id: String?=null,
    val name: String?=null,
    val lastMessage: String?=null,
    val time: String?=null,
    val unreadCount: Int?=null,
    val avatarRes: String?=null
) : Parcelable

@Parcelize
data class ChatListResponseModel(
    val chats: List<ChatListResponse>?=null
) : Parcelable

fun ChatListResponseModel.toChatModel(): ChatListModel {
    return ChatListModel(
        chats = chats?.map { it.toChatModel() }!!
    )
}

fun ChatListResponse.toChatModel(): ChatModel {
    return ChatModel(
        id = id,
        name = name,
        lastMessage = lastMessage,
        time = time,
        unreadCount = unreadCount,
        avatarRes = avatarRes
    )
}