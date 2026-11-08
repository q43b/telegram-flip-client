package com.q43b.tgflip

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
import java.io.IOException

// One row of a settings group: what it shows, and what OK on it does
class SettingRow(val label: String, val open: () -> Unit)

// One group of Settings on its own screen: Network, Appearance, Media and Cache, QoL
// (quality of life: quick phrases), Advanced.
// Rows are rebuilt after every change (refresh) and when the screen is shown again.
// Choosing between several values (a mode, a theme, a limit) still opens a short list.
// Left or Back goes back to Settings. New settings: add a row to their group's rows.
class SettingsGroupActivity : AppCompatActivity() {
    // row = its line in Settings, title = its screen's title
    enum class Group(@StringRes val row: Int, @StringRes val title: Int) {
        NETWORK(R.string.group_network, R.string.group_network_title),
        APPEARANCE(R.string.group_appearance, R.string.group_appearance_title),
        MEDIA_CACHE(R.string.group_media_cache, R.string.group_media_cache_title),
        QOL(R.string.group_qol, R.string.group_qol_title),
        ADVANCED(R.string.group_advanced, R.string.group_advanced_title),
    }

    private lateinit var group: Group
    private lateinit var settings: ServerSettings
    private lateinit var adapter: ArrayAdapter<String>
    private var rows = emptyList<SettingRow>()
    private var cacheBytes = 0L  // the media cache's size, for "Clear media cache (size)"
    private var stickerBytes = 0L  // the sticker cache's, while it is separate
    private var serverThemes = false  // Appearance: the server's modules provide themes

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
        if (group == Group.APPEARANCE) {
            // "Get themes from the server" only where it can work; quietly nothing otherwise
            lifecycleScope.launch {
                serverThemes = try {
                    withContext(Dispatchers.IO) { ApiClient(settings.getAddress(), settings.getToken().orEmpty()).getServerInfo() }
                        ?.capabilities?.containsKey(ThemeMenu.CAPABILITY) == true
                } catch (e: IOException) {
                    false
                }
                showRows()
            }
        }
        if (group == Group.MEDIA_CACHE) {
            lifecycleScope.launch {
                cacheBytes = withContext(Dispatchers.IO) { MediaCache.sizeBytes(this@SettingsGroupActivity) }
                stickerBytes = withContext(Dispatchers.IO) { MediaCache.stickerBytes(this@SettingsGroupActivity) }
                showRows()
            }
        }
    }

    private fun showRows() {
        rows = when (group) {
            Group.NETWORK -> networkRows()
            Group.APPEARANCE -> appearanceRows()
            Group.MEDIA_CACHE -> stickerRows() + CacheMenu.rows(this, cacheBytes, stickerBytes, ::refresh)
            Group.QOL -> qolRows()
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
        // Which saved account the app talks as; its own token, addresses, chats and folders
        val account = SettingRow(getString(R.string.setting_account, Profiles.current(this).name)) {
            AccountMenu.show(this) { saved() }
        }
        return listOf(account, mode) + servers
    }

    // The theme (docs/themes-plan.md), light or dark, the UI size, and the server's themes
    private fun appearanceRows(): List<SettingRow> {
        val lightDark = getString(ThemeSetting.label(ThemeSetting.load(this)))
        val theme = Themes.current(this)
        // After a theme change the app starts over and comes back here
        val back = listOf(Intent(this, SettingsActivity::class.java), intentFor(this, Group.APPEARANCE))
        val fromServer = SettingRow(getString(R.string.theme_server_row)) { ThemeMenu.fromServer(this, back) }
        return listOf(
            SettingRow(getString(R.string.setting_look, theme.name)) { ThemeMenu.pick(this, back) },
            SettingRow(getString(R.string.setting_theme, lightDark)) {
                MessageMenu.choose(this, getString(R.string.group_appearance_title),
                    ThemeSetting.choices.map { getString(it.second) }) { which ->
                    // Android redraws this screen in the new theme, which also refreshes the rows
                    ThemeSetting.save(this, ThemeSetting.choices[which].first)
                }
            },
            // Every size in the theme times this: smaller fits more on the screen
            SettingRow(getString(R.string.setting_ui_size, Themes.uiSize(this))) {
                val labels = Themes.UI_SIZES.map { getString(R.string.ui_size_choice, it) } + getString(R.string.cache_custom)
                MessageMenu.choose(this, getString(R.string.ui_size_title), labels) { which ->
                    if (which < Themes.UI_SIZES.size) {
                        setUiSize(Themes.UI_SIZES[which])
                    } else {
                        askUiSize()
                    }
                }
            },
        ) + listOfNotNull(fromServer.takeIf { serverThemes })
    }

    // Custom UI size: a whole percent within Themes.UI_SIZE_RANGE; anything else changes nothing
    private fun askUiSize() {
        val range = Themes.UI_SIZE_RANGE
        MessageMenu.askText(this, getString(R.string.ui_size_custom, range.first, range.last),
            Themes.uiSize(this).toString(), InputType.TYPE_CLASS_NUMBER) { text ->
            val percent = text.toIntOrNull()
            if (percent != null && percent in range) {
                setUiSize(percent)
            } else {
                Toast.makeText(this, getString(R.string.ui_size_invalid, range.first, range.last), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setUiSize(percent: Int) {
        Themes.saveUiSize(this, percent)
        Themer.apply(this)
        refresh()
    }

    // How stickers show, and from which packs they may download (docs/stickers-plan.md).
    // Animated is chosen only where it can work: Android 9+, and a server whose modules provide
    // animated_stickers (asked when chosen). "Only chosen packs" isn't built yet: it says so.
    private fun stickerRows(): List<SettingRow> {
        val mode = StickerSetting.mode(this)
        val packs = StickerSetting.packs(this)
        return listOf(
            SettingRow(getString(R.string.setting_stickers, getString(mode.label))) {
                val modes = StickerSetting.Mode.entries
                MessageMenu.choose(this, getString(R.string.stickers_title), modes.map { getString(it.label) }) { which ->
                    if (modes[which] == StickerSetting.Mode.ANIMATED) {
                        chooseAnimated()
                    } else {
                        StickerSetting.saveMode(this, modes[which])
                        saved()
                    }
                }
            },
            SettingRow(getString(R.string.setting_sticker_packs, getString(packs.label))) {
                val choices = StickerSetting.Packs.entries
                MessageMenu.choose(this, getString(R.string.sticker_packs_title), choices.map { getString(it.label) }) { which ->
                    if (choices[which] == StickerSetting.Packs.CHOSEN) {
                        Toast.makeText(this, R.string.coming_later, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    // Things that save keypresses: quick phrases for now
    private fun qolRows() = listOf(
        SettingRow(getString(R.string.setting_quick_phrases, QuickPhrases(this).load().size)) {
            QuickPhrasesEditor.show(this, ::refresh)
        },
    )

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

    private fun chooseAnimated() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, R.string.stickers_animated_android, Toast.LENGTH_LONG).show()
            return
        }
        val api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        lifecycleScope.launch {
            val capable = try {
                withContext(Dispatchers.IO) { api.getServerInfo() }?.capabilities?.containsKey(StickerSetting.CAPABILITY) == true
            } catch (e: IOException) {
                Toast.makeText(this@SettingsGroupActivity, R.string.error_no_server, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (capable) {
                StickerSetting.saveMode(this@SettingsGroupActivity, StickerSetting.Mode.ANIMATED)
                saved()
            } else {
                Toast.makeText(this@SettingsGroupActivity, R.string.stickers_animated_needs_module, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saved() {
        refresh()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_GROUP = "group"

        fun start(activity: Activity, group: Group) = activity.startActivity(intentFor(activity, group))

        fun intentFor(context: Context, group: Group): Intent =
            Intent(context, SettingsGroupActivity::class.java).putExtra(EXTRA_GROUP, group.ordinal)
    }
}
