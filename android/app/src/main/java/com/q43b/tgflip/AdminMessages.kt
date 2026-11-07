package com.q43b.tgflip

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException

// The admin channel (since 1.0.0): admin-only news from the server (GET /admin_messages,
// empty for other accounts), first of all "a newer server version is out". No pop-up (owner):
// an unread dot leads the way instead, on the Settings tab, then on the version line in
// Settings, then on Under the hood > Messages from the server, where they are listed; opening
// that marks them read. The chat list asks whenever its live connection comes up and keeps the
// latest texts here, so every screen can show the dot without asking the server. Servers older
// than 1.0.0 have no such endpoint: nothing.
object AdminMessages {
    const val DOT = "●"
    private const val KEY_LATEST = "admin_messages_latest"
    private const val KEY_READ = "admin_messages_read"
    private const val KEEP_READ = 30

    // Asks the server; onChange runs (on the main thread) when it answered
    fun check(context: Context, api: ApiClient, scope: CoroutineScope, onChange: () -> Unit) {
        scope.launch {
            val messages = try {
                withContext(Dispatchers.IO) { api.getAdminMessages() }
            } catch (e: IOException) {
                return@launch  // no server right now: next time the connection comes up
            }
            save(context, KEY_LATEST, messages)
            onChange()
        }
    }

    // The latest admin messages not yet read
    fun unread(context: Context): Int {
        val read = load(context, KEY_READ)
        return load(context, KEY_LATEST).count { it !in read }
    }

    // Messages from the server showed these: they are read now (and the latest ones)
    fun markRead(context: Context, shown: List<String>) {
        save(context, KEY_LATEST, shown)
        save(context, KEY_READ, (load(context, KEY_READ) + shown).distinct().takeLast(KEEP_READ))
    }

    // "Settings" -> "Settings ●" while something is unread
    fun withDot(context: Context, text: String) = if (unread(context) > 0) "$text $DOT" else text

    private fun load(context: Context, key: String): List<String> {
        val json = prefs(context).getString(key, null) ?: return emptyList()
        return try {
            JSONArray(json).let { array -> List(array.length()) { array.getString(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, key: String, texts: List<String>) {
        prefs(context).edit().putString(key, JSONArray(texts).toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
