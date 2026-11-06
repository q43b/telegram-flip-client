package com.q43b.tgflip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException

// "Under the hood": the server's console log, live (WebSocket /logs). Opened with OK on
// the version line in Settings. First the server's last lines arrive, then each new one as
// it is printed. The last row says whether it is live; while the bottom row is selected the
// list follows new lines, otherwise it stays where it was. Reconnects by itself while open,
// except on a shared server where this account isn't an admin (close code LOG_PRIVATE).
// Left or Back closes it (the server then prints a line saying the sharing stopped).
class LogActivity : AppCompatActivity() {
    private lateinit var list: WrapListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var api: ApiClient
    private val lines = ArrayDeque<String>()
    private var footer = ""
    private var socket: WebSocket? = null
    private var awaitingKept = false  // the next message is the server's kept lines
    private var retry: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_log)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val settings = ServerSettings(this)
        api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        list = findViewById(R.id.log_list)
        adapter = ArrayAdapter(this, R.layout.log_row, mutableListOf())
        list.adapter = adapter
        list.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        retry?.cancel()
        socket?.close(1000, null)
        socket = null
        super.onStop()
    }

    // Left closes, like the other screens opened from Settings
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun connect() {
        if (!api.hasServer) {
            setFooter(getString(R.string.log_no_server))
            return
        }
        setFooter(getString(R.string.log_connecting))
        awaitingKept = true
        socket = api.openLogSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(api.token)
                runOnUiThread { if (socket === webSocket) setFooter(getString(R.string.log_live)) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try {
                    JSONArray(text)
                } catch (e: JSONException) {
                    return
                }
                val received = List(json.length()) { json.getString(it) }
                runOnUiThread { if (socket === webSocket) addLines(received) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != LOG_PRIVATE) return
                webSocket.close(1000, null)
                runOnUiThread {
                    if (socket !== webSocket) return@runOnUiThread
                    socket = null  // so lost() doesn't try again: the answer won't change
                    lines.clear()
                    setFooter(getString(R.string.log_private))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    // Tries again in a few seconds, as long as the screen is open
    private fun lost(webSocket: WebSocket) = runOnUiThread {
        if (socket !== webSocket) return@runOnUiThread
        socket = null
        setFooter(getString(R.string.log_offline))
        retry = lifecycleScope.launch {
            delay(RETRY_MS)
            connect()
        }
    }

    // Each connection starts with the kept lines (again after a reconnect), so the list
    // starts over from them
    private fun addLines(received: List<String>) {
        if (awaitingKept) {
            lines.clear()
            awaitingKept = false
        }
        lines.addAll(received)
        while (lines.size > MAX_LINES) lines.removeFirst()
        redraw()
    }

    private fun setFooter(text: String) {
        footer = text
        redraw()
    }

    private fun redraw() {
        val selected = list.selectedItemPosition
        val following = selected == AdapterView.INVALID_POSITION || selected >= adapter.count - 1
        adapter.clear()
        adapter.addAll(lines)
        adapter.add(footer)
        if (following) list.setSelection(adapter.count - 1)
    }

    companion object {
        private const val MAX_LINES = 500  // the server keeps as many
        private const val RETRY_MS = 3000L
        private const val LOG_PRIVATE = 4003  // the server's close code (server.py LOG_PRIVATE)

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, LogActivity::class.java))
        }
    }
}
