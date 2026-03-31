package app.secure.kyber.MyApp

import android.content.Intent
import android.os.Build
import androidx.lifecycle.MutableLiveData
import androidx.work.PeriodicWorkRequestBuilder
import app.secure.kyber.ApplicationClass
import app.secure.kyber.adapters.AddedMembers
import app.secure.kyber.onionrouting.UnionService
import app.secure.kyber.workers.SyncWorker

@Suppress("UNCHECKED_CAST")
class MyApp : ApplicationClass() {
    var tabBtnState = "individual_chat"
    var addedMembersList: MutableLiveData<MutableList<AddedMembers>> = MutableLiveData(mutableListOf())
    var finalMembersList: MutableLiveData<MutableList<AddedMembers>> = MutableLiveData(mutableListOf())
    var activeChatOnion: String? = null

    override fun onCreate() {
        super.onCreate()

        scheduleSyncWorker()

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


    private fun scheduleSyncWorker() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        // Periodic worker: runs every 15 minutes even when app is closed
        val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES       // ← CORRECT
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,  // Don't restart if already scheduled
            periodicRequest
        )

        // Also run an immediate one-time sync right now (cold start backfill)
        val immediateRequest = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        androidx.work.WorkManager.getInstance(this)
            .enqueue(immediateRequest)
    }

}