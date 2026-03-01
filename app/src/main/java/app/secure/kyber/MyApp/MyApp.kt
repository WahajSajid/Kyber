package app.secure.kyber.MyApp

import androidx.lifecycle.MutableLiveData
import app.secure.kyber.ApplicationClass
import app.secure.kyber.adapters.AddedMembers

@Suppress("UNCHECKED_CAST")
class MyApp : ApplicationClass() {
    var tabBtnState = "individual_chat"
    var addedMembersList: MutableLiveData<MutableList<AddedMembers>> = MutableLiveData(mutableListOf())
    var finalMembersList: MutableLiveData<MutableList<AddedMembers>> = MutableLiveData(mutableListOf())

}
