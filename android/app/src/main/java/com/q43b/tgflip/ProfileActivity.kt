package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// Someone's profile: photo (or their letter circle), name, username and phone when
// visible. Opened with "View profile" in a message's menu (private chats and groups), or
// from a group's member list; from a group it also offers "Send message", which opens the private chat with them (Telegram
// creates it with the first message). Left or Back closes it.
class ProfileActivity : AppCompatActivity() {
    private lateinit var photos: PhotoLoader
    private lateinit var photoView: ImageView
    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var userId = 0L
    private var name = ""
    private var profile: Profile? = null
    private var canMessage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        userId = intent.getLongExtra(EXTRA_USER_ID, 0)
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        canMessage = intent.getBooleanExtra(EXTRA_CAN_MESSAGE, false)
        photoView = findViewById(R.id.profile_photo)
        status = findViewById(R.id.status)
        photos = PhotoLoader(this, lifecycleScope, PhotoLoader.PROFILE_SIZE_DP) { showPhoto() }
        val settings = ServerSettings(this)
        photos.api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())

        val list = findViewById<ListView>(R.id.profile_rows)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onRowChosen(position) }
        list.requestFocus()

        showProfile()
        load()
    }

    // Left closes, like Settings
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun load() {
        val api = photos.api ?: return
        status.text = getString(R.string.profile_loading)
        status.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                profile = withContext(Dispatchers.IO) { api.getProfile(userId) }
                status.visibility = View.GONE
            } catch (e: BadTokenException) {
                status.text = getString(R.string.error_bad_token)
            } catch (e: IOException) {
                status.text = getString(R.string.profile_not_loaded)
            }
            showProfile()
        }
    }

    // Before loading: the name we were given and the letter circle; the username and
    // phone rows only once the server answered
    private fun showProfile() {
        val p = profile
        findViewById<TextView>(R.id.profile_name).text = p?.name?.ifEmpty { null } ?: name
        showPhoto()
        adapter.clear()
        if (p != null) {
            adapter.add(getString(R.string.profile_username, p.username?.let { "@$it" } ?: getString(R.string.profile_no_username)))
            adapter.add(getString(R.string.profile_phone, p.phone ?: getString(R.string.profile_hidden)))
        }
        if (canMessage) adapter.add(getString(R.string.profile_send_message))
    }

    private fun showPhoto() {
        val p = profile
        photoView.setImageDrawable(photos.drawable(userId, p?.name ?: name, p?.photo))
    }

    private fun onRowChosen(position: Int) {
        if (canMessage && position == adapter.count - 1) {
            val p = profile
            ChatActivity.start(this, Chat(userId, p?.name?.ifEmpty { null } ?: name, unread = 0, forum = false))
            finish()
        }
    }

    companion object {
        private const val EXTRA_USER_ID = "user_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_CAN_MESSAGE = "can_message"

        // canMessage: offer "Send message" (not when opened from that private chat)
        fun start(activity: Activity, userId: Long, name: String, canMessage: Boolean) {
            activity.startActivity(
                Intent(activity, ProfileActivity::class.java)
                    .putExtra(EXTRA_USER_ID, userId)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_CAN_MESSAGE, canMessage)
            )
        }
    }
}
