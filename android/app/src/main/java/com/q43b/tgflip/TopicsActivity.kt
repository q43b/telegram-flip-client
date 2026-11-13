package com.q43b.tgflip

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
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
import org.json.JSONObject
import java.io.IOException

// Lists the topics of a forum group (a group split into topics). OK opens one.
class TopicsActivity : AppCompatActivity() {
    private lateinit var connection: TextView
    private lateinit var status: TextView
    private lateinit var topicList: ListView
    private lateinit var topicAdapter: ArrayAdapter<String>
    private var topics: List<Topic> = emptyList()

    private lateinit var api: Backend
    private lateinit var cache: LocalCache
    private lateinit var chat: Chat
    private var live: LiveConnection? = null
    private var wasConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_topics)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // The list screen only opens this after settings are saved, but be safe
        // Without a server for the current mode the screen still opens from the saved copy
        val settings = ServerSettings(this)
        api = Backends.create(this)
        cache = LocalCache(this)
        chat = Chat(
            id = intent.getLongExtra(EXTRA_CHAT_ID, 0),
            name = intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty(),
            unread = 0,
            forum = true,
        )

        connection = findViewById(R.id.connection)
        status = findViewById(R.id.status)
        topicList = findViewById(R.id.topic_list)
        findViewById<TextView>(R.id.group_title).text = chat.name
        // One adapter for the screen's lifetime, so refreshes keep the d-pad selection
        topicAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        topicList.adapter = topicAdapter
        topicList.setOnItemClickListener { _, _, position, _ ->
            topics.getOrNull(position)?.let { ChatActivity.start(this, chat, it) }
        }
    }

    // Refresh on every return, so unread counts are fresh after reading a topic
    override fun onStart() {
        super.onStart()
        wasConnected = false
        if (api.hasServer) {
            live = LiveConnection(api, lifecycleScope, ::showConnection, ::onServerEvent).also { it.start() }
        } else {
            connection.showNoServer()
        }
        lifecycleScope.launch {
            // On a fresh screen, show the topics saved last time while the real ones load
            if (topics.isEmpty()) {
                withContext(Dispatchers.IO) { cache.loadTopics(chat.id) }?.let(::showTopics)
            }
            loadTopics(quiet = false)
        }
    }

    override fun onStop() {
        live?.stop()
        live = null
        super.onStop()
    }

    // quiet = background refresh: no "Loading" text, and network errors are left to the status bar
    private fun loadTopics(quiet: Boolean) {
        if (!quiet) showStatus(getString(R.string.loading_topics))
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    api.getTopics(chat.id).also { cache.saveTopics(chat.id, it) }
                }
                showTopics(loaded)
            } catch (e: BadTokenException) {
                showStatus(getString(R.string.error_bad_token))
            } catch (e: NoServerException) {
                showStatus(getString(R.string.server_not_set_short))
            } catch (e: IOException) {
                if (!quiet) {
                    val text = if (topics.isNotEmpty()) R.string.offline_saved else R.string.error_no_server
                    showStatus(getString(text))
                }
            }
        }
    }

    private fun showTopics(loaded: List<Topic>) {
        topics = loaded
        topicAdapter.clear()
        topicAdapter.addAll(loaded.map { if (it.unread > 0) "${it.title} (${it.unread})" else it.title })
        if (loaded.isEmpty()) showStatus(getString(R.string.no_topics)) else status.visibility = View.GONE
        topicList.requestFocus()
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun showConnection(state: LiveConnection.Status) {
        connection.showConnection(state)
        // After a reconnect we may have missed events, so refetch
        if (state == LiveConnection.Status.CONNECTED) {
            if (wasConnected) loadTopics(quiet = true)
            wasConnected = true
        }
    }

    private fun onServerEvent(event: JSONObject) {
        if (IncomingCallAlert.handle(this, event)) return
        // A new message or a read in this group changes the topics' counts and order
        if (event.optLong("chat_id") != chat.id) return
        when (event.optString("type")) {
            "message", "read" -> loadTopics(quiet = true)
        }
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_CHAT_NAME = "chat_name"

        fun start(context: Context, chat: Chat) {
            val intent = Intent(context, TopicsActivity::class.java)
            intent.putExtra(EXTRA_CHAT_ID, chat.id)
            intent.putExtra(EXTRA_CHAT_NAME, chat.name)
            context.startActivity(intent)
        }
    }
}
