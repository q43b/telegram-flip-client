package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
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

// What this app can receive from the server in its own form. The server sends everything
// else it would tell the app as plain text to "Messages from the server" (a fallback, so newer
// servers can still reach older apps). When the app learns to receive a kind properly (its own
// endpoint or event), add its name here, and it stops showing up as a message.
object ServerMessages {
    val KNOWN_KINDS = listOf(
        "server_info",  // since 0.14.1-beta: the first line of Under the hood (UnderTheHoodActivity)
        "modules",  // since 0.15.1-beta: Under the hood > Modules (ModulesActivity)
        "animated_stickers",  // since 0.16.0-beta: Settings > Stickers > Animated, played in chats
        "admin_messages",  // since 1.0.0: admin news, a dot trail to them (AdminMessages)
        "themes_host",  // since 1.2.1: Appearance > Get themes from the server (ThemeMenu)
    )
}

// "Messages from the server" in Under the hood: those texts, one per row, loaded when the
// screen opens. Left or Back closes it.
class ServerMessagesActivity : AppCompatActivity() {
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings_group)  // a title and a list
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        findViewById<TextView>(R.id.group_title).setText(R.string.server_messages_title)
        val list = findViewById<WrapListView>(R.id.group_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.requestFocus()
        load()
    }

    private fun load() {
        show(getString(R.string.server_messages_loading))
        val settings = ServerSettings(this)
        val api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        lifecycleScope.launch {
            val shown = try {
                val messages = withContext(Dispatchers.IO) {
                    api.getServerMessages()?.let { others -> api.getAdminMessages() to others }
                }?.let { (admin, others) ->
                    // Shown here: the admin ones are read now, and their dots go
                    AdminMessages.markRead(this@ServerMessagesActivity, admin)
                    admin.map { getString(R.string.admin_message_prefix, it) } + others
                }
                when {
                    messages == null -> listOf(getString(R.string.server_messages_old_server))
                    messages.isEmpty() -> listOf(getString(R.string.server_messages_none))
                    else -> messages
                }
            } catch (e: BadTokenException) {
                listOf(getString(R.string.error_bad_token))
            } catch (e: IOException) {
                listOf(getString(R.string.error_no_server))
            }
            show(*shown.toTypedArray())
        }
    }

    private fun show(vararg rows: String) {
        adapter.clear()
        adapter.addAll(*rows)
    }

    // Left goes back, like the screens of Settings
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, ServerMessagesActivity::class.java))
        }
    }
}
