package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// "Under the hood", the developer mode: tools for looking inside the app and the server.
// Opened with OK on the version line in Settings. The first row is about the server
// (/server_info, since 0.14.1-beta; OK asks again), then one row per tool: Live logs (the
// server's console log, LogActivity), Messages from the server (ServerMessagesActivity) and,
// since 0.15.1-beta, Modules (the server's modules, ModulesActivity), and since 1.2.0 Load a
// theme from a file (a theme zip on the phone, ThemeMenu.installFile) and, since 1.3.0, Local
// cache (what the app keeps on the phone, with deleting: LocalCacheActivity); more to come. Since 0.14.2-beta the messages row is highlighted while the server has
// messages for this app ("● Messages from the server (2)", bold; counted on every return to
// this screen). Left or Back closes it.
// New tools: add a row to rows().
class UnderTheHoodActivity : AppCompatActivity() {
    private var rows = emptyList<SettingRow>()
    private lateinit var adapter: ArrayAdapter<CharSequence>
    private var serverLine = ""
    private var messageCount = 0  // server messages waiting for this app
    private var messagesRow: SettingRow? = null

    // A theme zip on the phone (docs/themes-plan.md): for trying a theme before any server has it
    private val pickTheme = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            ThemeMenu.installFile(this, uri, listOf(Intent(this, SettingsActivity::class.java),
                Intent(this, UnderTheHoodActivity::class.java)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings_group)  // a title and a list, like a settings group
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        findViewById<TextView>(R.id.group_title).setText(R.string.under_the_hood_title)
        val list = findViewById<WrapListView>(R.id.group_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> rows[position].open() }
        list.requestFocus()
        loadServerInfo()
    }

    // Also after coming back from a tool, so the highlight stays right
    override fun onResume() {
        super.onResume()
        countMessages()
    }

    private fun redraw() {
        rows = rows()
        adapter.clear()
        adapter.addAll(rows.map { if (it === messagesRow) messagesLabel() else it.label })
    }

    private fun messagesLabel(): CharSequence {
        if (messageCount == 0) return getString(R.string.under_the_hood_server_messages)
        val text = SpannableString(getString(R.string.under_the_hood_server_messages_waiting, messageCount))
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    // Quietly: if the server can't be reached, the row just isn't highlighted
    private fun countMessages() {
        val settings = ServerSettings(this)
        val api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        lifecycleScope.launch {
            messageCount = try {
                // the server's messages for this app, and the admin messages not yet read
                (withContext(Dispatchers.IO) { api.getServerMessages() }?.size ?: 0) +
                    AdminMessages.unread(this@UnderTheHoodActivity)
            } catch (e: IOException) {
                0
            }
            redraw()
        }
    }

    // "Server 1.0.0 "Melted" · private"
    private fun loadServerInfo() {
        serverLine = getString(R.string.under_the_hood_server_asking)
        redraw()
        val settings = ServerSettings(this)
        val api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        lifecycleScope.launch {
            serverLine = try {
                val info = withContext(Dispatchers.IO) { api.getServerInfo() }
                when {
                    info == null -> getString(R.string.under_the_hood_server_old)
                    else -> getString(R.string.under_the_hood_server, info.version, info.codename,
                        getString(when {
                            !info.shared -> R.string.under_the_hood_private
                            info.log -> R.string.under_the_hood_shared_admin
                            else -> R.string.under_the_hood_shared
                        }))
                }
            } catch (e: BadTokenException) {
                getString(R.string.error_bad_token)
            } catch (e: IOException) {
                getString(R.string.error_no_server)
            }
            redraw()
        }
    }

    private fun rows() = listOf(
        SettingRow(serverLine) { loadServerInfo() },
        SettingRow(getString(R.string.under_the_hood_live_logs)) { LogActivity.start(this) },
        SettingRow(getString(R.string.under_the_hood_server_messages)) { ServerMessagesActivity.start(this) }
            .also { messagesRow = it },
        SettingRow(getString(R.string.under_the_hood_modules)) { ModulesActivity.start(this) },
        SettingRow(getString(R.string.under_the_hood_local_cache)) { LocalCacheActivity.start(this) },
        SettingRow(getString(R.string.under_the_hood_load_theme)) { pickTheme.launch("*/*") },
    )

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
            activity.startActivity(Intent(activity, UnderTheHoodActivity::class.java))
        }
    }
}
