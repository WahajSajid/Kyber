package app.secure.kyber.GroupCreationBackend

import android.util.Log
import app.secure.kyber.dataClasses.Group
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.GroupsEntity
import app.secure.kyber.roomdb.roomViewModel.GroupsViewModel
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


object LoadGroups {

    fun loadGroup(context: android.content.Context, myId: String, database: FirebaseDatabase, groupViewModel: GroupsViewModel) {
        val dbRef = database.getReference("groups")
        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDb.get(context)
                    val dao = db.groupsDao()
                    
                    for (groupSnapshot in snapshot.children) {
                        val group = groupSnapshot.getValue(Group::class.java) ?: continue
                        val isMember = group.members.values.any { idsMatch(it["id"], myId) }

                        if (isMember) {
                            val existing = dao.getGroupById(group.groupId)
                            if (existing == null) {
                                val groupEntity = GroupsEntity(
                                    groupId = group.groupId,
                                    groupName = group.groupName,
                                    lastMessage = group.lastMessage,
                                    newMessagesCount = group.newMessagesCount,
                                    profileImageResId = 0,
                                    timeSpan = group.lastMessageTime.toLongOrNull() ?: 0L,
                                    chatTime = "",
                                    createdBy = group.createdBy,
                                    createdAt = group.createdAt,
                                    noOfMembers = group.members.size,
                                    isAnonymous = group.anonymous,
                                    groupExpiresAt = group.groupExpiresAt
                                )
                                dao.insert(groupEntity)
                            } else {
                                // METADATA ONLY: Preserve local unread count and last message
                                val updated = existing.copy(
                                    groupName = group.groupName,
                                    noOfMembers = group.members.size,
                                    isAnonymous = group.anonymous,
                                    createdBy = group.createdBy,
                                    createdAt = group.createdAt,
                                    groupExpiresAt = group.groupExpiresAt
                                )
                                dao.update(updated)
                            }
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun normalizeId(id: String?): String {
        return id.orEmpty().trim().replace(",", ".")
    }

    private fun idsMatch(left: String?, right: String?): Boolean {
        return normalizeId(left).isNotEmpty() && normalizeId(left) == normalizeId(right)
    }
}
