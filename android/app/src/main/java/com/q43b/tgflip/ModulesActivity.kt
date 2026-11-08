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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// "Modules" in Under the hood (since 0.15.1-beta): the server's modules (GET /modules), one
// per row: "● Hello world 1.0.0 · on" and what it provides; ○ off, ⚠ failed with the reason.
// Loaded when the screen opens. Left or Back closes it. Modules are installed on the PC
// (maintenance mode), never from here.
class ModulesActivity : AppCompatActivity() {
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings_group)  // a title and a list
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        findViewById<TextView>(R.id.group_title).setText(R.string.modules_title)
        val list = findViewById<WrapListView>(R.id.group_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.requestFocus()
        load()
    }

    private fun load() {
        show(listOf(getString(R.string.server_messages_loading)))
        val settings = ServerSettings(this)
        val api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        lifecycleScope.launch {
            val rows = try {
                val modules = withContext(Dispatchers.IO) { api.getModules() }
                when {
                    modules == null -> listOf(getString(R.string.modules_old_server))
                    modules.isEmpty() -> listOf(getString(R.string.modules_none))
                    else -> modules.map(::row)
                }
            } catch (e: BadTokenException) {
                listOf(getString(R.string.error_bad_token))
            } catch (e: IOException) {
                listOf(getString(R.string.error_no_server))
            }
            show(rows)
        }
    }

    private fun row(module: ServerModule): String {
        val mark = when {
            module.status == "on" -> "●"
            module.status == "off" -> "○"
            else -> "⚠"
        }
        var line = "$mark ${module.name} ${module.version} · ${module.status}"
        if (module.provides.isNotEmpty()) line += "\n" + getString(R.string.modules_provides, module.provides.joinToString(", "))
        // What it may do with the traffic (since server 1.4.0: module.json "uses")
        if (module.uses.isNotEmpty()) line += "\n" + getString(R.string.modules_uses, module.uses.joinToString(", "))
        return line
    }

    private fun show(rows: List<String>) {
        adapter.clear()
        adapter.addAll(rows)
    }

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
            activity.startActivity(Intent(activity, ModulesActivity::class.java))
        }
    }
}
