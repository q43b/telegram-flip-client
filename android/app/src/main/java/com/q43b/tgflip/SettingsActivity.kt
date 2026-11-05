package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// The settings screen: a list with one row per setting, OK opens a row.
// Opened with Right from the chat list, like swiping open the official app's side
// menu; Left or Back closes it. New settings: add a row in rows() and a case in open().
class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settings = ServerSettings(this)
        val list = findViewById<ListView>(R.id.settings_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows().toMutableList())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> open(position) }
        list.requestFocus()

        // versionName from build.gradle.kts (dev builds add "-dev")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.app_version).text = getString(R.string.app_version, version)

        setUpTabBar(Tab.SETTINGS) { finish() }
    }

    // Left closes, mirroring Right that opened it
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Row 0: which connection mode is active. Rows 1..3: each mode's server.
    // Then theme and quick phrases. The chat list reconnects with new server values when
    // shown again.
    private fun rows(): List<String> {
        val modes = ConnectionMode.entries.map { mode ->
            // The address without "http(s)://", as host:port
            val server = settings.getAddress(mode)?.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" }
            getString(R.string.setting_server, getString(mode.label), server ?: getString(R.string.not_set))
        }
        val theme = getString(ThemeSetting.label(ThemeSetting.load(this)))
        return listOf(getString(R.string.setting_mode, getString(settings.getMode().label))) +
            modes +
            getString(R.string.setting_theme, theme) +
            getString(R.string.setting_quick_phrases, QuickPhrases(this).load().size)
    }

    private fun open(position: Int) {
        val modes = ConnectionMode.entries
        when (position) {
            0 -> MessageMenu.choose(this, getString(R.string.settings_screen_title), modes.map { getString(it.label) }) {
                val mode = modes[it]
                settings.saveMode(mode)
                savedAndRefresh()
                // A mode without an address cannot connect yet: ask for it now (Cancel keeps it unset)
                if (settings.getAddress(mode) == null) {
                    ServerSettingsDialog.show(this, settings, mode, cancelable = true) { savedAndRefresh() }
                }
            }
            in 1..modes.size -> ServerSettingsDialog.show(this, settings, modes[position - 1], cancelable = true) {
                savedAndRefresh()
            }
            modes.size + 1 -> MessageMenu.choose(this, getString(R.string.settings_screen_title),
                ThemeSetting.choices.map { getString(it.second) }) { which ->
                // Android redraws this screen in the new theme, which also refreshes the rows
                ThemeSetting.save(this, ThemeSetting.choices[which].first)
            }
            modes.size + 2 -> QuickPhrasesEditor.show(this, ::refreshRows)
        }
    }

    private fun savedAndRefresh() {
        refreshRows()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun refreshRows() {
        adapter.clear()
        adapter.addAll(rows())
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
            // Slide in from the left edge, like the official app's side menu
            @Suppress("DEPRECATION")  // the replacement needs API 34; the phone has 29
            activity.overridePendingTransition(android.R.anim.slide_in_left, 0)
        }
    }
}
