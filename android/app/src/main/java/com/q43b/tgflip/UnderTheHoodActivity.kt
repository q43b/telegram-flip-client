package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// "Under the hood", the developer mode: tools for looking inside the app and the server.
// Opened with OK on the version line in Settings. Each row opens one tool: Live logs (the
// server's console log, LogActivity) and Messages from the server (ServerMessagesActivity);
// more to come. Left or Back closes it.
// New tools: add a row to rows().
class UnderTheHoodActivity : AppCompatActivity() {
    private var rows = emptyList<SettingRow>()

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
        rows = rows()
        val list = findViewById<WrapListView>(R.id.group_list)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.map { it.label })
        list.setOnItemClickListener { _, _, position, _ -> rows[position].open() }
        list.requestFocus()
    }

    private fun rows() = listOf(
        SettingRow(getString(R.string.under_the_hood_live_logs)) { LogActivity.start(this) },
        SettingRow(getString(R.string.under_the_hood_server_messages)) { ServerMessagesActivity.start(this) },
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
