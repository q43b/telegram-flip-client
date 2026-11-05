package com.q43b.tgflip

import android.content.Context
import java.io.File

// The phone's copies of downloaded media: today the profile photos, later pictures,
// stickers and voice messages, each kind in its own folder under cacheDir/media.
// Two limits, set in Settings > Cache: how long a file is kept since it was last used,
// and how much space all of it may take (the least recently used files go first).
// trim() applies them, at app start and when a limit changes. Text (chats, messages)
// is LocalCache's, and only "Delete all saved data" touches it.
// Reads and deletes files, so call from a background thread.
object MediaCache {
    private const val KEY_DAYS = "media_cache_days"
    private const val KEY_MB = "media_cache_mb"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    // The choices in the order Settings lists them; 0 = no limit
    val dayChoices = listOf(1, 7, 30, 90, 0)
    val mbChoices = listOf(10, 25, 50, 100, 0)

    fun folder(context: Context, kind: String) = File(root(context), kind)

    // "Last used" is the file's modified time: refresh it when a file is shown
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    fun keepDays(context: Context) = prefs(context).getInt(KEY_DAYS, 30)

    fun maxMb(context: Context) = prefs(context).getInt(KEY_MB, 50)

    fun saveKeepDays(context: Context, days: Int) = prefs(context).edit().putInt(KEY_DAYS, days).apply()

    fun saveMaxMb(context: Context, mb: Int) = prefs(context).edit().putInt(KEY_MB, mb).apply()

    fun sizeBytes(context: Context): Long = synchronized(this) {
        files(context).sumOf { it.length() }
    }

    fun trim(context: Context) = synchronized(this) {
        File(context.cacheDir, "photos").deleteRecursively()  // where 0.8 and 0.9.0 kept photos
        var files = files(context)
        val days = keepDays(context)
        if (days > 0) {
            val cutoff = System.currentTimeMillis() - days * DAY_MS
            val (old, kept) = files.partition { it.lastModified() < cutoff }
            old.forEach { it.delete() }
            files = kept
        }
        val maxBytes = maxMb(context) * 1024L * 1024
        if (maxBytes > 0) {
            var total = files.sumOf { it.length() }
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                total -= file.length()
                file.delete()
            }
        }
    }

    fun clear(context: Context) = synchronized(this) {
        root(context).deleteRecursively()
    }

    // "Delete all saved data": media, saved chats and messages, and anything else in the
    // app's cache folder. Settings, folders and quick phrases are not cache and stay.
    fun clearEverything(context: Context) = synchronized(this) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        LocalCache(context).clear()
    }

    private fun root(context: Context) = File(context.cacheDir, "media")

    private fun files(context: Context) = root(context).walkTopDown().filter { it.isFile }.toList()

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
