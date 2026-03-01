package app.secure.kyber.GroupCreationBackend

import android.util.Log
import app.secure.kyber.roomdb.GroupsEntity
import app.secure.kyber.roomdb.roomViewModel.GroupsViewModel
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener


object LoadGroups {

    fun loadGroup(myId: String, database: FirebaseDatabase, groupViewModel: GroupsViewModel) {

        Log.d("###Check 1", "In the loadGroupsMethod")

        val dbRef = database.getReference("groups")
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (groupSnapshot in snapshot.children) {
                    val membersRef = groupSnapshot.child("members")
                    var isMember = false
                    for (member in membersRef.children) {
                        val memberId = member.child("id").value ?: ""
                        if (memberId.toString() == myId) {
                            isMember = true
                            break
                        }
                    }

                    if (isMember) {
                        val createdAt = groupSnapshot.child("createdAt").value ?: 0
                        val createdBy = groupSnapshot.child("createdBy").value ?: ""
                        val groupId = groupSnapshot.child("groupId").value ?: ""
                        val groupName = groupSnapshot.child("groupName").value ?: ""
                        val lastMessage = groupSnapshot.child("lastMessage").value ?: ""
                        val lastMessageTime = groupSnapshot.child("lastMessageTime").value ?: 0
                        val noOfMembers = membersRef.childrenCount.toInt()

                        Log.d("###Check 2", "Group Retrieved: $groupName, members: $noOfMembers")
                        val groupEntity = GroupsEntity(
                            groupId = groupId.toString(),
                            groupName = groupName.toString(),
                            lastMessage = lastMessage.toString(),
                            timeSpan = lastMessageTime.toString().toLong(),
                            createdBy = createdBy.toString(),
                            createdAt = createdAt.toString().toLong(),
                            noOfMembers = noOfMembers
                        )
                        groupViewModel.saveGroup(groupEntity)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("LoadGroups", "Database error: ${error.message}")
            }
        })
    }
}
