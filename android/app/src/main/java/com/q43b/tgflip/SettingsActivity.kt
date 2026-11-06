package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
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
        findViewById<TextView>(R.id.app_version).text =
            getString(R.string.app_version, BuildConfig.VERSION_NAME)

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

    // The groups, in the order of SettingsGroupActivity.Group; each opens its own screen
    private fun rows(): List<String> = SettingsGroupActivity.Group.entries.map { getString(it.row) }

    private fun open(position: Int) =
        SettingsGroupActivity.start(this, SettingsGroupActivity.Group.entries[position])

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
            // Slide in from the left edge, like the official app's side menu
            @Suppress("DEPRECATION")  // the replacement needs API 34; the phone has 29
            activity.overridePendingTransition(android.R.anim.slide_in_left, 0)
        }
    }
}
