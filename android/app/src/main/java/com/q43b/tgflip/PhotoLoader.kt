package com.q43b.tgflip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
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

// Profile photos of chats and people (a person's id is also their private chat's id),
// as round drawables sizeDp across: 32 in the chat list, bigger on the profile screen.
// Looks in memory, then on disk (the app's cache folder, so they show offline too),
// then asks the server, at most two at a time. drawable() never waits: until a photo
// is there it returns a letter circle, and onLoaded runs when a photo arrives, so the
// screen can redraw. Files are named <chat id>_<photo id>_<px>.jpg; a new photo id
// means a new photo, and the chat's older files are removed.
// Only touched from the main thread (scope is the screen's).
class PhotoLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    sizeDp: Int = LIST_SIZE_DP,
    private val onLoaded: () -> Unit,
) {
    // A new server (or none): photos that failed may work now
    var api: ApiClient? = null
        set(value) {
            field = value
            failed.clear()
        }

    private val dir = File(context.cacheDir, "photos")
    private val sizePx = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceIn(16, 160)
    private val memory = LruCache<String, Bitmap>(MEMORY_PHOTOS)
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()  // no photo, or no server: don't ask again for now
    private val downloads = Semaphore(MAX_DOWNLOADS)

    fun drawable(chat: Chat): Drawable = drawable(chat.id, chat.name, chat.photo)

    // photo = the photo id from the server, null for no photo (or not known yet)
    fun drawable(id: Long, name: String, photo: String?): Drawable {
        val bitmap = bitmap(id, photo) ?: return LetterAvatar(id, name)
        return RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply { isCircular = true }
    }

    private fun bitmap(id: Long, photo: String?): Bitmap? {
        photo ?: return null
        val key = "${id}_${photo}_$sizePx"
        memory.get(key)?.let { return it }
        if (key in loading || key in failed) return null
        loading += key
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fromDisk(key) ?: download(id, photo, key) }
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
        val file = File(dir, "$key.jpg")
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }

    private suspend fun download(chatId: Long, photo: String, key: String): Bitmap? {
        val api = api ?: return null
        val bytes = try {
            downloads.withPermit { api.getPhoto(chatId, photo, sizePx) }
        } catch (e: IOException) {
            null
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            dir.mkdirs()
            // This chat's older photos; other sizes of this one stay
            dir.listFiles { f -> f.name.startsWith("${chatId}_") && !f.name.startsWith("${chatId}_${photo}_") }
                ?.forEach { it.delete() }
            val file = AtomicFile(File(dir, "$key.jpg"))
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
        const val LIST_SIZE_DP = 32  // as in chat_row.xml
        const val PROFILE_SIZE_DP = 96  // as in activity_profile.xml
        private const val MEMORY_PHOTOS = 200  // at 48×48 px about 9 KB each
        private const val MAX_DOWNLOADS = 2
    }
}

// The stand-in while a photo loads, or for chats without one: a colored circle with
// the first letter of the name, like the official app. The color follows the id, so a
// person has the same color in the list and on their profile.
class LetterAvatar(id: Long, name: String) : Drawable() {
    private val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLORS[(id % COLORS.size).toInt().let { if (it < 0) -it else it }]
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val radius = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, circle)
        text.textSize = radius
        // Centered vertically: the baseline goes half the letter's height below the middle
        val y = b.exactCenterY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(letter, b.exactCenterX(), y, text)
    }

    override fun setAlpha(alpha: Int) {
        circle.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        circle.colorFilter = colorFilter
        text.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        val COLORS = intArrayOf(
            0xFFE17076.toInt(), 0xFFF5A35B.toInt(), 0xFFA695E7.toInt(), 0xFF7BC862.toInt(),
            0xFF6EC9CB.toInt(), 0xFF65AADD.toInt(), 0xFFEE7AAE.toInt(),
        )
    }
}
