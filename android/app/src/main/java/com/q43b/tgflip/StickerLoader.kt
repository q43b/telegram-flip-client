package com.q43b.tgflip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

// Sticker pictures (still PNGs from GET /sticker), sizeDp across, like PhotoLoader: memory,
// then disk (the media cache's "stickers" folder, so the Cache limits apply), then the
// server, at most two at a time. drawable() never waits: it returns null until the picture
// is there, and onLoaded runs when one arrives, so the screen can redraw. Files are named
// <sticker id>_<px>.png; a sticker never changes. Only touched from the main thread.
class StickerLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    sizeDp: Int = CHAT_SIZE_DP,
    private val onLoaded: () -> Unit,
) {
    var api: ApiClient? = null

    private val dir = MediaCache.folder(context, "stickers")
    private val sizePx = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceIn(32, 512)
    private val memory = LruCache<String, Bitmap>(MEMORY_STICKERS)
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()  // nothing to show, or no server: don't ask again
    private val downloads = Semaphore(MAX_DOWNLOADS)

    // chatId and messageId say where the sticker came from (the server may need to look again)
    fun drawable(sticker: StickerInfo, chatId: Long, messageId: Long): Drawable? {
        val key = "${sticker.id}_$sizePx"
        memory.get(key)?.let { return BitmapDrawable(context.resources, it) }
        if (key in loading || key in failed) return null
        loading += key
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fromDisk(key) ?: download(sticker.id, chatId, messageId, key) }
            loading -= key
            if (bitmap == null) {
                failed += key
            } else {
                memory.put(key, bitmap)
                onLoaded()
            }
        }
        return null
    }

    private fun fromDisk(key: String): Bitmap? {
        val file = File(dir, "$key.png")
        if (!file.exists()) return null
        MediaCache.touch(file)
        return BitmapFactory.decodeFile(file.path)
    }

    private suspend fun download(id: Long, chatId: Long, messageId: Long, key: String): Bitmap? {
        val api = api ?: return null
        val bytes = try {
            downloads.withPermit { api.getSticker(id, sizePx, chatId, messageId) }
        } catch (e: IOException) {
            null
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            dir.mkdirs()
            val file = AtomicFile(File(dir, "$key.png"))
            val out = file.startWrite()
            try {
                out.write(bytes)
                file.finishWrite(out)
            } catch (e: IOException) {
                file.failWrite(out)
            }
        } catch (e: IOException) {
            // Not saved: it still shows, and is fetched again next time
        }
        return bitmap
    }

    companion object {
        const val CHAT_SIZE_DP = 96  // as in message_row.xml
        private const val MEMORY_STICKERS = 40  // at 144×144 px about 80 KB each
        private const val MAX_DOWNLOADS = 2
    }
}
