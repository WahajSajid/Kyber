package app.secure.kyber.backend.common

import android.util.Log
import java.time.Instant
import java.time.format.DateTimeParseException

object DisappearTime {
    private const val TAG = "DisappearTime"

    /**
     * Parses a transport/API timestamp string (millis as decimal, seconds, or ISO-8601).
     */
    fun parseMessageTimestampMs(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        val trimmed = value.trim()
        val asLong = trimmed.toLongOrNull()
        if (asLong != null) {
            return if (asLong < 1_000_000_000_000L) asLong * 1000L else asLong
        }
        return try {
            Instant.parse(trimmed).toEpochMilli()
        } catch (e: DateTimeParseException) {
            Log.w(TAG, "Could not parse timestamp: $value")
            System.currentTimeMillis()
        }
    }

    fun expiresAtFromSent(sentTimeMs: Long, disappearTtlMs: Long?): Long {
        if (disappearTtlMs == null || disappearTtlMs <= 0L) return 0L
        return sentTimeMs + disappearTtlMs
    }
}
