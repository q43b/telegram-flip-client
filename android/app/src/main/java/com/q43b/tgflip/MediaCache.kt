package com.q43b.tgflip

import android.content.Context
import java.io.File

// The phone's copies of downloaded media (profile photos, photos, videos, voice messages,
// stickers), each kind in its own folder under cacheDir/media.
// Two limits, set in Settings > Media and Cache: how long a file is kept since it was last
// used, and how much space all of it may take (the least recently used files go first).
// "Separate sticker cache" gives the stickers folder limits and clearing of its own; the
// media limits then leave it alone. trim() applies them, at app start and when a limit
// changes. Text (chats, messages) is LocalCache's, and only "Delete all saved data" touches it.
// Reads and deletes files, so call from a background thread.
object MediaCache {
    private const val KEY_DAYS = "media_cache_days"
    private const val KEY_MB = "media_cache_mb"
    private const val KEY_SEPARATE = "sticker_cache_separate"
    private const val KEY_STICKER_DAYS = "sticker_cache_days"
    private const val KEY_STICKER_MB = "sticker_cache_mb"
    private const val STICKERS = "stickers"
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

    fun separateStickers(context: Context) = prefs(context).getBoolean(KEY_SEPARATE, false)

    fun saveSeparateStickers(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SEPARATE, on).apply()

    // The stickers' own limits (used while separate): by default 3 months and 20 MB
    fun stickerKeepDays(context: Context) = prefs(context).getInt(KEY_STICKER_DAYS, 90)

    fun stickerMaxMb(context: Context) = prefs(context).getInt(KEY_STICKER_MB, 20)

    fun saveStickerKeepDays(context: Context, days: Int) = prefs(context).edit().putInt(KEY_STICKER_DAYS, days).apply()

    fun saveStickerMaxMb(context: Context, mb: Int) = prefs(context).edit().putInt(KEY_STICKER_MB, mb).apply()

    // The media files the media limits cover (all of them, or all but stickers while separate)
    fun sizeBytes(context: Context): Long = synchronized(this) {
        mediaFiles(context).sumOf { it.length() }
    }

    fun stickerBytes(context: Context): Long = synchronized(this) {
        stickerFiles(context).sumOf { it.length() }
    }

    fun trim(context: Context) = synchronized(this) {
        File(context.cacheDir, "photos").deleteRecursively()  // where 0.8 and 0.9.0 kept photos
        trimFiles(mediaFiles(context), keepDays(context), maxMb(context))
        if (separateStickers(context)) trimFiles(stickerFiles(context), stickerKeepDays(context), stickerMaxMb(context))
    }

    // Older than days since last used go first, then the least recently used beyond mb
    private fun trimFiles(all: List<File>, days: Int, mb: Int) {
        var files = all
        if (days > 0) {
            val cutoff = System.currentTimeMillis() - days * DAY_MS
            val (old, kept) = files.partition { it.lastModified() < cutoff }
            old.forEach { it.delete() }
            files = kept
        }
        val maxBytes = mb * 1024L * 1024
        if (maxBytes > 0) {
            var total = files.sumOf { it.length() }
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                total -= file.length()
                file.delete()
            }
        }
    }

    // The media cache; stickers too unless they are separate
    fun clear(context: Context) = synchronized(this) {
        if (separateStickers(context)) {
            root(context).listFiles()?.filter { it.name != STICKERS }?.forEach { it.deleteRecursively() }
        } else {
            root(context).deleteRecursively()
        }
    }

    // Under the hood > Local cache (since 1.3.0): one folder per kind of file, and deleting one
    fun kindFolders(context: Context): List<File> = root(context).listFiles()?.filter { it.isDirectory }.orEmpty()

    fun deleteKind(context: Context, kind: String) = synchronized(this) {
        folder(context, kind).deleteRecursively()
    }

    fun clearStickers(context: Context) = synchronized(this) {
        folder(context, STICKERS).deleteRecursively()
    }

    // "Delete all saved data": media, saved chats and messages, and anything else in the
    // app's cache folder. Settings, folders and quick phrases are not cache and stay.
    fun clearEverything(context: Context) = synchronized(this) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        LocalCache(context).clear()
    }

    private fun root(context: Context) = File(context.cacheDir, "media")

    private fun files(context: Context) = root(context).walkTopDown().filter { it.isFile }.toList()

    private fun stickerFiles(context: Context) = folder(context, STICKERS).walkTopDown().filter { it.isFile }.toList()

    private fun mediaFiles(context: Context): List<File> {
        if (!separateStickers(context)) return files(context)
        val stickers = folder(context, STICKERS)
        return files(context).filter { it.parentFile != stickers }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
