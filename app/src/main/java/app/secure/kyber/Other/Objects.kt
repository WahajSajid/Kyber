package app.secure.kyber.Other

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Objects {
    fun formatTimestamp(timestampMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestampMillis

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestampMillis

        val todayCalendar = Calendar.getInstance()
        todayCalendar.timeInMillis = now

        val yesterdayCalendar = Calendar.getInstance()
        yesterdayCalendar.timeInMillis = now
        yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)

        return when {
            // Today - show time (10:44 AM)
            isSameDay(calendar, todayCalendar) -> {
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestampMillis))
            }

            // Yesterday
            isSameDay(calendar, yesterdayCalendar) -> {
                "Yesterday"
            }

            // Within last 7 days - show day name (Monday, Tuesday, etc.)
            diff < TimeUnit.DAYS.toMillis(7) -> {
                SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(timestampMillis))
            }

            // Within same year - show date without year (Dec 20)
            calendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR) -> {
                SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestampMillis))
            }

            // Older - show full date (Dec 20, 2024)
            else -> {
                SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestampMillis))
            }
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }


}