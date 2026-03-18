package app.secure.kyber.GroupCreationBackend

import android.util.Log
import app.secure.kyber.adapters.AddedMembers
import app.secure.kyber.dataClasses.Group
import app.secure.kyber.dataClasses.GroupMessage
import app.secure.kyber.roomdb.GroupMessageEntity
import app.secure.kyber.roomdb.GroupsEntity
import app.secure.kyber.roomdb.roomViewModel.GroupMessagesViewModel
import app.secure.kyber.roomdb.roomViewModel.GroupsViewModel
import com.google.firebase.database.*
import kotlinx.coroutines.tasks.await

class GroupManager {
    private val database =
        FirebaseDatabase.getInstance("https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/")
    private val groupsRef = database.getReference("groups")
    private val groupMessagesRef = database.getReference("group_messages")
    private val userGroupsRef = database.getReference("user_groups")

    private fun encodeKey(key: String): String = key.replace(".", ",")
    private fun decodeKey(key: String): String = key.replace(",", ".")

    suspend fun createGroup(
        groupName: String,
        groupImage: String = "",
        members: List<AddedMembers>,
        currentUserId: String,
        currentUserName: String,
        groupViewModel: GroupsViewModel
    ): String? {
        return try {
            val groupId = groupsRef.push().key ?: return null

            Log.d("GroupManager", "Starting group creation for: $groupName with ID: $groupId")

            val membersMap = members.associate { encodeKey(it.id) to mapOf("id" to it.id, "name" to it.name) }.toMutableMap()
            // Ensure the creator is always in the member list
            membersMap[encodeKey(currentUserId)] = mapOf("id" to currentUserId, "name" to currentUserName)

            val group = Group(
                groupId = groupId,
                groupName = groupName,
                groupImage = groupImage,
                createdBy = currentUserId,
                createdAt = System.currentTimeMillis(),
                members = membersMap,
                lastMessage = "",
                lastMessageTime = 0L,
                lastMessageSenderId = "",
                newMessagesCount = 0
            )

            val groupLocal = GroupsEntity(
                groupId = groupId,
                groupName = groupName,
                lastMessage = "",
                newMessagesCount = 0,
                profileImageResId = 0,
                timeSpan = System.currentTimeMillis(),
                chatTime = "",
                createdAt = System.currentTimeMillis(),
                noOfMembers = membersMap.size
            )

            // Step 1: Save group to Firebase
            try {
                groupsRef.child(groupId).setValue(group).await()
                Log.d("GroupManager", "Group saved to Firebase successfully")
            } catch (e: Exception) {
                Log.e("GroupManager", "Failed to save group to Firebase: ${e.message}", e)
                throw e
            }

            // Step 2: Save group to local database
            try {
                groupViewModel.saveGroup(groupLocal)
                Log.d("GroupManager", "Group saved to local DB successfully")
            } catch (e: Exception) {
                Log.e("GroupManager", "Failed to save group to local DB: ${e.message}", e)
                // Continue anyway - Firebase is the source of truth
            }

            // Step 3: Update user_groups for all members
            try {
                membersMap.keys.forEach { encodedUserId ->
                    userGroupsRef.child(encodedUserId).child(groupId).setValue(true).await()
                }
                Log.d("GroupManager", "User groups updated successfully")
            } catch (e: Exception) {
                Log.e("GroupManager", "Failed to update user groups: ${e.message}", e)
                throw e
            }

            Log.d("GroupManager", "Group created successfully in Firebase and local DB")
            groupId
        } catch (e: Exception) {
            Log.e("GroupManager", "Error creating group: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    suspend fun sendMessage(
        groupId: String,
        senderId: String,
        senderName: String,
        messageText: String,
        groupMessagesViewModel: GroupMessagesViewModel,
        type: String = "TEXT",
        uri: String? = null,
        ampsJson: String? = null
    ): Boolean {
        return try {
            val messageId = groupMessagesRef.child(groupId).push().key ?: return false

            val message = GroupMessageEntity(
                messageId = messageId,
                group_id = groupId,
                senderOnion = senderId,
                senderName = senderName,
                msg = messageText,
                time = System.currentTimeMillis().toString(),
                isSent = true,
                type = type,
                uri = uri,
                ampsJson = ampsJson.toString()
            )

            groupMessagesViewModel.saveMessage(message)
            groupMessagesRef.child(groupId).child(messageId).setValue(message).await()

            val lastMsg = when (type) {
                "IMAGE" -> "$senderName sent an image"
                "VIDEO" -> "$senderName sent a video"
                else -> "$senderName: $messageText"
            }
            val updates = hashMapOf<String, Any>(
                "lastMessage" to lastMsg,
                "lastMessageTime" to message.time,
                "lastMessageSenderId" to senderId
            )
            groupsRef.child(groupId).updateChildren(updates).await()

            true
        } catch (e: Exception) {
            Log.e("GroupManager", "Error sending message: ${e.message}", e)
            false
        }
    }

    fun listenForMessages(
        groupId: String,
        mySenderId: String,
        groupMessageViewModel: GroupMessagesViewModel
    ): DatabaseReference {
        val messagesListener = groupMessagesRef.child(groupId)

        messagesListener.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (messageSnapshot in snapshot.children) {
                    try {
                        val messageId = messageSnapshot.child("messageId").getValue(String::class.java) ?: ""
                        val group_id = messageSnapshot.child("group_id").getValue(String::class.java) ?: ""
                        val sender_id = messageSnapshot.child("senderId").getValue(String::class.java) ?: ""
                        val senderName = messageSnapshot.child("senderName").getValue(String::class.java) ?: ""
                        val messageText = messageSnapshot.child("msg").getValue(String::class.java) ?: ""
                        val timeStamp = messageSnapshot.child("time").getValue(String::class.java) ?: ""
                        val isSent = messageSnapshot.child("sent").getValue(Boolean::class.java) ?: false
                        val type = messageSnapshot.child("type").getValue(String::class.java) ?: "TEXT"
                        val uri = messageSnapshot.child("uri").getValue(String::class.java)
                        val reaction = messageSnapshot.child("reaction").getValue(String::class.java) ?: ""

                        if (mySenderId != sender_id) {
                            val message = GroupMessageEntity(
                                messageId = messageId,
                                group_id = group_id,
                                msg = messageText,
                                senderOnion = sender_id,
                                senderName = senderName,
                                time = timeStamp,
                                isSent = isSent,
                                type = type,
                                uri = uri,
                                reaction = reaction
                            )
                            groupMessageViewModel.saveMessage(message)
                        } else {
                            // Also update local reaction if it's my message and changed on server
                            val message = GroupMessageEntity(
                                messageId = messageId,
                                group_id = group_id,
                                msg = messageText,
                                senderOnion = sender_id,
                                senderName = senderName,
                                time = timeStamp,
                                isSent = true,
                                type = type,
                                uri = uri,
                                reaction = reaction
                            )
                            groupMessageViewModel.saveMessage(message)
                        }
                    } catch (e: Exception) {
                        Log.e("listenForMessages", "Error processing message", e)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("listenForMessages", "Database error: ${error.message}")
            }
        })

        return messagesListener
    }

    suspend fun updateReaction(groupId: String, messageId: String, reaction: String): Boolean {
        return try {
            groupMessagesRef.child(groupId).child(messageId).child("reaction").setValue(reaction).await()
            true
        } catch (e: Exception) {
            Log.e("GroupManager", "Error updating reaction: ${e.message}", e)
            false
        }
    }

    suspend fun deleteMessage(groupId: String, messageId: String): Boolean {
        return try {
            groupMessagesRef.child(groupId).child(messageId).removeValue().await()
            true
        } catch (e: Exception) {
            Log.e("GroupManager", "Error deleting message: ${e.message}", e)
            false
        }
    }

    suspend fun updateLastMessage(groupId: String, lastMsg: String, lastTime: String, senderId: String) {
        try {
            val updates = hashMapOf<String, Any>(
                "lastMessage" to lastMsg,
                "lastMessageTime" to lastTime,
                "lastMessageSenderId" to senderId
            )
            groupsRef.child(groupId).updateChildren(updates).await()
        } catch (e: Exception) {
            Log.e("GroupManager", "Error updating last message: ${e.message}", e)
        }
    }

    fun listenForNewMessages(
        groupId: String,
        onNewMessage: (GroupMessage) -> Unit
    ): DatabaseReference {
        val messagesListener = groupMessagesRef.child(groupId)

        messagesListener.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.getValue(GroupMessage::class.java)
                message?.let { onNewMessage(it) }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("GroupManager", "listenForNewMessages error: ${error.message}")
            }
        })

        return messagesListener
    }

    fun getUserGroups(
        userId: String,
        onGroupsReceived: (List<Group>) -> Unit
    ) {
        userGroupsRef.child(encodeKey(userId)).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val groups = mutableListOf<Group>()
                val groupIds = snapshot.children.mapNotNull { it.key }

                if (groupIds.isEmpty()) {
                    onGroupsReceived(emptyList())
                    return
                }

                var processedCount = 0
                groupIds.forEach { groupId ->
                    groupsRef.child(groupId).get().addOnSuccessListener { groupSnapshot ->
                        val group = groupSnapshot.getValue(Group::class.java)
                        group?.let { groups.add(it) }

                        processedCount++
                        if (processedCount == groupIds.size) {
                            groups.sortByDescending { it.lastMessageTime }
                            onGroupsReceived(groups)
                        }
                    }.addOnFailureListener {
                        processedCount++
                        if (processedCount == groupIds.size) {
                            onGroupsReceived(groups)
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("GroupManager", "getUserGroups error: ${error.message}")
            }
        })
    }
    
}
