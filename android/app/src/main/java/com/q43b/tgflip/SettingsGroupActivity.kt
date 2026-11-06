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
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// One row of a settings group: what it shows, and what OK on it does
class SettingRow(val label: String, val open: () -> Unit)

// One group of Settings on its own screen: Network, Appearance, Media and Cache, Advanced.
// Rows are rebuilt after every change (refresh) and when the screen is shown again.
// Choosing between several values (a mode, a theme, a limit) still opens a short list.
// Left or Back goes back to Settings. New settings: add a row to their group's rows.
class SettingsGroupActivity : AppCompatActivity() {
    enum class Group(@StringRes val title: Int) {
        NETWORK(R.string.group_network_title),
        APPEARANCE(R.string.group_appearance_title),
        MEDIA_CACHE(R.string.group_media_cache_title),
        ADVANCED(R.string.group_advanced_title),
    }

    private lateinit var group: Group
    private lateinit var settings: ServerSettings
    private lateinit var adapter: ArrayAdapter<String>
    private var rows = emptyList<SettingRow>()
    private var cacheBytes = 0L  // the media cache's size, for "Clear media cache (size)"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings_group)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        group = Group.entries[intent.getIntExtra(EXTRA_GROUP, 0)]
        settings = ServerSettings(this)
        findViewById<TextView>(R.id.group_title).setText(group.title)
        val list = findViewById<ListView>(R.id.group_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.open?.invoke() }
        list.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // Left goes back, like in Settings
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refresh() {
        showRows()
        if (group == Group.MEDIA_CACHE) {
            lifecycleScope.launch {
                cacheBytes = withContext(Dispatchers.IO) { MediaCache.sizeBytes(this@SettingsGroupActivity) }
                showRows()
            }
        }
    }

    private fun showRows() {
        rows = when (group) {
            Group.NETWORK -> networkRows()
            Group.APPEARANCE -> appearanceRows()
            Group.MEDIA_CACHE -> CacheMenu.rows(this, cacheBytes, ::refresh)
            Group.ADVANCED -> advancedRows()
        }
        adapter.clear()
        adapter.addAll(rows.map { it.label })
    }

    // Which connection mode is active, then each mode's server. The chat list reconnects
    // with new server values when shown again.
    private fun networkRows(): List<SettingRow> {
        val modes = ConnectionMode.entries
        val mode = SettingRow(getString(R.string.setting_mode, getString(settings.getMode().label))) {
            MessageMenu.choose(this, getString(R.string.group_network_title), modes.map { getString(it.label) }) {
                val chosen = modes[it]
                settings.saveMode(chosen)
                saved()
                // A mode without an address cannot connect yet: ask for it now (Cancel keeps it unset)
                if (settings.getAddress(chosen) == null) {
                    ServerSettingsDialog.show(this, settings, chosen, cancelable = true) { saved() }
                }
            }
        }
        val servers = modes.map { m ->
            // The address without "http(s)://", as host:port
            val server = settings.getAddress(m)?.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" }
            SettingRow(getString(R.string.setting_server, getString(m.label), server ?: getString(R.string.not_set))) {
                ServerSettingsDialog.show(this, settings, m, cancelable = true) { saved() }
            }
        }
        return listOf(mode) + servers
    }

    // Only the theme for now
    private fun appearanceRows(): List<SettingRow> {
        val theme = getString(ThemeSetting.label(ThemeSetting.load(this)))
        return listOf(SettingRow(getString(R.string.setting_theme, theme)) {
            MessageMenu.choose(this, getString(R.string.group_appearance_title),
                ThemeSetting.choices.map { getString(it.second) }) { which ->
                // Android redraws this screen in the new theme, which also refreshes the rows
                ThemeSetting.save(this, ThemeSetting.choices[which].first)
            }
        })
    }

    // Settings for this phone's limits: the voice format for older Android, and whether the
    // phone shows soft key labels itself. OK switches a row on and off.
    private fun advancedRows() = listOf(
        SettingRow(getString(R.string.setting_voice_compatible, onOff(VoiceFormat.isCompatible(this)))) {
            VoiceFormat.saveCompatible(this, !VoiceFormat.isCompatible(this))
            val note = if (VoiceFormat.isCompatible(this)) R.string.voice_compatible_on else R.string.saved
            Toast.makeText(this, note, Toast.LENGTH_LONG).show()
            refresh()
        },
        // Open screens pick it up when shown again
        SettingRow(getString(R.string.setting_feature_phone, onOff(SoftKeySetting.isOn(this)))) {
            SoftKeySetting.save(this, !SoftKeySetting.isOn(this))
            saved()
        },
    )

    private fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    private fun saved() {
        refresh()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_GROUP = "group"

        fun start(activity: Activity, group: Group) {
            activity.startActivity(
                Intent(activity, SettingsGroupActivity::class.java).putExtra(EXTRA_GROUP, group.ordinal)
            )
        }
    }
}
