package com.q43b.tgflip

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shows one chat's latest messages and adds new ones live.
// Opening a chat does not mark it as read: that sends a read receipt, so it stays a deliberate action.
class ChatActivity : AppCompatActivity() {
    private lateinit var connection: TextView
    private lateinit var status: TextView
    private lateinit var messageList: ListView
    private lateinit var messageAdapter: ArrayAdapter<String>
    private lateinit var messageInput: EditText
    private val messages = mutableListOf<Message>()
    private var sending = false

    private lateinit var api: ApiClient
    private var chatId = 0L
    private var live: LiveConnection? = null
    private var wasConnected = false

    private val serverDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val timeOnly = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayAndTime = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            // Also make room for an on-screen keyboard, so it never covers the message box
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // The list screen only opens this after settings are saved, but be safe
        val settings = ServerSettings(this)
        val address = settings.getAddress()
        val token = settings.getToken()
        if (address == null || token == null) {
            finish()
            return
        }
        api = ApiClient(address, token)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)

        connection = findViewById(R.id.connection)
        status = findViewById(R.id.status)
        messageList = findViewById(R.id.message_list)
        findViewById<TextView>(R.id.chat_title).text = intent.getStringExtra(EXTRA_CHAT_NAME)
        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        messageList.adapter = messageAdapter

        // OK on the keypad sends. We act on key-up and swallow key-down too,
        // so one press can never send twice.
        messageInput = findViewById(R.id.message_input)
        messageInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_UP) sendCurrentText()
            true
        }
        // The send button of an on-screen keyboard, if the phone shows one
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentText()
                true
            } else {
                false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        wasConnected = false
        live = LiveConnection(api, lifecycleScope, ::showConnection, ::onServerEvent)
        live?.start()
        loadMessages(quiet = false)
    }

    override fun onStop() {
        live?.stop()
        live = null
        super.onStop()
    }

    private fun loadMessages(quiet: Boolean) {
        if (!quiet) showStatus(getString(R.string.loading_messages))
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { api.getMessages(chatId) }
                showMessages(loaded)
            } catch (e: BadTokenException) {
                showStatus(getString(R.string.error_bad_token))
            } catch (e: IOException) {
                if (!quiet) showStatus(getString(R.string.error_no_server))
            }
        }
    }

    private fun showMessages(loaded: List<Message>) {
        messages.clear()
        messages.addAll(loaded)
        messageAdapter.clear()
        messageAdapter.addAll(loaded.map(::formatMessage))
        if (loaded.isEmpty()) showStatus(getString(R.string.no_messages)) else status.visibility = View.GONE
        // Newest message is at the bottom; start there
        messageList.setSelection(messageAdapter.count - 1)
        messageList.requestFocus()
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun showConnection(state: LiveConnection.Status) {
        connection.showConnection(state)
        // After a reconnect we may have missed messages, so refetch
        if (state == LiveConnection.Status.CONNECTED) {
            if (wasConnected) loadMessages(quiet = true)
            wasConnected = true
        }
    }

    private fun onServerEvent(event: JSONObject) {
        if (event.optString("type") != "message" || event.optLong("chat_id") != chatId) return
        val message = try {
            Message.fromJson(event)
        } catch (e: JSONException) {
            return
        }
        // Follow new messages only if the user is reading the bottom of the chat
        val selected = messageList.selectedItemPosition
        val atBottom = selected == AdapterView.INVALID_POSITION || selected >= messageAdapter.count - 1
        addMessage(message, follow = atBottom)
    }

    // Our own sent messages arrive twice: from /send and as a live event. The id check keeps one.
    private fun addMessage(message: Message, follow: Boolean) {
        if (messages.any { it.id == message.id }) return
        messages.add(message)
        messageAdapter.add(formatMessage(message))
        if (follow) messageList.setSelection(messageAdapter.count - 1)
    }

    private fun sendCurrentText() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty() || sending) return

        sending = true
        showStatus(getString(R.string.sending))
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { api.sendMessage(chatId, text) }
                messageInput.text.clear()
                status.visibility = View.GONE
                addMessage(Message(id, text, out = true, date = serverDate.format(Date())), follow = true)
            } catch (e: IOException) {
                // The text stays in the box, so OK again retries
                showStatus(getString(R.string.error_send))
            } finally {
                sending = false
            }
        }
    }

    private fun formatMessage(message: Message): String {
        val text = message.text.ifEmpty { getString(R.string.media) }
        val time = formatDate(message.date)
        return if (message.out) "$time ${getString(R.string.you)}: $text" else "$time $text"
    }

    // "14:05" for today, "03.11 14:05" for older days, in the phone's time zone
    private fun formatDate(date: String): String {
        return try {
            // The server may add fractions of a second; the parser does not need them
            val parsed = serverDate.parse(date.replace(Regex("\\.\\d+"), "")) ?: return ""
            if (DateUtils.isToday(parsed.time)) timeOnly.format(parsed) else dayAndTime.format(parsed)
        } catch (e: ParseException) {
            ""
        }
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_CHAT_NAME = "chat_name"

        fun start(context: Context, chat: Chat) {
            val intent = Intent(context, ChatActivity::class.java)
            intent.putExtra(EXTRA_CHAT_ID, chat.id)
            intent.putExtra(EXTRA_CHAT_NAME, chat.name)
            context.startActivity(intent)
        }
    }
}
