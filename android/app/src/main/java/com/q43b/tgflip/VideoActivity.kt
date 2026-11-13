package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// A video from a message, opened with "Play video" in the message menu. The whole file is
// downloaded first (GET /video; the server first gets it from Telegram), into the media
// cache's "videos" folder, then played from there: smooth on a weak phone, instant to
// replay, works offline. OK = pause/play, Left/Right = back/forward 10 s, the volume keys
// set the loudness, Back closes. Round video messages play here too, under a round mask.
class VideoActivity : AppCompatActivity() {
    private lateinit var video: VideoView
    private lateinit var status: TextView
    private lateinit var time: TextView
    private var download: Download? = null
    private var ticker: Job? = null
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_video)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        video = findViewById(R.id.video)
        status = findViewById(R.id.status)
        if (intent.getBooleanExtra(EXTRA_ROUND, false)) {
            // The mask always covers exactly the video: same place (both centered), same size
            val mask = findViewById<View>(R.id.round_mask)
            mask.background = RoundMaskDrawable()
            video.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (mask.layoutParams.width != v.width || mask.layoutParams.height != v.height) {
                    mask.layoutParams = mask.layoutParams.apply {
                        width = v.width
                        height = v.height
                    }
                }
                mask.visibility = if (v.width > 0) View.VISIBLE else View.GONE
            }
        }
        time = findViewById(R.id.time)
        SoftKeyBar(this).apply {  // nothing on the soft keys here: keep the phone's boxes empty
            setLeft(null)
            setRight(null)
        }

        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0)
        val settings = ServerSettings(this)
        val api = Backends.create(this)
        val file = File(MediaCache.folder(this, "videos"), "${chatId}_$messageId.mp4")
        if (file.exists()) {
            MediaCache.touch(file)
            play(file)
        } else {
            fetch(api, chatId, messageId, file)
        }
    }

    override fun onStop() {
        if (ready) video.pause()
        super.onStop()
    }

    override fun onDestroy() {
        download?.cancel()  // a download still running stops with the screen
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when {
            event.isOkKey() -> ::togglePause
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { -> seekBy(-SEEK_MS) }
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { -> seekBy(SEEK_MS) }
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && ready) action()
        return true
    }

    // First the server gets it from Telegram (no progress to show), then it comes to the
    // phone, counted against its size. Written to a .part file, renamed when complete.
    private fun fetch(api: Backend, chatId: Long, messageId: Long, file: File) {
        status.setText(R.string.video_server_fetching)
        val part = File(file.path + ".part")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    file.parentFile?.mkdirs()
                    api.download(Download.Kind.VIDEO, chatId, messageId).also { download = it }
                        .saveTo(part) { done, total -> showProgress(done, total) }
                    if (!part.renameTo(file)) throw IOException("Can't save the video")
                    null
                } catch (e: TooBigException) {
                    R.string.video_too_big
                } catch (e: IOException) {
                    part.delete()
                    R.string.video_not_loaded
                }
            }
            download = null
            if (result == null) play(file) else status.setText(result)
        }
    }

    private var lastProgress = 0L

    // Called from the download thread; at most a few redraws a second
    private fun showProgress(done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgress < 300 && done < total) return
        lastProgress = now
        runOnUiThread {
            status.text = getString(
                R.string.video_downloading,
                Formatter.formatShortFileSize(this, done),
                Formatter.formatShortFileSize(this, total),
            )
        }
    }

    private fun play(file: File) {
        status.visibility = View.GONE
        video.setOnErrorListener { _, _, _ ->
            ready = false
            status.setText(R.string.video_cant_play)
            status.visibility = View.VISIBLE
            true
        }
        video.setOnPreparedListener {
            ready = true
            time.visibility = View.VISIBLE
            video.start()
            startTicker()
        }
        video.setOnCompletionListener { showTime() }
        video.setVideoPath(file.path)
    }

    private fun togglePause() {
        if (video.isPlaying) video.pause() else video.start()
        showTime()
    }

    private fun seekBy(ms: Int) {
        video.seekTo((video.currentPosition + ms).coerceIn(0, video.duration))
        showTime()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (isActive) {
                showTime()
                delay(500)
            }
        }
    }

    // "▶ 0:12 / 0:45" while playing, "⏸ …" while paused
    private fun showTime() {
        val mark = if (video.isPlaying) "▶" else "⏸"
        time.text = "$mark ${MessageFormat.duration(video.currentPosition / 1000)} / ${MessageFormat.duration(video.duration / 1000)}"
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_ROUND = "round"
        private const val SEEK_MS = 10_000

        // round = a round video message: shown under a round mask
        fun start(activity: Activity, chatId: Long, messageId: Long, round: Boolean = false) {
            activity.startActivity(
                Intent(activity, VideoActivity::class.java)
                    .putExtra(EXTRA_CHAT_ID, chatId)
                    .putExtra(EXTRA_MESSAGE_ID, messageId)
                    .putExtra(EXTRA_ROUND, round)
            )
        }
    }
}
