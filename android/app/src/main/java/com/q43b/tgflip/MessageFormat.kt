package com.q43b.tgflip

import android.text.format.DateUtils
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Time and text formatting shared by the chat list and the chat screen.
// Main thread only (SimpleDateFormat is not thread-safe).
object MessageFormat {
    // The server's date format (ISO 8601 with a time zone), e.g. 2026-11-05T14:05:00+00:00
    private val serverDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    // Digits only, so they don't depend on the phone's language (Locale.ROOT)
    private val timeOnly = SimpleDateFormat("HH:mm", Locale.ROOT)
    private val dayAndTime = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT)

    // "Now" in the server's format, for messages shown before the server confirms them
    fun now(): String = serverDate.format(Date())

    // "14:05" for today, "03.11 14:05" for older days, in the phone's time zone
    fun time(date: String): String {
        return try {
            // The server may add fractions of a second; the parser does not need them
            val parsed = serverDate.parse(date.replace(Regex("\\.\\d+"), "")) ?: return ""
            if (DateUtils.isToday(parsed.time)) timeOnly.format(parsed) else dayAndTime.format(parsed)
        } catch (e: ParseException) {
            ""
        }
    }

    // The start of a text on one line, ending in "…" if it was cut
    fun snippet(text: String, length: Int): String {
        val line = text.replace('\n', ' ')
        return if (line.length > length) line.take(length) + "…" else line
    }
}
