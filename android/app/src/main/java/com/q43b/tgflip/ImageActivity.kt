package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.AtomicFile
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// A photo from a message, on its own screen, fitted to the display. Opened with
// "View photo" in the message menu. The server shrinks photos to the phone's screen size
// (GET /image); the copy is kept in the media cache's "images" folder, so the Cache
// settings apply. Left or Back closes it.
class ImageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_image)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0)
        val image = findViewById<ImageView>(R.id.image)
        val status = findViewById<TextView>(R.id.status)
        status.setText(R.string.photo_loading)

        val settings = ServerSettings(this)
        val api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val file = file(api, chatId, messageId)
                    BitmapFactory.decodeFile(file.path)
                } catch (e: IOException) {
                    null
                }
            }
            if (bitmap == null) {
                status.setText(R.string.photo_not_loaded)
            } else {
                image.setImageBitmap(bitmap)
                status.visibility = View.GONE
            }
        }
    }

    // Left closes, like the profile screens
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // The saved copy, or a fresh download that is then saved
    private fun file(api: ApiClient, chatId: Long, messageId: Long): File {
        val dir = MediaCache.folder(this, "images")
        val file = File(dir, "${chatId}_$messageId.jpg")
        if (file.exists()) {
            MediaCache.touch(file)
            return file
        }
        val bytes = api.getImage(chatId, messageId)
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

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_MESSAGE_ID = "message_id"

        fun start(activity: Activity, chatId: Long, messageId: Long) {
            activity.startActivity(
                Intent(activity, ImageActivity::class.java)
                    .putExtra(EXTRA_CHAT_ID, chatId)
                    .putExtra(EXTRA_MESSAGE_ID, messageId)
            )
        }
    }
}
