package app.secure.kyber.roomdb

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "groups")
data class GroupsEntity(
    @PrimaryKey val groupId: String,
    val groupName: String,
    val lastMessage: String,
    val newMessagesCount: Int = 0,
    val profileImageResId: Int? = null,
    val timeSpan: Long = 0,
    val chatTime: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0,
    val noOfMembers: Int = 0,
    val isAnonymous: Boolean = false,   // new: persisted from Firebase on group create/load
    val groupExpiresAt: Long = 0L,
    val anonymousAliases: String = "{}",  // JSON map: sanitized member ID -> alias (e.g., "user,example,com" -> "BK1")
)

