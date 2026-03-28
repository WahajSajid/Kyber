package app.secure.kyber.MyApp

import android.content.Intent
import android.os.Build
import androidx.lifecycle.MutableLiveData
import app.secure.kyber.ApplicationClass
import app.secure.kyber.adapters.AddedMembers
import app.secure.kyber.onionrouting.UnionService

@Suppress("UNCHECKED_CAST")
class MyApp : ApplicationClass() {
    var tabBtnState = "individual_chat"
    var addedMembersList: MutableLiveData<MutableList<AddedMembers>> = MutableLiveData(mutableListOf())
    var finalMembersList: MutableLiveData<MutableList<AddedMembers>> = MutableLiveData(mutableListOf())
    var activeChatOnion: String? = null

    override fun onCreate() {
        super.onCreate()

        // Boot the socket service globally when the app launches
        val serviceIntent = Intent(this, UnionService::class.java).apply {
            action = UnionService.ACTION_START_SERVICE
            putExtra(UnionService.EXTRA_SERVER_HOST, "82.221.100.220") // Use your socket IP
            putExtra(UnionService.EXTRA_SERVER_PORT, 8080)             // Use your socket Port
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}