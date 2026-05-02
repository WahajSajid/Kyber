package app.secure.kyber.roomdb.roomViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.secure.kyber.roomdb.ContactEntity
import app.secure.kyber.roomdb.ContactRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactsViewModel(private val repo: ContactRepository) : ViewModel() {
    // live, auto-updating list
    val contactsFlow = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveContact(id: String, name: String, publicKey: String? = null, shortId: String? = null, isContact: Boolean = true) {
        viewModelScope.launch {
            repo.saveContact(id, name, publicKey, shortId, isContact)
        }
    }

    suspend fun getContact(id: String): ContactEntity? {
        return repo.getContact(id)
    }

    suspend fun getShortId(onionAddress:String): String?{
        return repo.getShortId(onionAddress)
    }


    fun observeContact(id: String): kotlinx.coroutines.flow.Flow<ContactEntity?> {
        return repo.observeContact(id)
    }
}
