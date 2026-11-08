package com.q43b.tgflip

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.io.InputStream

// Files as documents, both ways (F2 > Send media > Send file, and OK > Download file on a
// file message). Sending: reads the chosen file's name and size; the file itself is streamed
// from the picker's Uri while sending, never held in memory. Receiving: saves what the server
// fetched into the phone's Downloads/TG Flip folder through the media store, which needs no
// permission from Android 10 on. Reads and writes files, so call from a background thread.
object FileSender {
    class Prepared(val uri: Uri, val name: String, val size: Long)

    // Over the server's limit
    class TooLargeException : IOException("Over 100 MB")

    const val MAX_BYTES = 100L * 1024 * 1024
    private const val SAVE_FOLDER = "TG Flip"

    fun prepare(context: Context, uri: Uri): Prepared {
        var name = ""
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0)
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (name.isEmpty()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifEmpty { "file" }
        // Not every picker tells the size: count it
        if (size < 0) size = open(context, uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
        if (size > MAX_BYTES) throw TooLargeException()
        return Prepared(uri, name, size)
    }

    fun open(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read the file")

    // Copies the downloaded file into Downloads/TG Flip. Marked pending while it is written, so
    // no file manager shows half a file; the media store renames a name that is already taken.
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context, source: File, name: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't save files")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Can't save files")
            out.use { output -> source.inputStream().use { it.copyTo(output, 64 * 1024) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
