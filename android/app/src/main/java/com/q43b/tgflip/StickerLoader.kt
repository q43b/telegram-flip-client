package com.q43b.tgflip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
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
import java.nio.ByteBuffer
import kotlin.math.roundToInt

// Sticker pictures (still PNGs from GET /sticker), sizeDp across, like PhotoLoader: memory,
// then disk (the media cache's "stickers" folder, so the Cache limits apply), then the
// server, at most two at a time. drawable() never waits: it returns null until the picture
// is there, and onLoaded runs when one arrives, so the screen can redraw. Files are named
// <sticker id>_<px>.png; a sticker never changes. Only touched from the main thread.
//
// With plays = true (Settings > Stickers > Animated, Android 9+; since 0.16.0-beta) animated and
// video stickers are asked for as animated WebP (<id>_<px>_anim.webp) and come back as playing
// AnimatedImageDrawables. If the server answers with a still PNG instead (no animated stickers
// module, or an older server), that is used as the still picture. pause()/resume() stop and
// start them all, for when the screen isn't shown.
class StickerLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    sizeDp: Int = CHAT_SIZE_DP,
    private val plays: Boolean = false,
    animatedKept: Int = MEMORY_ANIMATED,  // how many playing stickers stay loaded (the picker's "all play" mode needs more)
    private val onLoaded: () -> Unit,
) {
    var api: Backend? = null

    private val dir = MediaCache.folder(context, "stickers")
    private val sizePx = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceIn(32, 512)
    private val memory = LruCache<String, Bitmap>(MEMORY_STICKERS)
    private val playing = object : LruCache<String, Drawable>(animatedKept) {
        override fun entryRemoved(evicted: Boolean, key: String, old: Drawable, new: Drawable?) {
            (old as? Animatable)?.stop()
        }
    }
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()  // nothing to show, or no server: don't ask again
    private val stillOnly = mutableSetOf<Long>()  // the server sent a still picture instead
    private val downloads = Semaphore(MAX_DOWNLOADS)
    private var paused = false

    // chatId and messageId say where a sticker came from (the server may need to look again);
    // null for a custom emoji, which the server can always find by its id. kind ("static",
    // "animated", "video") says whether it can move; null = treat as still.
    fun drawable(id: Long, chatId: Long?, messageId: Long?, kind: String? = null): Drawable? {
        if (plays && (kind == "animated" || kind == "video") && id !in stillOnly) {
            return animated(id, chatId, messageId)
        }
        val key = "${id}_$sizePx"
        memory.get(key)?.let { return BitmapDrawable(context.resources, it) }
        if (key in loading || key in failed) return null
        loading += key
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fromDisk(key) ?: download(id, chatId, messageId, key) }
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

    fun pause() {
        paused = true
        playing.snapshot().values.forEach { (it as? Animatable)?.stop() }
    }

    fun resume() {
        paused = false
        playing.snapshot().values.forEach { (it as? Animatable)?.start() }
    }

    private fun animated(id: Long, chatId: Long?, messageId: Long?): Drawable? {
        val key = "${id}_${sizePx}_anim"
        playing.get(key)?.let { return it }
        if (key in loading || key in failed) return null
        loading += key
        scope.launch {
            val drawable = withContext(Dispatchers.IO) { animatedFromDisk(key) ?: downloadAnimated(id, chatId, messageId, key) }
            loading -= key
            when {
                drawable != null -> {
                    playing.put(key, drawable)
                    if (!paused) (drawable as? Animatable)?.start()
                }
                id in stillOnly -> {}  // the still picture is in memory now
                else -> failed += key
            }
            onLoaded()
        }
        return null
    }

    @SuppressLint("NewApi")  // only reached with plays = true, which needs Android 9 (StickerSetting.plays)
    private fun decodeAnimated(bytes: ByteArray): Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    } catch (e: IOException) {
        null
    }

    private fun animatedFromDisk(key: String): Drawable? {
        val file = File(dir, "$key.webp")
        if (!file.exists()) return null
        MediaCache.touch(file)
        return decodeAnimated(file.readBytes())
    }

    // On the IO thread. A PNG answer (no module on the server, or an older server) becomes the
    // still picture, and this sticker isn't asked for animated again on this screen.
    private suspend fun downloadAnimated(id: Long, chatId: Long?, messageId: Long?, key: String): Drawable? {
        val api = api ?: return null
        val bytes = try {
            downloads.withPermit { api.getSticker(id, sizePx, chatId, messageId, animated = true) }
        } catch (e: IOException) {
            null
        } ?: return null
        if (!isWebp(bytes)) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val stillKey = "${id}_$sizePx"
            save(bytes, "$stillKey.png")
            withContext(Dispatchers.Main) {
                memory.put(stillKey, bitmap)
                stillOnly += id
            }
            return null
        }
        save(bytes, "$key.webp")
        return decodeAnimated(bytes)
    }

    private fun isWebp(bytes: ByteArray) = bytes.size > 12 &&
        String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun fromDisk(key: String): Bitmap? {
        val file = File(dir, "$key.png")
        if (!file.exists()) return null
        MediaCache.touch(file)
        return BitmapFactory.decodeFile(file.path)
    }

    private suspend fun download(id: Long, chatId: Long?, messageId: Long?, key: String): Bitmap? {
        val api = api ?: return null
        val bytes = try {
            downloads.withPermit { api.getSticker(id, sizePx, chatId, messageId) }
        } catch (e: IOException) {
            null
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        save(bytes, "$key.png")
        return bitmap
    }

    private fun save(bytes: ByteArray, name: String) {
        try {
            dir.mkdirs()
            val file = AtomicFile(File(dir, name))
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
    }

    companion object {
        const val CHAT_SIZE_DP = 96  // as in message_row.xml
        const val EMOJI_SIZE_DP = 20  // custom emoji inside text, about the text's height
        private const val MEMORY_STICKERS = 40  // at 144×144 px about 80 KB each
        private const val MEMORY_ANIMATED = 12  // playing ones keep a decoder and two frames each
        private const val MAX_DOWNLOADS = 2
    }
}
