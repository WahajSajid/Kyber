package app.secure.kyber.GroupCreationBackend

import android.content.Context
import android.util.Log
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.dataClasses.Group
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.GroupsEntity
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object GlobalGroupSync {
    private val activeGroupListeners = mutableMapOf<String, ChildEventListener>()
    private const val DB_URL = "https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/"

    fun startGlobalSync(context: Context, myId: String) {
        val database = FirebaseDatabase.getInstance(DB_URL)
        val groupsRef = database.getReference("groups")

        groupsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AppDb.get(context)
                        val groupDao = db.groupsDao()
                        val groupMessageDao = db.groupsMessagesDao()
                        val memberGroupIds = mutableSetOf<String>()
                        val now = System.currentTimeMillis()
                        val manager = GroupManager()

                        for (groupSnapshot in snapshot.children) {
                            val group = groupSnapshot.getValue(Group::class.java) ?: continue
                            val isMember = group.members.values.any { idsMatch(it["id"], myId) }
                            val isExpired = group.groupExpiresAt > 0L && now >= group.groupExpiresAt

                            if (isExpired) {
                                if (idsMatch(myId, group.createdBy)) {
                                    val allMemberIds = group.members.values.mapNotNull { it["id"] }
                                    manager.deleteGroupEverywhere(group.groupId, allMemberIds)
                                }
                                groupDao.deleteById(group.groupId)
                                groupMessageDao.deleteByGroupId(group.groupId)
                                continue
                            }

                            if (isMember) {
                                memberGroupIds.add(group.groupId)
                                val existingGroup = groupDao.getGroupById(group.groupId)
                                
                                if (existingGroup == null) {
                                    // We were added to a new group! Insert it and notify.
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
                                    groupDao.insert(groupEntity)

                                    if (!idsMatch(group.createdBy, myId)) {
                                        val creatorName = group.members.values.find { it["id"] == group.createdBy }?.get("name") ?: "Someone"
                                        GroupManager().showGroupNotification(
                                            context,
                                            group.groupId,
                                            "TEXT"
                                        )
                                    }
                                    } else {
                                    val updated = existingGroup.copy(
                                        groupName = group.groupName,
                                        noOfMembers = group.members.size,
                                        isAnonymous = group.anonymous,
                                        createdBy = group.createdBy,
                                        createdAt = group.createdAt,
                                        groupExpiresAt = group.groupExpiresAt
                                    )
                                    groupDao.update(updated)
                                }

                                if (group.anonymous) {
                                    val sortedMembers = group.members.values.mapNotNull { it["id"] }.sorted()
                                    val aliasMap = org.json.JSONObject()
                                    if (group.anonymousAliases.isNotEmpty()) {
                                        group.anonymousAliases.forEach { (memberId, alias) ->
                                            aliasMap.put(memberId, alias)
                                            aliasMap.put(memberId.replace(".", ","), alias)
                                        }
                                    } else {
                                        var index = 1
                                        for (memberId in sortedMembers) {
                                            if (memberId != group.createdBy) {
                                                aliasMap.put(memberId, "BK$index")
                                                aliasMap.put(memberId.replace(".", ","), "BK$index")
                                                index++
                                            }
                                        }
                                    }
                                    context.getSharedPreferences("anonymous_aliases", Context.MODE_PRIVATE)
                                        .edit().putString(group.groupId, aliasMap.toString()).apply()
                                }

                                // Start listening to messages for this group if not already doing so
                                if (!activeGroupListeners.containsKey(group.groupId)) {
                                    listenForGroupMessages(context, group.groupId, myId, group.anonymous, group.createdBy)
                                }
                            }
                        }
                        if (memberGroupIds.isEmpty()) {
                            groupDao.deleteAll()
                        } else {
                            groupDao.deleteGroupsNotIn(memberGroupIds.toList())
                            groupMessageDao.deleteByGroupIdsNotIn(memberGroupIds.toList())
                        }
                    } catch (e: Exception) {
                        Log.e("GlobalGroupSync", "Error in global sync processing", e)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("GlobalGroupSync", "Failed to sync groups globally: ${error.message}")
            }
        })
    }

    private fun listenForGroupMessages(context: Context, groupId: String, myId: String, isAnonymous: Boolean, creatorId: String) {
        val database = FirebaseDatabase.getInstance(DB_URL)
        val msgRef = database.getReference("group_messages").child(groupId)
        
        val listenerStartedAt = System.currentTimeMillis()
        val groupManager = GroupManager()

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                CoroutineScope(Dispatchers.IO).launch {
                    groupManager.processIncomingGlobalMessage(
                        context, snapshot, groupId, myId, isAnonymous, creatorId, listenerStartedAt
                    )
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                CoroutineScope(Dispatchers.IO).launch {
                    groupManager.processIncomingGlobalReaction(
                        context, snapshot, groupId, myId, isAnonymous, creatorId
                    )
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        
        msgRef.addChildEventListener(listener)
        activeGroupListeners[groupId] = listener
    }

    private fun normalizeId(id: String?): String {
        return id.orEmpty().trim().replace(",", ".")
    }

    private fun idsMatch(left: String?, right: String?): Boolean {
        return normalizeId(left).isNotEmpty() && normalizeId(left) == normalizeId(right)
    }
}
