package app.secure.kyber.roomdb.roomViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.roomdb.MessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MessagesViewModel(private val repo: MessageRepository, private val onionAddress: String) : ViewModel() {
    // live, auto-updating list
    val messagesFlow = repo.observeAll(onionAddress)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val lastMessagesFlow = repo.observeAllLastMsgs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveMessage(
        msg: String,
        senderOnion: String,
        timestamp: String,
        isSent: Boolean,
        type: String = "TEXT",
        uri: String? = null,
        ampsJson: String? = null
    ) {
        viewModelScope.launch {
            repo.saveMsg(msg, senderOnion, timestamp, isSent, type, uri, ampsJson)
        }
    }

    fun updateMessage(message: MessageEntity) {
        viewModelScope.launch {
            repo.updateMsg(message)
        }
    }

    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch {
            repo.deleteMsg(message)
        }
    }
}
