package app.secure.kyber.roomdb.roomViewModel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.secure.kyber.roomdb.GroupMessageEntity
import app.secure.kyber.roomdb.GroupMessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupMessagesViewModel(
    private val repo: GroupMessageRepository
) : ViewModel() {
    
    fun getMessagesForGroupChat(
        groupId: String,
        onResult: (LiveData<List<GroupMessageEntity>>) -> Unit
    ) {
        viewModelScope.launch {
            val messages = repo.getAllGroupMessages(groupId)
            onResult(messages)
        }
    }

    val lastMessagesFlow = repo.observeAllLastMsgs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveMessage(
      message: GroupMessageEntity
    ) {
        viewModelScope.launch {
            repo.saveMsg(message)
        }
    }

    fun updateMessage(message: GroupMessageEntity) {
        viewModelScope.launch {
            repo.updateMsg(message)
        }
    }

    fun deleteMessage(message: GroupMessageEntity) {
        viewModelScope.launch {
            repo.deleteMsg(message)
        }
    }

    suspend fun getLatestMessage(groupId: String): GroupMessageEntity? {
        return repo.getLatestMessage(groupId)
    }
}
