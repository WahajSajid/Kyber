package app.secure.kyber.roomdb.roomViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.secure.kyber.roomdb.MessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MessagesViewModel(private val repo: MessageRepository, private val senderId: String) : ViewModel() {
    // live, auto-updating list
    val messagesFlow = repo.observeAll(senderId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val lastMessagesFlow = repo.observeAllLastMsgs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // or, if you need a one-shot load:
    // suspend fun loadAllOnce() = repo.getAllOnce()

    fun saveMessage(msg: String, senderId: String, timestamp: String,isSent: Boolean) {
        viewModelScope.launch {
            repo.saveMsg(msg, senderId, timestamp,isSent)
        }
    }
}
