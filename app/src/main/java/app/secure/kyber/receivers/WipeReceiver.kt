package app.secure.kyber.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.secure.kyber.services.WipeService

/**
 * Receives the UNDO broadcast from the wipe notification action button.
 * Forwards ACTION_CANCEL_WIPE to WipeService so it can stop itself cleanly.
 */
class WipeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WipeService.ACTION_CANCEL_WIPE) {
            Log.d("WipeReceiver", "UNDO received — cancelling wipe")
            val serviceIntent = Intent(context, WipeService::class.java).apply {
                action = WipeService.ACTION_CANCEL_WIPE
            }
            context.startService(serviceIntent)
        }
    }
}
