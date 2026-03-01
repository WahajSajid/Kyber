package app.secure.kyber.viewmodels


import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class AddMembersViewModel : ViewModel() {
    val selectedMembers = MutableLiveData<MutableMap<String, Boolean>>()

    init {
        selectedMembers.value = mutableMapOf()
    }

    fun toggleMemberSelection(name: String) {
        val currentSelection = selectedMembers.value ?: mutableMapOf()
        val isSelected = currentSelection[name] ?: false
        currentSelection[name] = !isSelected
        selectedMembers.value = currentSelection // Trigger LiveData update
    }

    fun isMemberSelected(name: String): Boolean {
        return selectedMembers.value?.get(name) ?: false
    }
}