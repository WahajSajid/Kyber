package app.secure.kyber.roomdb.roomViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.roomdb.MessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Page size: initial visible batch + each "load older" batch. */
const val MSG_PAGE_SIZE = 15

class MessagesViewModel(private val repo: MessageRepository, private val onionAddress: String) : ViewModel() {

    /** Full message stream — kept for backwards-compat (SyncWorker, tests, etc.). */
    val messagesFlow = repo.observeAll(onionAddress)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Paginated stream: emits only the [MSG_PAGE_SIZE] most-recent messages,
     * sorted ascending (oldest-first in batch) so RecyclerView renders bottom-up.
     * Real-time new messages still appear immediately because Room re-emits on insert.
     */
    val recentMessagesFlow = repo.observeRecent(onionAddress, MSG_PAGE_SIZE)
        .map { list -> list.reversed() }   // DB returns DESC; we want ASC for display
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Last message per conversation — for the normal accepted chat list. */
    val lastMessagesFlow = repo.observeAllLastMsgs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Incoming pending message requests.
     */
    val incomingRequestsFlow = repo.observeIncomingRequests()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Fetches one page of messages older than [beforeTime] (exclusive).
     * Returns them sorted ascending so they can be prepended to current list.
     */
    suspend fun loadOlderMessages(beforeTime: Long): List<MessageEntity> =
        repo.getOlderMessages(onionAddress, beforeTime, MSG_PAGE_SIZE).reversed()

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
     */
    fun deleteAllBySender(senderOnion: String) {
        viewModelScope.launch {
            repo.deleteAllBySender(senderOnion)
        }
    }

    suspend fun getMessageByMessageId(messageId: String) = repo.getMessageByMessageId(messageId)
}