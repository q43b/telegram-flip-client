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
import kotlinx.coroutines.delay
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
    private lateinit var readKey: TextView
    private val messages = mutableListOf<Message>()
    private var unread = 0
    private var sending = false
    private var markingRead = false

    private lateinit var api: ApiClient
    private lateinit var cache: LocalCache
    private var chatId = 0L
    private var topicId: Long? = null  // set when this is one topic of a forum group
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
        cache = LocalCache(this)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        if (intent.hasExtra(EXTRA_TOPIC_ID)) topicId = intent.getLongExtra(EXTRA_TOPIC_ID, 0)

        connection = findViewById(R.id.connection)
        status = findViewById(R.id.status)
        messageList = findViewById(R.id.message_list)
        findViewById<TextView>(R.id.chat_title).text = intent.getStringExtra(EXTRA_TITLE)
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
        // The label shows only while there is something to mark as read, as a reminder for F2.
        // Tapping it works too on a touchscreen.
        readKey = findViewById(R.id.soft_key_right)
        readKey.setOnClickListener { markAsRead() }
        unread = intent.getIntExtra(EXTRA_UNREAD, 0)
        updateReadKey()

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
        lifecycleScope.launch {
            // On a fresh screen, show the messages saved last time while the real ones load
            if (messages.isEmpty()) {
                withContext(Dispatchers.IO) { cache.loadMessages(chatId, topicId) }?.let(::showMessages)
            }
            loadMessages(quiet = false)
        }
        refreshUnread()  // may have changed while the app was in the background
    }

    override fun onStop() {
        live?.stop()
        live = null
        super.onStop()
    }

    // Right soft key = mark as read. Phones report it either as F2 or as SOFT_RIGHT.
    // Caught here, before any list or text box sees it; we act on key-up, like sending.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isRightSoftKey()) {
            if (event.action == KeyEvent.ACTION_UP) markAsRead()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Does nothing while there is nothing unread, like the web version
    private fun markAsRead() {
        if (markingRead || unread == 0) return
        markingRead = true
        showStatus(getString(R.string.marking_read))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { api.markRead(chatId, topicId) }
                unread = 0
                updateReadKey()
                showStatus(getString(R.string.marked_read))
                hideStatusLater(getString(R.string.marked_read))
            } catch (e: IOException) {
                showStatus(getString(R.string.error_mark_read))
            } finally {
                markingRead = false
            }
        }
    }

    private fun updateReadKey() {
        readKey.text = getString(R.string.soft_key_mark_read, unread)
        readKey.visibility = if (unread > 0) View.VISIBLE else View.GONE
    }

    // Asks the server for this chat's (or topic's) unread count. Only the chat and topic
    // lists have it; if the chat is not among the first 20, keep what we have.
    private fun refreshUnread() {
        val topicId = topicId
        lifecycleScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    if (topicId == null) api.getChats().find { it.id == chatId }?.unread
                    else api.getTopics(chatId).find { it.id == topicId }?.unread
                } ?: return@launch
                unread = fresh
                updateReadKey()
            } catch (e: IOException) {
                // The connection bar already shows network trouble
            }
        }
    }

    // Hides the status after a moment, unless something else has replaced it meanwhile
    private fun hideStatusLater(text: String) {
        lifecycleScope.launch {
            delay(2_000)
            if (status.text.toString() == text) status.visibility = View.GONE
        }
    }

    private fun loadMessages(quiet: Boolean) {
        if (!quiet) showStatus(getString(R.string.loading_messages))
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    api.getMessages(chatId, topicId).also { cache.saveMessages(chatId, topicId, it) }
                }
                showMessages(loaded)
            } catch (e: BadTokenException) {
                showStatus(getString(R.string.error_bad_token))
            } catch (e: IOException) {
                if (!quiet) {
                    val text = if (messages.isNotEmpty()) R.string.offline_saved else R.string.error_no_server
                    showStatus(getString(text))
                }
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
            if (wasConnected) {
                loadMessages(quiet = true)
                refreshUnread()
            }
            wasConnected = true
        }
    }

    private fun onServerEvent(event: JSONObject) {
        if (event.optLong("chat_id") != chatId) return
        when (event.optString("type")) {
            "message" -> if (isInThisTopic(event)) onLiveMessage(event)
            "read" -> refreshUnread()  // read on another device, maybe only partly
        }
    }

    // The server sends topic_id null for ordinary chats and for a forum's General topic
    private fun isInThisTopic(event: JSONObject): Boolean {
        val topicId = topicId ?: return true
        val eventTopic = if (event.isNull("topic_id")) null else event.optLong("topic_id")
        return if (topicId == Topic.GENERAL_ID) eventTopic == null else eventTopic == topicId
    }

    private fun onLiveMessage(event: JSONObject) {
        val message = try {
            Message.fromJson(event)
        } catch (e: JSONException) {
            return
        }
        // Follow new messages only if the user is reading the bottom of the chat
        val selected = messageList.selectedItemPosition
        val atBottom = selected == AdapterView.INVALID_POSITION || selected >= messageAdapter.count - 1
        if (addMessage(message, follow = atBottom) && !message.out) {
            unread++
            updateReadKey()
        }
    }

    // Our own sent messages arrive twice: from /send and as a live event. The id check keeps one.
    // Returns false if the message was already shown.
    private fun addMessage(message: Message, follow: Boolean): Boolean {
        if (messages.any { it.id == message.id }) return false
        messages.add(message)
        messageAdapter.add(formatMessage(message))
        if (follow) messageList.setSelection(messageAdapter.count - 1)
        // Keep the saved copy current; save a snapshot, since the list keeps changing
        val snapshot = messages.toList()
        lifecycleScope.launch(Dispatchers.IO) { cache.saveMessages(chatId, topicId, snapshot) }
        return true
    }

    private fun sendCurrentText() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty() || sending) return

        sending = true
        showStatus(getString(R.string.sending))
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { api.sendMessage(chatId, topicId, text) }
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
        private const val EXTRA_TOPIC_ID = "topic_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_UNREAD = "unread"

        // topic = null for an ordinary chat; otherwise one topic of a forum group
        fun start(context: Context, chat: Chat, topic: Topic? = null) {
            val intent = Intent(context, ChatActivity::class.java)
            intent.putExtra(EXTRA_CHAT_ID, chat.id)
            if (topic == null) {
                intent.putExtra(EXTRA_TITLE, chat.name)
                intent.putExtra(EXTRA_UNREAD, chat.unread)
            } else {
                intent.putExtra(EXTRA_TOPIC_ID, topic.id)
                intent.putExtra(EXTRA_TITLE, "${chat.name} › ${topic.title}")
                intent.putExtra(EXTRA_UNREAD, topic.unread)
            }
            context.startActivity(intent)
        }
    }
}
