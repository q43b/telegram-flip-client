package com.q43b.tgflip

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Time, text and call formatting shared by the chat list and the chat screen.
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

    // "☎ Missed call", "☎ Outgoing video call · 2:31", ... out = I made the call
    fun call(context: Context, call: CallInfo, out: Boolean): String {
        val what = context.getString(if (call.video) R.string.call_video else R.string.call_voice)
        val duration = call.duration
        val text = when {
            duration != null && duration > 0 -> context.getString(
                if (out) R.string.call_outgoing else R.string.call_incoming, what
            ) + " · " + duration(duration)
            call.reason == "missed" ->
                context.getString(if (out) R.string.call_cancelled else R.string.call_missed, what)
            call.reason == "busy" ->
                context.getString(if (out) R.string.call_not_answered else R.string.call_declined, what)
            else -> context.getString(if (out) R.string.call_outgoing else R.string.call_incoming, what)
        }
        return "☎ $text"
    }

    // "🖼 Photo", or "🖼 Photo: caption"
    fun photo(context: Context, caption: String): String {
        val label = "🖼 ${context.getString(R.string.photo)}"
        return if (caption.isEmpty()) label else "$label: $caption"
    }

    // "🎬 Video 0:45 · 12 MB", or with ": caption"; in the chat list only "🎬 Video"
    fun video(context: Context, video: VideoInfo?, caption: String): String {
        var label = "🎬 ${context.getString(R.string.video)}"
        if (video != null) {
            label += " ${duration(video.duration)} · ${Formatter.formatShortFileSize(context, video.size)}"
        }
        return if (caption.isEmpty()) label else "$label: $caption"
    }

    // "📄 File: report.docx · 1.2 MB", or with " - caption"; in the chat list (short) "📄 File .docx"
    fun file(context: Context, file: FileInfo?, caption: String, short: Boolean = false): String {
        var label = "📄 ${context.getString(R.string.file)}"
        if (file != null) {
            if (short) {
                if (file.ext.isNotEmpty()) label += " ${file.ext}"
            } else {
                val shown = file.name.ifEmpty { file.ext }
                if (shown.isNotEmpty()) label += ": $shown"
                if (file.size > 0) label += " · ${Formatter.formatShortFileSize(context, file.size)}"
            }
        }
        return if (caption.isEmpty()) label else "$label - $caption"
    }

    // "🎞 GIF"
    fun gif(context: Context) = "🎞 ${context.getString(R.string.gif)}"

    // "📊 Anonymous poll · multiple choice · closed · ✓: question" (✓ = I voted); without the poll's
    // details (the chat list's preview) just "📊 Poll: question"
    fun poll(context: Context, poll: PollInfo?, question: String): String {
        if (poll == null) return "📊 ${context.getString(R.string.poll)}: $question"
        val kind = context.getString(when {
            poll.quiz && poll.public -> R.string.poll_quiz_public
            poll.quiz -> R.string.poll_quiz_anonymous
            poll.public -> R.string.poll_public
            else -> R.string.poll_anonymous
        })
        var label = "📊 $kind"
        if (poll.multiple) label += " · ${context.getString(R.string.poll_multiple)}"
        if (poll.closed) label += " · ${context.getString(R.string.poll_closed)}"
        if (poll.voted) label += " · ✓"
        return "$label: $question"
    }

    // "📌 pinned a message"
    fun pinned(context: Context) = "📌 ${context.getString(R.string.pinned_a_message)}"

    // "🏷 Sticker 😂" (the emoji it stands for)
    fun sticker(context: Context, emoji: String): String {
        val label = "🏷 ${context.getString(R.string.sticker)}"
        return if (emoji.isEmpty()) label else "$label $emoji"
    }

    // "⭕ Video message 0:15"; in the chat list only "⭕ Video message"
    fun round(context: Context, round: RoundInfo?): String {
        val label = "⭕ ${context.getString(R.string.round_message)}"
        return if (round == null) label else "$label ${duration(round.duration)}"
    }

    // "🎤 Voice message 0:12"
    fun voice(context: Context, voice: VoiceInfo) =
        "🎤 ${context.getString(R.string.voice_message)} ${duration(voice.duration)}"

    // While it plays: "▶ 0:05 / 0:12"
    fun voicePlaying(voice: VoiceInfo, position: Int) =
        "▶ ${duration(position)} / ${duration(voice.duration)}"

    // A call someone made to me that I didn't take: shown in red instead of blue
    fun isUnanswered(call: CallInfo, out: Boolean) =
        !out && (call.reason == "missed" || call.reason == "busy")

    // 2:31, or 1:02:31 for an hour or more
    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
