package app.secure.kyber.backend.common

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object Prefs {
    private const val FILE = "kyber_prefs"
    private const val KEY_UNION_ID = "union_id"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_ONION_ADDRESS = "onion_address"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_CIRCUIT_ID = "circuit_id"
    private const val KEY_NAME = "name"
    private const val KEY_PASSWORD = "password"

    private const val KEY_LICENSE = "license"
    private const val KEY_RECENT_EMOJIS = "recent_emojis"

    private const val DISAPPEARING_MESSAGES_STATUS = "Off"
    private const val MUTE_NOTIFICATION_STATUS = "Always"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Setters
    fun setUnionId(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_UNION_ID) else putString(KEY_UNION_ID, value)
            apply()
        }
    }

    fun setOnionAddress(ctx: Context, value: String?) {
        prefs(ctx).edit().apply {
            if (value == null) remove(KEY_ONION_ADDRESS) else putString(KEY_ONION_ADDRESS, value)
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
            if (value == null) remove(DISAPPEARING_MESSAGES_STATUS) else putString(DISAPPEARING_MESSAGES_STATUS, value)
            apply()
        }
    }

    fun setMuteNotificationStatus(ctx: Context, value: String?){
        prefs(ctx).edit().apply{
            if (value == null) remove(MUTE_NOTIFICATION_STATUS) else putString(MUTE_NOTIFICATION_STATUS, value)
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
    fun getUnionId(ctx: Context): String? = prefs(ctx).getString(KEY_UNION_ID, null)
    fun getOnionAddress(ctx: Context): String? = prefs(ctx).getString(KEY_ONION_ADDRESS, null)
    fun getSessionToken(ctx: Context): String? = prefs(ctx).getString(KEY_SESSION_TOKEN, null)
    fun getCircuitId(ctx: Context): String? = prefs(ctx).getString(KEY_CIRCUIT_ID, null)
    fun getPublicKey(ctx: Context): String? = prefs(ctx).getString(KEY_PUBLIC_KEY, null)
    fun getName(ctx: Context): String? = prefs(ctx).getString(KEY_NAME, null)
    fun getPassword(ctx: Context): String? = prefs(ctx).getString(KEY_PASSWORD, null)
    fun getLicense(ctx: Context): String? = prefs(ctx).getString(KEY_LICENSE, null)
    fun getDisappearingMessageStatus(ctx: Context): String? = prefs(ctx).getString(DISAPPEARING_MESSAGES_STATUS, null)
    fun getMuteNotificationStatus(ctx: Context): String? = prefs(ctx).getString(MUTE_NOTIFICATION_STATUS, null)

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
    fun removeUnionId(ctx: Context) = prefs(ctx).edit().remove(KEY_UNION_ID).apply()
    fun removePublicKey(ctx: Context) = prefs(ctx).edit().remove(KEY_PUBLIC_KEY).apply()
}
