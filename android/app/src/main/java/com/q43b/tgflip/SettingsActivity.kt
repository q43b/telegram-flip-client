package com.q43b.tgflip

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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

// The settings screen: a list of setting groups (Network, Appearance, Media and Cache,
// QoL, Advanced); OK opens a group's own screen (SettingsGroupActivity).
// Opened with Right from the chat list, like swiping open the official app's side menu;
// Left or Back closes it. New settings go into their group in SettingsGroupActivity.
class SettingsActivity : AppCompatActivity() {
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

        val list = findViewById<ListView>(R.id.settings_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows().toMutableList())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> open(position) }
        list.requestFocus()

        // versionName from build.gradle.kts (dev builds add "-dev")
        val version = findViewById<TextView>(R.id.app_version)
        version.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        version.setOnClickListener { openReleaseNotes() }

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

    // This version's page on GitHub, in the phone's browser (dev builds: the version they
    // were made from, without "-dev")
    private fun openReleaseNotes() {
        val tag = "v" + BuildConfig.VERSION_NAME.removeSuffix("-dev")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL + tag)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }

    // The groups, in the order of SettingsGroupActivity.Group; each opens its own screen
    private fun rows(): List<String> = SettingsGroupActivity.Group.entries.map { getString(it.row) }

    private fun open(position: Int) =
        SettingsGroupActivity.start(this, SettingsGroupActivity.Group.entries[position])

    companion object {
        private const val RELEASES_URL = "https://github.com/q43b/telegram-flip-client/releases/tag/"

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
            // Slide in from the left edge, like the official app's side menu
            @Suppress("DEPRECATION")  // the replacement needs API 34; the phone has 29
            activity.overridePendingTransition(android.R.anim.slide_in_left, 0)
        }
    }
}
