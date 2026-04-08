package app.secure.kyber.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.secure.kyber.services.GlobalSyncService

/**
 * Receives BOOT_COMPLETED broadcast and starts GlobalSyncService.
 * Ensures Kyber sync service runs 24/7, even after device reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootCompletedReceiver", "Device boot completed — starting GlobalSyncService")
            
            context?.let {
                val serviceIntent = Intent(it, GlobalSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.startForegroundService(serviceIntent)
                } else {
                    it.startService(serviceIntent)
                }
            }
        }
    }
}
