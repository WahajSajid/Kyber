package app.secure.kyber.backend.common

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object Prefs {

    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_MSG_TIME  = "last_msg_server_time"

    private const val FILE = "kyber_prefs"
    private const val KEY_ONION_ADDRESS = "onion_address"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_CIRCUIT_ID = "circuit_id"
    private const val KEY_NAME = "name"

    private const val SHORT_ID = "short_id"

    private const val NAME_HASH = "name_hash"

    private const val KEY_PASSWORD = "password"

    private const val KEY_LICENSE = "license"
    private const val KEY_RECENT_EMOJIS = "recent_emojis"

    private const val KEY_DISAPPEARING_MESSAGES_STATUS = "disappearing_messages_status"
    private const val KEY_MUTE_NOTIFICATION_STATUS = "mute_notification_status"
    private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout"
    private const val KEY_ENCRYPTION_TIMER = "encryption_timer"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Setters
    fun setOnionAddress(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_ONION_ADDRESS) else putString(KEY_ONION_ADDRESS, value)
            apply()
        }
    }


    fun setShortId(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(SHORT_ID) else putString(SHORT_ID, value)
            apply()
        }
    }


    fun setHashName(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(NAME_HASH) else putString(NAME_HASH, value)
            apply()
        }
    }

    fun setSessionToken(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_SESSION_TOKEN) else putString(KEY_SESSION_TOKEN, value)
            apply()
        }
    }

    fun setCircuitId(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_CIRCUIT_ID) else putString(KEY_CIRCUIT_ID, value)
            apply()
        }
    }

    fun setDisappearingMessagesStatus(ctx: Context, value: String?){
        prefs(ctx).edit().apply{
            if (value == null) remove(KEY_DISAPPEARING_MESSAGES_STATUS) else putString(KEY_DISAPPEARING_MESSAGES_STATUS, value)
            apply()
        }
    }

    fun setMuteNotificationStatus(ctx: Context, value: String?){
        prefs(ctx).edit().apply{
            if (value == null) remove(KEY_MUTE_NOTIFICATION_STATUS) else putString(KEY_MUTE_NOTIFICATION_STATUS, value)
            apply()
        }
    }

    fun setAutoLockTimeout(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_AUTO_LOCK_TIMEOUT) else putString(KEY_AUTO_LOCK_TIMEOUT, value)
            apply()
        }
    }

    fun setEncryptionTimer(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_ENCRYPTION_TIMER) else putString(KEY_ENCRYPTION_TIMER, value)
            apply()
        }
    }

    fun setPublicKey(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_PUBLIC_KEY) else putString(KEY_PUBLIC_KEY, value)
            apply()
        }
    }

    fun setLicense(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_LICENSE) else putString(KEY_LICENSE, value)
            apply()
        }
    }

    fun setName(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_NAME) else putString(KEY_NAME, value)
            apply()
        }
    }

    fun setPassword(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, value)
            apply()
        }
    }

    fun setRecentEmojis(ctx: Context, emojis: List<String>) {
        val json = JSONArray(emojis).toString()
        prefs(ctx).edit().putString(KEY_RECENT_EMOJIS, json).apply()
    }

    // Getters
    fun getOnionAddress(ctx: Context): String? = prefs(ctx).getString(KEY_ONION_ADDRESS, null)
    fun getNameHash(ctx: Context): String? = prefs(ctx).getString(NAME_HASH, null)

    fun getShortId(ctx: Context): String? = prefs(ctx).getString(SHORT_ID, null)



    fun getSessionToken(ctx: Context): String? = prefs(ctx).getString(KEY_SESSION_TOKEN, null)
    fun getCircuitId(ctx: Context): String? = prefs(ctx).getString(KEY_CIRCUIT_ID, null)
    fun getPublicKey(ctx: Context): String? = prefs(ctx).getString(KEY_PUBLIC_KEY, null)
    fun getName(ctx: Context): String? = prefs(ctx).getString(KEY_NAME, null)
    fun getPassword(ctx: Context): String? = prefs(ctx).getString(KEY_PASSWORD, null)
    fun getLicense(ctx: Context): String? = prefs(ctx).getString(KEY_LICENSE, null)
    fun getDisappearingMessageStatus(ctx: Context): String? = prefs(ctx).getString(KEY_DISAPPEARING_MESSAGES_STATUS, null)
    fun getMuteNotificationStatus(ctx: Context): String? = prefs(ctx).getString(KEY_MUTE_NOTIFICATION_STATUS, null)
    fun getAutoLockTimeout(ctx: Context): String? = prefs(ctx).getString(KEY_AUTO_LOCK_TIMEOUT, "Never")
    fun getEncryptionTimer(ctx: Context): String? = prefs(ctx).getString(KEY_ENCRYPTION_TIMER, "24 Hours")

    fun getDisappearingTimerMs(ctx: Context): Long {
        return when (getDisappearingMessageStatus(ctx)) {
            "24 Hours" -> 24L * 60 * 60 * 1000
            "7 Days" -> 7L * 24 * 60 * 60 * 1000
            "30 Days" -> 30L * 24 * 60 * 60 * 1000
            "Always" -> 60L * 60 * 1000 // 1h — feature always on without the old 1ms instant-delete bug
            else -> 0L
        }
    }

    fun getAutoLockTimeoutMs(ctx: Context): Long {
        return when (getAutoLockTimeout(ctx)) {
            "1 Minute" -> 60L * 1000
            "5 Minutes" -> 5L * 60 * 1000
            "15 Minutes" -> 15L * 60 * 1000
            else -> 0L // Never
        }
    }

    fun getEncryptionTimerMs(ctx: Context): Long {
        return when (getEncryptionTimer(ctx)) {
            "24 Hours" -> 24L * 60 * 60 * 1000
            "48 Hours" -> 48L * 60 * 60 * 1000
            "7 Days" -> 7L * 24 * 60 * 60 * 1000
            else -> 0L // Never
        }
    }

    fun getRecentEmojis(ctx: Context): List<String>? {
        val json = prefs(ctx).getString(KEY_RECENT_EMOJIS, null) ?: return null
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    // Utilities
    fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()
    fun removeOnionAddress(ctx: Context) = prefs(ctx).edit().remove(KEY_ONION_ADDRESS).apply()
    fun removePublicKey(ctx: Context) = prefs(ctx).edit().remove(KEY_PUBLIC_KEY).apply()


    fun getLastSyncTime(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_TIME, 0L)

    fun setLastSyncTime(context: Context, time: Long) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SYNC_TIME, time).apply()

    fun getLastMsgTime(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_MSG_TIME, 0L)

    fun setLastMsgTime(context: Context, time: Long) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_MSG_TIME, time).apply()

}
