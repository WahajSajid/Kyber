package app.secure.kyber.Utils

import app.secure.kyber.dataClasses.DocumentItem
import app.secure.kyber.dataClasses.LinkItem
import app.secure.kyber.dataClasses.MediaItem
import app.secure.kyber.dataClasses.SectionDocument
import app.secure.kyber.dataClasses.SectionLink
import app.secure.kyber.dataClasses.SectionMedia

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

object DateUtils {
    private fun toLocalDate(millis: Long): LocalDate {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    }


    //Media item categorization
    fun categorizeMediaItem(item: MediaItem): String {
        val itemDate = toLocalDate(item.timestampMillis)
        val today = LocalDate.now()
        return when {
            itemDate.isEqual(today) -> "Today"
            itemDate.isEqual(today.minusDays(1)) -> "Yesterday"
            itemDate.isAfter(today.minusWeeks(1)) -> "Last week"
            else -> {
                val month = itemDate.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                "$month ${itemDate.year}"
            }
        }
    }
    fun groupIntoMediaSections(items: List<MediaItem>): List<SectionMedia> {
        val map = linkedMapOf<String, MutableList<MediaItem>>() // preserve insertion order
        val sorted = items.sortedByDescending { it.timestampMillis }

        for (it in sorted) {
            val key = categorizeMediaItem(it)
            if (!map.containsKey(key)) map[key] = mutableListOf()
            map[key]!!.add(it)
        }

        return map.map { (k, v) -> SectionMedia(k, v) }
    }





    //Link Item categorization
    fun categorizeLinkItem(item: LinkItem): String {
        val itemDate = toLocalDate(item.timestampMillis)
        val today = LocalDate.now()
        return when {
            itemDate.isEqual(today) -> "Today"
            itemDate.isEqual(today.minusDays(1)) -> "Yesterday"
            itemDate.isAfter(today.minusWeeks(1)) -> "Last week"
            else -> {
                val month = itemDate.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                "$month ${itemDate.year}"
            }
        }
    }
    fun groupIntoLinksSections(items: List<LinkItem>): List<SectionLink> {
        val map = linkedMapOf<String, MutableList<LinkItem>>() // preserve insertion order
        val sorted = items.sortedByDescending { it.timestampMillis }

        for (it in sorted) {
            val key = categorizeLinkItem(it)
            if (!map.containsKey(key)) map[key] = mutableListOf()
            map[key]!!.add(it)
        }

        return map.map { (k, v) -> SectionLink(k, v) }
    }





    //Document Item categorization
    fun categorizeDocumentItem(item: DocumentItem): String {
        val itemDate = toLocalDate(item.timestampMillis)
        val today = LocalDate.now()
        return when {
            itemDate.isEqual(today) -> "Today"
            itemDate.isEqual(today.minusDays(1)) -> "Yesterday"
            itemDate.isAfter(today.minusWeeks(1)) -> "Last week"
            else -> {
                val month = itemDate.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                "$month ${itemDate.year}"
            }
        }
    }
    fun groupIntoDocumentSections(items: List<DocumentItem>): List<SectionDocument> {
        val map = linkedMapOf<String, MutableList<DocumentItem>>() // preserve insertion order
        val sorted = items.sortedByDescending { it.timestampMillis }

        for (it in sorted) {
            val key = categorizeDocumentItem(it)
            if (!map.containsKey(key)) map[key] = mutableListOf()
            map[key]!!.add(it)
        }

        return map.map { (k, v) -> SectionDocument(k, v) }
    }

    fun getRelativeTimeSpan(timeMillis: Long): String {
        if (timeMillis == 0L) return "never"
        
        val diff = System.currentTimeMillis() - timeMillis
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)

        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "1 min ago"
            minutes < 60 -> "$minutes mins ago"
            hours == 1L -> "1 hour ago"
            hours < 24 -> "$hours hours ago"
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
    }

    fun getChatSeparatorDate(timestampMillis: Long): String {
        val itemDate = toLocalDate(timestampMillis)
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        return when {
            itemDate.isEqual(today) -> "Today"
            itemDate.isEqual(yesterday) -> "Yesterday"
            itemDate.isAfter(today.minusDays(7)) -> {
                itemDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            }
            else -> {
                val formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault())
                itemDate.format(formatter)
            }
        }
    }

    fun isSameDay(time1: Long, time2: Long): Boolean {
        if (time1 == 0L || time2 == 0L) return false
        val d1 = toLocalDate(time1)
        val d2 = toLocalDate(time2)
        return d1.isEqual(d2)
    }
}