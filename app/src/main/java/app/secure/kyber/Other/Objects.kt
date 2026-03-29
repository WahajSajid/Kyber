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

        // Negative diff = future timestamp (clock skew) → show time
        if (diff < 0) {
            return SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(Date(timestampMillis))
        }

        val msgCal = Calendar.getInstance().apply { timeInMillis = timestampMillis }
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }

        val isSameYear = msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)

        return when {
            // Today → "3:45 PM"
            isSameDay(msgCal, nowCal) -> {
                SimpleDateFormat("h:mm a", Locale.getDefault())
                    .format(Date(timestampMillis))
            }

            // Yesterday → "Yesterday"
            isYesterday(msgCal, nowCal) -> "Yesterday"

            // 2–6 days ago → weekday name e.g. "Monday"
            diff < TimeUnit.DAYS.toMillis(7) -> {
                SimpleDateFormat("EEEE", Locale.getDefault())
                    .format(Date(timestampMillis))
            }

            // 7+ days, same year → "12 Mar"
            isSameYear -> {
                SimpleDateFormat("d MMM", Locale.getDefault())
                    .format(Date(timestampMillis))
            }

            // Different year → "12 Mar 2024"
            else -> {
                SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                    .format(Date(timestampMillis))
            }
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean =
        cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(msgCal: Calendar, nowCal: Calendar): Boolean {
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = nowCal.timeInMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameDay(msgCal, yesterday)
    }


}