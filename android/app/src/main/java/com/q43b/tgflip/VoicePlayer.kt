package com.q43b.tgflip

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.Toast
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// Plays one voice message at a time in the chat screen. The file (OGG/Opus, as Telegram
// keeps it) comes from the media cache's "voice" folder, or from GET /voice the first
// time, and is saved there, so the Cache settings apply to it. While it plays,
// onChange runs every second, so the message line can count along.
// Playing does not tell the sender it was listened to.
class VoicePlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onChange: (messageId: Long) -> Unit,
) {
    var api: ApiClient? = null

    // The message being loaded or played, or null
    var currentId: Long? = null
        private set
    private var player: MediaPlayer? = null
    private var job: Job? = null

    // Seconds played so far; 0 while it still loads
    val position get() = (player?.currentPosition ?: 0) / 1000

    fun isLoading(messageId: Long) = currentId == messageId && player == null

    fun play(chatId: Long, messageId: Long) {
        stop()
        if (VoiceFormat.isCompatible(context)) {
            Toast.makeText(context, R.string.voice_compatible_later, Toast.LENGTH_LONG).show()
            return
        }
        currentId = messageId
        onChange(messageId)
        job = scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { file(chatId, messageId) }
            } catch (e: IOException) {
                fail(messageId, R.string.voice_not_loaded)
                return@launch
            }
            val started = try {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.path)
                    setOnCompletionListener { stop() }
                    prepare()  // a local file of a few hundred KB: quick
                    start()
                }
            } catch (e: Exception) {  // IOException, or an unsupported format on older Android
                fail(messageId, R.string.voice_cant_play)
                return@launch
            }
            player = started
            while (isActive) {
                // The phone doesn't always report the end (setOnCompletionListener), so check
                if (!started.isPlaying) {
                    stop()
                    break
                }
                onChange(messageId)
                delay(1000)
            }
        }
    }

    fun stop() {
        val id = currentId ?: return
        job?.cancel()
        job = null
        player?.release()
        player = null
        currentId = null
        onChange(id)
    }

    private fun fail(messageId: Long, message: Int) {
        if (currentId == messageId) stop()
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // The saved copy, or a fresh download that is then saved
    private fun file(chatId: Long, messageId: Long): File {
        val dir = MediaCache.folder(context, "voice")
        val file = File(dir, "${chatId}_$messageId.ogg")
        if (file.exists()) {
            MediaCache.touch(file)
            return file
        }
        val bytes = (api ?: throw IOException("No server")).getVoice(chatId, messageId)
        dir.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
        return file
    }
}
