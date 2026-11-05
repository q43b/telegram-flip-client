package com.q43b.tgflip

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var cache: LocalCache
    private lateinit var connection: TextView
    private lateinit var status: TextView
    private lateinit var chatList: ListView
    private lateinit var chatAdapter: ArrayAdapter<CharSequence>
    private var previewColor = Color.GRAY  // the theme's secondary text color, set in onCreate
    private lateinit var softKeys: SoftKeyBar
    private var chats: List<Chat> = emptyList()
    private var chatLimit = PAGE_SIZE  // grows by a page with each "Show more chats"
    private var settingsDialog: AlertDialog? = null

    private var api: ApiClient? = null
    private var live: LiveConnection? = null
    private var wasConnected = false
    // Between onStart and onStop. Android's own lifecycle state only becomes STARTED
    // after onStart returns, too late for code that runs during onStart.
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settings = ServerSettings(this)
        cache = LocalCache(this)
        // Gray that suits both light and dark theme
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        previewColor = attrs.getColor(0, Color.GRAY)
        attrs.recycle()
        connection = findViewById(R.id.connection)
        status = findViewById(R.id.status)
        chatList = findViewById(R.id.chat_list)
        // One adapter for the screen's lifetime, so refreshes keep the d-pad selection
        chatAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        chatList.adapter = chatAdapter
        // Rows past the chats are the "Show more chats" row
        chatList.setOnItemClickListener { _, _, position, _ ->
            val chat = chats.getOrNull(position)
            if (chat != null) openChat(chat) else showMoreChats()
        }
        chatList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                updateSoftKey()

            override fun onNothingSelected(parent: AdapterView<*>?) = updateSoftKey()
        }

        softKeys = SoftKeyBar(this)
        softKeys.onRightTap { selectedChat()?.let(::openGeneral) }

        setUpTabBar(Tab.CHATS) { SettingsActivity.start(this) }
    }

    // Right soft key on a topic group opens its General topic directly.
    // D-pad Right opens Settings, like swiping open the official app's side menu
    // (the list only uses Up and Down, so Right is free here).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isRightSoftKey()) {
            if (event.action == KeyEvent.ACTION_UP) selectedChat()?.let(::openGeneral)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_UP) SettingsActivity.start(this)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun selectedChat(): Chat? = chats.getOrNull(chatList.selectedItemPosition)

    private fun updateSoftKey() {
        softKeys.setRight(if (selectedChat()?.forum == true) getString(R.string.soft_key_general) else null)
    }

    private fun openChat(chat: Chat) {
        if (chat.forum) TopicsActivity.start(this, chat) else ChatActivity.start(this, chat)
    }

    private fun openGeneral(chat: Chat) {
        if (!chat.forum) return
        ChatActivity.start(this, chat, Topic(Topic.GENERAL_ID, getString(R.string.general_topic), 0))
    }

    private fun showMoreChats() {
        chatLimit += PAGE_SIZE
        loadChats(quiet = false)
    }

    // The app is visible: connect. Hidden: disconnect, to save battery.
    override fun onStart() {
        super.onStart()
        visible = true
        start()
    }

    override fun onStop() {
        visible = false
        live?.stop()
        super.onStop()
    }

    private fun start() {
        live?.stop()
        live = null
        api = null
        val address = settings.getAddress()
        val token = settings.getToken()
        // Why this mode cannot connect, if it cannot
        val problem = when {
            address == null || token == null -> getString(R.string.server_not_set, getString(settings.getMode().label))
            address.toHttpUrlOrNull() == null -> getString(R.string.error_bad_address)
            else -> null
        }

        lifecycleScope.launch {
            // The saved copy shows in every case, also when this mode has no server yet
            showSavedChats()
            if (problem != null) {
                connection.showNoServer()
                showStatus(problem)  // after the saved chats, which hide the status line
                // A hint, not a forced dialog: the user may just have switched modes.
                // Only the very first launch (no token yet) opens the dialog by itself.
                if (token == null) askForSettings()
                return@launch
            }
            // The screen may have been hidden meanwhile; onStart will run start() again
            if (!visible) return@launch
            val api = ApiClient(address, token!!)
            this@MainActivity.api = api
            wasConnected = false
            live = LiveConnection(api, lifecycleScope, ::showConnection, ::onServerEvent).also { it.start() }
            loadChats(quiet = false)
        }
    }

    // On a fresh screen, show the chats saved last time while the real ones load
    private suspend fun showSavedChats() {
        if (chats.isNotEmpty()) return
        val saved = withContext(Dispatchers.IO) { cache.loadChats() } ?: return
        // Ask for as many pages as were saved, so the list does not shrink on refresh
        chatLimit = ((saved.size + PAGE_SIZE - 1) / PAGE_SIZE * PAGE_SIZE).coerceAtLeast(PAGE_SIZE)
        showChats(saved)
    }

    // quiet = background refresh: no "Loading" text, and network errors are left to the status bar
    private fun loadChats(quiet: Boolean) {
        val api = api ?: return
        if (!quiet) showStatus(getString(R.string.loading))
        lifecycleScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { api.getChats(chatLimit).also(cache::saveChats) }
                showChats(fresh)
            } catch (e: BadTokenException) {
                showError(getString(R.string.error_bad_token))
            } catch (e: IOException) {
                when {
                    quiet -> {}
                    // Saved chats are on screen: say so, no settings dialog
                    chats.isNotEmpty() -> showStatus(getString(R.string.offline_saved))
                    else -> showError(getString(R.string.error_no_server))
                }
            }
        }
    }

    private fun showChats(chats: List<Chat>) {
        this.chats = chats
        chatAdapter.clear()
        chatAdapter.addAll(chats.map(::chatRow))
        // A full page means there may be more. The selection stays on this row's position,
        // so after loading more it lands on the first newly loaded chat.
        if (chats.size >= chatLimit) chatAdapter.add(getString(R.string.show_more_chats))
        if (chats.isEmpty()) showStatus(getString(R.string.no_chats)) else status.visibility = View.GONE
        chatList.requestFocus()
        updateSoftKey()
    }

    // "Name (3)"; topic groups get a "›" because they open a list of topics.
    // Below it, smaller and grayed, the newest message: "14:05 Anna: hi there…"
    private fun chatRow(chat: Chat): CharSequence {
        val name = if (chat.forum) "${chat.name} ›" else chat.name
        val title = if (chat.unread > 0) "$name (${chat.unread})" else name
        val last = chat.last ?: return title
        val preview = SpannableString(previewLine(last))
        preview.setSpan(RelativeSizeSpan(0.8f), 0, preview.length, 0)
        preview.setSpan(ForegroundColorSpan(previewColor), 0, preview.length, 0)
        return TextUtils.concat(title, "\n", preview)
    }

    private fun previewLine(last: LastMessage): String {
        val call = last.call
        val body = when {
            last.kind == "call" && call != null -> MessageFormat.call(this, call, last.out)
            last.kind == "media" -> getString(R.string.media)
            last.kind == "event" -> getString(R.string.event)
            else -> MessageFormat.snippet(last.text, PREVIEW_LENGTH)
        }
        // A call line already says who called ("Incoming" / "Outgoing")
        val who = when {
            last.kind == "call" -> ""
            last.out -> "${getString(R.string.you)}: "
            last.sender != null -> "${MessageFormat.snippet(last.sender, SENDER_LENGTH)}: "
            else -> ""
        }
        return "${MessageFormat.time(last.date)} $who$body"
    }

    private fun showConnection(state: LiveConnection.Status) {
        connection.showConnection(state)

        // After a reconnect we may have missed events, so refetch
        if (state == LiveConnection.Status.CONNECTED) {
            if (wasConnected) loadChats(quiet = true)
            wasConnected = true
        }
    }

    private fun onServerEvent(event: JSONObject) {
        if (IncomingCallAlert.handle(this, event)) return
        // A new message or a read elsewhere changes unread counts and order;
        // edits and deletes can change the newest message shown in the preview
        when (event.optString("type")) {
            "message", "read", "edited", "deleted" -> loadChats(quiet = true)
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun showError(text: String) {
        showStatus(text)
        askForSettings()
    }

    // First launch or a connection error. Always closable (Back or Cancel): the status line
    // keeps the hint, and Settings (Right) has the same fields.
    private fun askForSettings() {
        if (settingsDialog?.isShowing == true) return
        settingsDialog = ServerSettingsDialog.show(this, settings, settings.getMode(), cancelable = true) { start() }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val PREVIEW_LENGTH = 32  // about one line of the small preview text
        const val SENDER_LENGTH = 12
    }
}
