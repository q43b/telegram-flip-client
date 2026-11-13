package com.q43b.tgflip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// GIFs in a chat (since 1.9.0): the still picture shows in the media row, and while that message has the
// focus the GIF itself plays there, looping and silent. Telegram keeps GIFs as small MP4 videos, so the
// file is the same as a video's (media cache "videos", <chat>_<message>.mp4, also used by VideoActivity);
// the still picture comes from GET /thumb and is kept in "gifs". Both are fetched in the background;
// until then null, and onLoaded redraws the list. Like StickerLoader, call it from the main thread.
class GifLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLoaded: () -> Unit,
) {
    var api: Backend? = null

    private val thumbs = MediaCache.folder(context, "gifs")
    private val videos = MediaCache.folder(context, "videos")
    private val memory = LruCache<String, Bitmap>(30)
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val downloads = Semaphore(2)

    fun thumb(chatId: Long, messageId: Long): Drawable? {
        val key = "${chatId}_$messageId"
        memory.get(key)?.let { return BitmapDrawable(context.resources, it) }
        if (key in loading || key in failed) return null
        val api = api ?: return null
        loading += key
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                downloads.withPermit {
                    try {
                        val file = File(thumbs, "${key}_thumb.jpg")
                        if (!file.exists()) {
                            val data = api.getThumb(chatId, messageId, THUMB_PX) ?: return@withPermit null
                            thumbs.mkdirs()
                            file.writeBytes(data)
                        } else {
                            MediaCache.touch(file)
                        }
                        BitmapFactory.decodeFile(file.path)
                    } catch (e: IOException) {
                        null
                    }
                }
            }
            loading -= key
            if (bitmap == null) failed += key else memory.put(key, bitmap)
            onLoaded()
        }
        return null
    }

    // The GIF's MP4 once it is on the phone; the first call starts the download
    fun video(chatId: Long, messageId: Long): File? {
        val file = File(videos, "${chatId}_$messageId.mp4")
        if (file.exists()) return file
        val key = "v${chatId}_$messageId"
        if (key in loading || key in failed) return null
        val api = api ?: return null
        loading += key
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                downloads.withPermit {
                    val part = File(file.path + ".part")
                    try {
                        videos.mkdirs()
                        api.download(Download.Kind.VIDEO, chatId, messageId).saveTo(part) { _, _ -> }
                        part.renameTo(file)
                    } catch (e: IOException) {
                        part.delete()
                        false
                    }
                }
            }
            loading -= key
            if (!ok) failed += key
            onLoaded()
        }
        return null
    }

    companion object {
        const val THUMB_PX = 256
    }
}

// Plays one GIF at a time in a TextureView (a SurfaceView can't sit inside a list row): looping, silent,
// on the phone's own video decoder. show() again with the same view and file does nothing, so the list can
// redraw freely; stop() when the GIF loses the focus or the screen goes away.
class GifPlayer {
    private var player: MediaPlayer? = null
    private var view: TextureView? = null
    private var file: File? = null

    fun show(view: TextureView, file: File) {
        if (this.view === view && this.file == file) return
        stop()
        this.view = view
        this.file = file
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (this@GifPlayer.view === view) start(texture)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (this@GifPlayer.view === view) stop()
                return true
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
        view.surfaceTexture?.let(::start)
    }

    // A row being reused for something else lets go of the GIF it showed
    fun releaseIfOn(view: TextureView) {
        if (this.view === view) stop()
    }

    fun stop() {
        player?.release()
        player = null
        view = null
        file = null
    }

    private fun start(texture: SurfaceTexture) {
        val path = file?.path ?: return
        player?.release()
        player = try {
            MediaPlayer().apply {
                setSurface(Surface(texture))
                setDataSource(path)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> true }  // can't play it: the still picture stays
                prepareAsync()
            }
        } catch (e: Exception) {
            null
        }
    }
}
