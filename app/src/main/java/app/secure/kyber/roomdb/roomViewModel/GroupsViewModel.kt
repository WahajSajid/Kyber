package app.secure.kyber.roomdb.roomViewModel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.secure.kyber.backend.models.ChatModel
import app.secure.kyber.roomdb.GroupRepository
import app.secure.kyber.roomdb.GroupsEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupsViewModel(private val repo: GroupRepository) : ViewModel() {
    // live, auto-updating list
    val groupChatFlow = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    fun getGroupList(
        onResult: (LiveData<List<GroupsEntity>>) -> Unit
    ) {
        viewModelScope.launch {
            val groups = repo.getAllOnce()
            onResult(groups)
        }
    }


    fun getCreationDate(
        onResult: (Long) -> Unit,
        groupId:String
    ) {
        viewModelScope.launch {
            val creationDate = repo.getCreationDate(groupId)
            onResult(creationDate)
        }
    }

    fun getNoOfMembers(
        onResult: (Int) -> Unit,
        groupId:String
    ) {
        viewModelScope.launch {
            val noOfMembers = repo.getNoOfMembers(groupId)
            onResult(noOfMembers)
        }
    }

    fun saveGroup(group: GroupsEntity) {
        viewModelScope.launch {
            repo.saveGroup(group)
        }
    }

    suspend fun getGroupById(groupId: String): GroupsEntity? {
        return repo.getGroupById(groupId)
    }
}
