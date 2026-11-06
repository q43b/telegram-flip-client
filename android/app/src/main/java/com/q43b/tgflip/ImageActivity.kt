package com.q43b.tgflip

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
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
// settings apply.
// F2 opens a menu: Zoom (then Up/Down zoom in and out), Move around (then the arrow keys
// move the picture), Rotate 90° clockwise, and Download photo (the full-size original into
// the phone's Pictures/TG Flip folder). OK, Back or F1 ends a mode. Outside a mode Left or
// Back closes the screen.
class ImageActivity : AppCompatActivity() {
    private enum class Mode { NONE, ZOOM, MOVE }

    private lateinit var image: ImageView
    private lateinit var modeHint: TextView
    private lateinit var softKeys: SoftKeyBar
    private lateinit var api: ApiClient
    private var chatId = 0L
    private var messageId = 0L
    private var stickerId = 0L  // set when showing a sticker instead of a photo
    private var downloading = false
    private var bitmap: Bitmap? = null
    private var mode = Mode.NONE
    private var zoom = 1f        // 1 = the whole picture fits the screen
    private var rotation = 0     // degrees clockwise: 0, 90, 180, 270
    private var panX = 0f        // how far the picture is moved from the center, in pixels
    private var panY = 0f

    // Back ends a mode first, and only then closes the screen
    private val endModeOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setMode(Mode.NONE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_image)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0)
        stickerId = intent.getLongExtra(EXTRA_STICKER_ID, 0)
        image = findViewById(R.id.image)
        modeHint = findViewById(R.id.mode_hint)
        val status = findViewById<TextView>(R.id.status)
        status.setText(R.string.photo_loading)
        onBackPressedDispatcher.addCallback(this, endModeOnBack)
        // The picture is placed by hand (scaleType matrix), so place it again whenever the
        // screen area changes size
        image.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyMatrix() }

        softKeys = SoftKeyBar(this)
        softKeys.onLeftTap { setMode(Mode.NONE) }
        softKeys.onRightTap(::showMenu)

        val settings = ServerSettings(this)
        api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val file = file(api, chatId, messageId)
                    BitmapFactory.decodeFile(file.path)
                } catch (e: IOException) {
                    null
                }
            }
            if (loaded == null) {
                status.setText(R.string.photo_not_loaded)
            } else {
                bitmap = loaded
                image.setImageBitmap(loaded)
                status.visibility = View.GONE
                applyMatrix()
                softKeys.setRight(getString(R.string.soft_key_menu))
            }
        }
    }

    // F2 = the menu, F1 = end a mode; in a mode the d-pad zooms or moves (on key-down, so
    // holding a key repeats); OK ends the mode. Left outside a mode closes the screen.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isRightSoftKey()) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }
        if (event.isLeftSoftKey() || (event.isOkKey() && mode != Mode.NONE)) {
            if (event.action == KeyEvent.ACTION_UP) setMode(Mode.NONE)
            return true
        }
        val handled = when (mode) {
            Mode.NONE -> event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            Mode.ZOOM -> event.keyCode in ZOOM_KEYS
            Mode.MOVE -> event.keyCode in MOVE_KEYS
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) onArrow(event.keyCode)
        if (event.action == KeyEvent.ACTION_UP && mode == Mode.NONE) finish()
        return true
    }

    private fun onArrow(keyCode: Int) {
        when (mode) {
            Mode.NONE -> return
            Mode.ZOOM -> zoom = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> (zoom * ZOOM_STEP).coerceAtMost(MAX_ZOOM)
                else -> (zoom / ZOOM_STEP).coerceAtLeast(1f)
            }
            // The arrow says where to look, so the picture moves the other way
            Mode.MOVE -> {
                val stepX = image.width * MOVE_STEP
                val stepY = image.height * MOVE_STEP
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> panX += stepX
                    KeyEvent.KEYCODE_DPAD_RIGHT -> panX -= stepX
                    KeyEvent.KEYCODE_DPAD_UP -> panY += stepY
                    KeyEvent.KEYCODE_DPAD_DOWN -> panY -= stepY
                }
            }
        }
        applyMatrix()
    }

    private fun showMenu() {
        if (bitmap == null) return
        // A sticker has no full-size original to download
        val items = listOf(R.string.image_zoom, R.string.image_move, R.string.image_rotate) +
            if (stickerId == 0L) listOf(R.string.image_download) else emptyList()
        MessageMenu.choose(this, getString(R.string.photo), items.map(::getString)) { which ->
            when (items[which]) {
                R.string.image_zoom -> setMode(Mode.ZOOM)
                R.string.image_move -> setMode(Mode.MOVE)
                R.string.image_rotate -> {
                    rotation = (rotation + 90) % 360
                    applyMatrix()
                }
                R.string.image_download -> download()
            }
        }
    }

    // Asks the server for the full-size original and saves it in Pictures/TG Flip, where the
    // phone's own picture apps find it. Through the media store, which needs no permission
    // from Android 10 on; older phones only get a note.
    private fun download() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast(R.string.image_download_needs_10)
            return
        }
        if (downloading) return
        downloading = true
        toast(R.string.image_downloading)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    savePicture(api.getFullImage(chatId, messageId), "TGFlip_${chatId}_$messageId.jpg")
                    true
                } catch (e: IOException) {
                    false
                }
            }
            downloading = false
            toast(if (saved) R.string.image_downloaded else R.string.image_download_failed)
        }
    }

    // Marked pending while it is written, so no picture app shows half a file
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePicture(bytes: ByteArray, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SAVE_FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Can't save pictures")
        try {
            val out = contentResolver.openOutputStream(uri) ?: throw IOException("Can't save pictures")
            out.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: IOException) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // The hint at the top and the left soft key say how to use and leave the mode
    private fun setMode(newMode: Mode) {
        mode = newMode
        endModeOnBack.isEnabled = newMode != Mode.NONE
        val hint = when (newMode) {
            Mode.NONE -> null
            Mode.ZOOM -> getString(R.string.image_zoom_hint)
            Mode.MOVE -> getString(R.string.image_move_hint)
        }
        modeHint.text = hint
        modeHint.visibility = if (hint == null) View.GONE else View.VISIBLE
        softKeys.setLeft(if (hint == null) null else getString(R.string.soft_key_done))
    }

    // Places the picture: turned around its center, fitted to the screen, zoomed, then
    // moved. It can only be moved as far as it reaches past the screen's edges.
    private fun applyMatrix() {
        val bitmap = bitmap ?: return
        val viewW = image.width.toFloat()
        val viewH = image.height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val sideways = rotation % 180 != 0
        val picW = (if (sideways) bitmap.height else bitmap.width).toFloat()
        val picH = (if (sideways) bitmap.width else bitmap.height).toFloat()
        val scale = minOf(viewW / picW, viewH / picH) * zoom
        val maxX = maxOf(0f, (picW * scale - viewW) / 2)
        val maxY = maxOf(0f, (picH * scale - viewH) / 2)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        val matrix = Matrix()
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postRotate(rotation.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(viewW / 2 + panX, viewH / 2 + panY)
        image.imageMatrix = matrix
    }

    // The saved copy, or a fresh download that is then saved. A sticker comes at its full
    // 512 px from GET /sticker, into the stickers folder next to the chat-sized copies.
    private fun file(api: ApiClient, chatId: Long, messageId: Long): File {
        val sticker = stickerId != 0L
        val dir = MediaCache.folder(this, if (sticker) "stickers" else "images")
        val file = File(dir, if (sticker) "${stickerId}_$STICKER_PX.png" else "${chatId}_$messageId.jpg")
        if (file.exists()) {
            MediaCache.touch(file)
            return file
        }
        val bytes = if (sticker) api.getSticker(stickerId, STICKER_PX, chatId, messageId) ?: throw IOException("No picture")
        else api.getImage(chatId, messageId)
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
        private const val EXTRA_STICKER_ID = "sticker_id"
        private const val STICKER_PX = 512
        private const val SAVE_FOLDER = "TG Flip"
        private const val ZOOM_STEP = 1.25f
        private const val MAX_ZOOM = 6f
        private const val MOVE_STEP = 0.15f  // of the screen's width or height per press
        private val ZOOM_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        private val MOVE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        fun start(activity: Activity, chatId: Long, messageId: Long) {
            activity.startActivity(
                Intent(activity, ImageActivity::class.java)
                    .putExtra(EXTRA_CHAT_ID, chatId)
                    .putExtra(EXTRA_MESSAGE_ID, messageId)
            )
        }

        // "View sticker": the same viewer, with the sticker's still picture at full size
        fun startSticker(activity: Activity, chatId: Long, messageId: Long, stickerId: Long) {
            activity.startActivity(
                Intent(activity, ImageActivity::class.java)
                    .putExtra(EXTRA_CHAT_ID, chatId)
                    .putExtra(EXTRA_MESSAGE_ID, messageId)
                    .putExtra(EXTRA_STICKER_ID, stickerId)
            )
        }
    }
}
