package app.secure.kyber.roomdb.roomViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.secure.kyber.roomdb.ContactRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactsViewModel(private val repo: ContactRepository) : ViewModel() {
    // live, auto-updating list
    val contactsFlow = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // or, if you need a one-shot load:
    // suspend fun loadAllOnce() = repo.getAllOnce()

    fun saveContact(id: String, name: String) {
        viewModelScope.launch {
            repo.saveContact(id,name)
        }
    }
}
