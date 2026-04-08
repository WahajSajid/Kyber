package app.secure.kyber.MyApp

import android.content.Intent
import android.os.Build
import androidx.lifecycle.MutableLiveData
import androidx.work.PeriodicWorkRequestBuilder
import app.secure.kyber.ApplicationClass
import app.secure.kyber.adapters.AddedMembers
import app.secure.kyber.onionrouting.UnionService
import app.secure.kyber.workers.SyncWorker
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.secure.kyber.Utils.NetworkMonitor
import app.secure.kyber.backend.common.Prefs

@Suppress("UNCHECKED_CAST")
class MyApp : ApplicationClass() {
    var tabBtnState = "individual_chat"
    var addedMembersList: MutableLiveData<MutableList<AddedMembers>> = MutableLiveData(mutableListOf())
    var finalMembersList: MutableLiveData<MutableList<AddedMembers>> = MutableLiveData(mutableListOf())
    var activeChatOnion: String? = null
    var activeGroupId: String? = null
    var isAppLocked: Boolean = false
    var lastBackgroundTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        NetworkMonitor.initialize(this)
        scheduleSyncWorker()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                lastBackgroundTime = System.currentTimeMillis()
            }

            override fun onStart(owner: LifecycleOwner) {
                val timeout = Prefs.getAutoLockTimeoutMs(this@MyApp)
                if (timeout > 0 && lastBackgroundTime > 0) {
                    if (System.currentTimeMillis() - lastBackgroundTime > timeout) {
                        isAppLocked = true
                    }
                }
            }
        })

        // Boot the socket service globally when the app launches
        val serviceIntent = Intent(this, UnionService::class.java).apply {
            action = UnionService.ACTION_START_SERVICE
            putExtra(UnionService.EXTRA_SERVER_HOST, "82.221.100.220") // Use your socket IP
            putExtra(UnionService.EXTRA_SERVER_PORT, 8080)             // Use your socket Port
        }

        // minSdk is 28, so startForegroundService is always available (added in API 26)
        startForegroundService(serviceIntent)
    }


    private fun scheduleSyncWorker() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        // Periodic worker: runs every 15 minutes even when app is closed
        val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
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
            
        // Schedule MessageCleanupWorker to run every 15 minutes globally
        val cleanupRequest = PeriodicWorkRequestBuilder<app.secure.kyber.workers.MessageCleanupWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        ).build()
        
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MESSAGE_CLEANUP_WORK",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest
        )
    }

}
