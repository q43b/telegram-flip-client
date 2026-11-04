package com.q43b.tgflip

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var connection: TextView
    private lateinit var status: TextView
    private lateinit var chatList: ListView
    private lateinit var chatAdapter: ArrayAdapter<String>
    private var chats: List<Chat> = emptyList()
    private var settingsDialog: AlertDialog? = null

    private var api: ApiClient? = null
    private var live: LiveConnection? = null
    private var wasConnected = false

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
        connection = findViewById(R.id.connection)
        status = findViewById(R.id.status)
        chatList = findViewById(R.id.chat_list)
        // One adapter for the screen's lifetime, so refreshes keep the d-pad selection
        chatAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        chatList.adapter = chatAdapter
        chatList.setOnItemClickListener { _, _, position, _ -> ChatActivity.start(this, chats[position]) }
    }

    // The app is visible: connect. Hidden: disconnect, to save battery.
    override fun onStart() {
        super.onStart()
        start()
    }

    override fun onStop() {
        live?.stop()
        super.onStop()
    }

    private fun start() {
        live?.stop()
        live = null
        val address = settings.getAddress()
        val token = settings.getToken()
        if (address == null || token == null) {
            askForSettings()
            return
        }
        if (address.toHttpUrlOrNull() == null) {
            showError(getString(R.string.error_bad_address))
            return
        }

        val api = ApiClient(address, token)
        this.api = api
        wasConnected = false
        live = LiveConnection(api, lifecycleScope, ::showConnection, ::onServerEvent)
        live?.start()
        loadChats(quiet = false)
    }

    // quiet = background refresh: no "Loading" text, and network errors are left to the status bar
    private fun loadChats(quiet: Boolean) {
        val api = api ?: return
        if (!quiet) showStatus(getString(R.string.loading))
        lifecycleScope.launch {
            try {
                val chats = withContext(Dispatchers.IO) { api.getChats() }
                showChats(chats)
            } catch (e: BadTokenException) {
                showError(getString(R.string.error_bad_token))
            } catch (e: IOException) {
                if (!quiet) showError(getString(R.string.error_no_server))
            }
        }
    }

    private fun showChats(chats: List<Chat>) {
        this.chats = chats
        chatAdapter.clear()
        chatAdapter.addAll(chats.map { if (it.unread > 0) "${it.name} (${it.unread})" else it.name })
        if (chats.isEmpty()) showStatus(getString(R.string.no_chats)) else status.visibility = View.GONE
        chatList.requestFocus()
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
        // A new message or a read elsewhere changes unread counts and order
        when (event.optString("type")) {
            "message", "read" -> loadChats(quiet = true)
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

    private fun askForSettings() {
        if (settingsDialog?.isShowing == true) return

        val addressInput = EditText(this)
        addressInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        addressInput.hint = getString(R.string.address_hint)
        addressInput.setText(settings.getAddress() ?: DEFAULT_ADDRESS)

        // Visible on purpose: typing a hidden token on a keypad is too error-prone
        val tokenInput = EditText(this)
        tokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        tokenInput.hint = getString(R.string.token_hint)
        tokenInput.setText(settings.getToken() ?: "")

        val fields = LinearLayout(this)
        fields.orientation = LinearLayout.VERTICAL
        fields.addView(addressInput)
        fields.addView(tokenInput)

        settingsDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(fields)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.saveAddress(addressInput.text.toString().trim().trimEnd('/'))
                settings.saveToken(tokenInput.text.toString().trim())
                start()
            }
            .show()
    }

    private companion object {
        const val DEFAULT_ADDRESS = "http://192.168.31.24:8000"
    }
}
