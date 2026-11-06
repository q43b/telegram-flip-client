package com.q43b.tgflip

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

// Gets a video from the phone ready to send (F2 > Send media > Send video in a chat).
// Telegram shows a video's length, shape and preview picture only when the sender gives them,
// so they are read here with Android's own MediaMetadataRetriever: the length, the size the
// way it is shown (turned sideways if the camera was), and the first frame as a small JPEG.
// The file itself is streamed from the picker's Uri while sending, never held in memory.
// Reads files, so call from a background thread.
object VideoSender {
    class Prepared(
        val uri: Uri,
        val size: Long,
        val seconds: Int,
        val width: Int,
        val height: Int,
        val thumb: ByteArray,  // may be empty when no frame could be read
    )

    // Over the server's limit
    class TooLargeException : IOException("Over 100 MB")

    const val MAX_BYTES = 100L * 1024 * 1024
    private const val THUMB_SIDE = 320  // Telegram's size for previews
    private const val THUMB_QUALITY = 70

    fun prepare(context: Context, uri: Uri): Prepared {
        val size = sizeOf(context, uri)
        if (size > MAX_BYTES) throw TooLargeException()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun number(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val ms = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
            var width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0) {
                width = height.also { height = width }
            }
            if (width <= 0 || height <= 0) throw IOException("Not a video")
            val thumb = retriever.getFrameAtTime(0)?.let(::thumbnail) ?: ByteArray(0)
            return Prepared(uri, size, (ms + 500) / 1000, width, height, thumb)
        } catch (e: RuntimeException) {  // the retriever throws these for files it can't read
            throw IOException("Not a video", e)
        } finally {
            retriever.release()
        }
    }

    // After sending: a copy in the media cache under the new message's name (as VideoActivity
    // looks for it), so playing what was just sent needs no download. Best effort: without
    // the copy it simply downloads like any other video.
    fun keepCopy(context: Context, uri: Uri, chatId: Long, messageId: Long) {
        val dir = MediaCache.folder(context, "videos")
        val file = File(dir, "${chatId}_$messageId.mp4")
        val part = File(file.path + ".part")
        try {
            dir.mkdirs()
            open(context, uri).use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (!part.renameTo(file)) part.delete()
        } catch (e: IOException) {
            part.delete()
        }
    }

    fun open(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read the video")

    private fun sizeOf(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        // Not every picker tells the size: count it
        return open(context, uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }

    // getFrameAtTime already applies the video's rotation
    private fun thumbnail(frame: Bitmap): ByteArray {
        val scale = THUMB_SIDE.toFloat() / max(frame.width, frame.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true)
        } else {
            frame
        }
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
        if (small !== frame) small.recycle()
        frame.recycle()
        return out.toByteArray()
    }
}
