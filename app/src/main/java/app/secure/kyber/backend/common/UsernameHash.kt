package app.secure.kyber.backend.common

import android.util.Base64
import java.security.MessageDigest

object UsernameHash {
    /** SHA-256 of UTF-8 display name, Base64 (matches discovery registration). */
    fun forDiscovery(displayName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(displayName.trim().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hashBytes, Base64.NO_WRAP)
    }
}
