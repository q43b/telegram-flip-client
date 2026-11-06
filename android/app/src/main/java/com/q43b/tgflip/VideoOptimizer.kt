package com.q43b.tgflip

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

// Shrinks a video on the phone before sending ("Optimized" when sending a video): at most
// SHORT_SIDE pixels on its short side, H.264 at about VIDEO_BITRATE, AAC sound, with Media3
// Transformer, which uses the phone's video chip. On the weak phone it takes about as long
// as the video plays. Much smaller files make the slow part, the server passing the video
// through the proxy to Telegram, much quicker.
@OptIn(UnstableApi::class)
object VideoOptimizer {
    private const val SHORT_SIDE = 480
    private const val VIDEO_BITRATE = 1_000_000  // bits per second: about 8 MB a minute with sound

    // Writes the smaller video to output; onProgress gets 0-100 now and then. Cancelling the
    // coroutine (e.g. leaving the chat) stops the work.
    suspend fun optimize(context: Context, input: Uri, output: File, onProgress: (Int) -> Unit) {
        val scaleDown = withContext(Dispatchers.IO) { shortSide(context, input) > SHORT_SIDE }
        output.delete()
        // Transformer works on a thread with a Looper: the main one
        withContext(Dispatchers.Main) {
            coroutineScope {
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build()
                            )
                            .build()
                    )
                    .build()
                val effects = if (scaleDown) Effects(listOf(), listOf(Presentation.createForShortSide(SHORT_SIDE)))
                else Effects.EMPTY
                val item = EditedMediaItem.Builder(MediaItem.fromUri(input)).setEffects(effects).build()

                val progress = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress)
                        }
                        delay(500)
                    }
                }
                try {
                    suspendCancellableCoroutine { continuation ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                continuation.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                continuation.resumeWithException(IOException("Couldn't optimize", exportException))
                            }
                        })
                        continuation.invokeOnCancellation { transformer.cancel() }
                        transformer.start(item, output.path)
                    }
                } finally {
                    progress.cancel()
                }
            }
        }
    }

    private fun shortSide(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun number(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            min(
                number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
            )
        } catch (e: RuntimeException) {
            throw IOException("Not a video", e)
        } finally {
            retriever.release()
        }
    }
}
