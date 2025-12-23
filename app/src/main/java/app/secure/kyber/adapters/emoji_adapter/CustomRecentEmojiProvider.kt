package app.secure.kyber.adapters.emoji_adapter



import android.annotation.SuppressLint
import android.content.Context
import androidx.emoji2.emojipicker.RecentEmojiAsyncProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class CustomRecentEmojiProvider(private val context: Context) : RecentEmojiAsyncProvider {

    private val prefs = context.getSharedPreferences("recent_emojis", Context.MODE_PRIVATE)

    override fun getRecentEmojiListAsync(): ListenableFuture<List<String>> {
        val recent = prefs.getStringSet("recent_list", emptySet())?.toList() ?: emptyList()
        return Futures.immediateFuture(recent)
    }

    override fun recordSelection(emoji: String) {
        //
    }

    @SuppressLint("UseKtx")
    fun addRecentEmoji(emoji: String) {
        val set = prefs.getStringSet("recent_list", LinkedHashSet())?.toMutableSet() ?: mutableSetOf()
        set.remove(emoji) // Remove if already present to avoid duplicates
        set.add(emoji)    // Add to end (most recent)
        // Limit to, e.g., 20 most recent
        val limited = LinkedHashSet(set.toList().takeLast(20))
        prefs.edit().putStringSet("recent_list", limited).apply()
    }
}