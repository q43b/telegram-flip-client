package com.q43b.tgflip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

// Gets a picture from the phone ready to send (F2 > Send photo in a chat), in one of two ways:
// - optimized: shrunk on the phone to at most MAX_SIDE pixels on the long side (the size
//   Telegram's own apps send photos at) and saved as JPEG, usually 150-400 KB, so it
//   uploads quickly through the slow proxy and the weak phone never holds a huge picture
// - original: the file's bytes unchanged, at most Telegram's 10 MB photo limit
// Reads files, so call from a background thread.
object PhotoSender {
    class Prepared(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int)

    // The original is over Telegram's photo limit
    class TooLargeException : IOException("Over 10 MB")

    private const val MAX_SIDE = 1280
    private const val JPEG_QUALITY = 85
    private const val MAX_ORIGINAL_BYTES = 10 * 1024 * 1024

    fun prepare(context: Context, uri: Uri, optimized: Boolean): Prepared =
        if (optimized) optimize(context, uri) else original(context, uri)

    private fun original(context: Context, uri: Uri): Prepared {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Can't read the photo")
        if (bytes.size > MAX_ORIGINAL_BYTES) throw TooLargeException()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return Prepared(bytes, resolver.getType(uri) ?: "image/jpeg", bounds.outWidth, bounds.outHeight)
    }

    private fun optimize(context: Context, uri: Uri): Prepared {
        val resolver = context.contentResolver
        fun open() = resolver.openInputStream(uri) ?: throw IOException("Can't read the photo")

        // First only the size, to decode at a fraction of it (a camera photo is ~12 MP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not a picture")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Not a picture")

        // Then to the exact size, turned the way the camera was held (EXIF), as one step
        val matrix = Matrix()
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        if (scale < 1f) matrix.postScale(scale, scale)
        val degrees = open().use { rotationOf(ExifInterface(it)) }
        if (degrees != 0) matrix.postRotate(degrees.toFloat())
        val bitmap = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val prepared = Prepared(out.toByteArray(), "image/jpeg", bitmap.width, bitmap.height)
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return prepared
    }

    private fun rotationOf(exif: ExifInterface) =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
