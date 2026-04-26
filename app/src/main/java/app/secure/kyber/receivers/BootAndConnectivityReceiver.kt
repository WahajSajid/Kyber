package app.secure.kyber.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.onionrouting.UnionService
import app.secure.kyber.workers.SyncWorker

class BootAndConnectivityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        var action = intent.action ?: return
        Log.d("BootReceiver", "Received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.net.conn.CONNECTIVITY_CHANGE",
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                // Only act if user is registered (has an onion address)
                val onion = Prefs.getOnionAddress(context)
                if (onion.isNullOrEmpty()) return

                // Check network is actually available
                if (!isNetworkAvailable(context)) return

                // 1. Trigger immediate sync to catch missed messages
                val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag("boot_sync")
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "boot_sync_once",
                    ExistingWorkPolicy.REPLACE,
                    syncRequest
                )

                // 2. Restart UnionService for real-time socket delivery
                try {
                    val unionIntent = Intent(context, UnionService::class.java).apply {
                        action = UnionService.ACTION_START_SERVICE
                    }
                    val globalIntent = Intent(context, app.secure.kyber.services.GlobalSyncService::class.java)

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        try {
                            context.startForegroundService(unionIntent)
                            context.startForegroundService(globalIntent)
                        } catch (e: Exception) {
                            Log.e("BootReceiver", "ForegroundLaunch blocked by OS: ${e.message}")
                        }
                    } else {
                        context.startService(unionIntent)
                        context.startService(globalIntent)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Could not restart services: ${e.message}")
                }
            }
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}