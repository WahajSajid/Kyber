package app.secure.kyber.roomdb.roomViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.roomdb.MessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MessagesViewModel(private val repo: MessageRepository, private val onionAddress: String) : ViewModel() {

    /** Messages for a specific private chat (used in ChatFragment). */
    val messagesFlow = repo.observeAll(onionAddress)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Last message per conversation — for the normal accepted chat list. */
    val lastMessagesFlow = repo.observeAllLastMsgs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Incoming pending message requests.
     * Conversations where:
     *   - all messages are inbound (isSent = 0),
     *   - sender is not in the contacts table, AND
     *   - at least one message carries isRequest = 1.
     *
     * Used by MessageRequestsFragment.
     */
    val incomingRequestsFlow = repo.observeIncomingRequests()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveMessage(
        messageId: String,
        msg: String,
        senderOnion: String,
        timestamp: String,
        isSent: Boolean,
        type: String = "TEXT",
        uri: String? = null,
        ampsJson: String? = null,
        apiMessageId: String? = null,
        reaction: String = "",
        isRequest: Boolean = false
    ) {
        viewModelScope.launch {
            repo.saveMsg(messageId, msg, senderOnion, timestamp, isSent, type, uri, ampsJson, apiMessageId, reaction, isRequest)
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

    /**
     * Bulk-delete all messages from/to a given onion address.
     * Used by rejectRequest() to remove an entire pending conversation in one query.
     */
    fun deleteAllBySender(senderOnion: String) {
        viewModelScope.launch {
            repo.deleteAllBySender(senderOnion)
        }
    }

    suspend fun getMessageByMessageId(messageId: String) = repo.getMessageByMessageId(messageId)
}