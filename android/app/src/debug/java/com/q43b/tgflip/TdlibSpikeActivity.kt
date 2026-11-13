package com.q43b.tgflip

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File

// The 2.0 spike (docs/direct-mode-plan.md, step 2), in dev builds only: does TDLib load on the phone,
// connect to Telegram through a proxy, and what does it cost in memory and storage? It never logs in:
// it stops at "waiting for a phone number". The database stays in phone memory; files go to the SD card
// when there is one (the app's own folder there, no permission needed).
// api_id, api_hash and the proxy can be typed here or handed over from the PC:
//   adb shell am start -n com.q43b.tgflip.debug/com.q43b.tgflip.TdlibSpikeActivity
//       --es api_id 12345 --es api_hash abcdef... --es proxy "tg://proxy?server=...&port=...&secret=..."
class TdlibSpikeActivity : AppCompatActivity() {
    private class Row(val label: String, val action: (() -> Unit)? = null)

    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var list: WrapListView
    private var rows: List<Row> = emptyList()
    private val main = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("tdlib_spike", Context.MODE_PRIVATE) }
    private var client: Client? = null
    private var auth = "not started"
    private var connection = "-"
    private var ping = "-"
    private var storage = "-"
    private var startedAt = 0L
    private var readyAfter = ""
    private var memoryLine = "measuring…"
    private val tdlibVersion by lazy { version() }
    @Volatile private var open = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings_group)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        findViewById<TextView>(R.id.group_title).text = "TDLib test"
        list = findViewById(R.id.group_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.action?.invoke() }
        prefs.edit {
            intent.getStringExtra("api_id")?.let { putString("api_id", it.trim()) }
            intent.getStringExtra("api_hash")?.let { putString("api_hash", it.trim()) }
            intent.getStringExtra("proxy")?.let { putString("proxy", it.trim()) }
        }
        Client.setLogMessageHandler(2) { level, message -> Log.i("TDLib", "[$level] $message") }
        redraw()
        tick()
        measureMemory()
    }

    // Debug.getMemoryInfo reads the whole process's memory map: slow on this phone (it made the screen
    // freeze into "not responding" when done on the screen's thread every second), so it runs apart
    private fun measureMemory() {
        Thread {
            while (open) {
                val line = memory()
                main.post { memoryLine = line }
                Thread.sleep(5000)
            }
        }.apply { isDaemon = true }.start()
    }

    // Memory and storage once a second while the screen is open
    private fun tick() {
        redraw()
        client?.send(TdApi.GetStorageStatisticsFast()) { r ->
            if (r is TdApi.StorageStatisticsFast) main.post {
                storage = "database ${kb(r.databaseSize)}, files ${kb(r.filesSize)} (${r.fileCount}), log ${kb(r.logSize)}"
            }
        }
        main.postDelayed({ tick() }, 1000)
    }

    override fun onDestroy() {
        open = false
        main.removeCallbacksAndMessages(null)
        client?.send(TdApi.Close()) {}
        client = null
        super.onDestroy()
    }

    private fun version(): String = try {
        (Client.execute(TdApi.GetOption("version")) as? TdApi.OptionValueString)?.value ?: "?"
    } catch (e: Throwable) {
        "failed to load: ${e.message}"
    }

    private fun filesDir(): Pair<File, String> {
        val sd = getExternalFilesDirs(null).filterNotNull()
            .firstOrNull { Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        return if (sd != null && prefs.getBoolean("use_sd", true)) File(sd, "tdlib_spike") to "SD card"
        else File(filesDir, "tdlib_spike/files") to "phone"
    }

    private fun databaseDir() = File(filesDir, "tdlib_spike/db")

    private fun memory(): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return "app ${info.totalPss / 1024} MB in use, native heap ${Debug.getNativeHeapAllocatedSize() / 1048576} MB"
    }

    private fun apk(): String {
        val extracted = applicationInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0
        return "APK ${File(applicationInfo.sourceDir).length() / 1048576} MB, library " +
            if (extracted) "unpacked to phone memory" else "used from inside the APK"
    }

    private fun redraw() {
        val apiId = prefs.getString("api_id", "").orEmpty()
        val hasHash = prefs.getString("api_hash", "").orEmpty().isNotEmpty()
        val proxy = parseProxy(prefs.getString("proxy", "").orEmpty())
        val (dir, where) = filesDir()
        val running = client != null
        rows = listOf(
            Row("TDLib $tdlibVersion"),
            Row("api_id: " + apiId.ifEmpty { "not set" }) { if (!running) askApiId() },
            Row("api_hash: " + if (hasHash) "set" else "not set") { if (!running) askApiHash() },
            Row("Proxy: " + (proxy?.let { describe(it) } ?: "none")) { if (!running) askProxy() },
            Row("Files on: $where") { if (!running) { prefs.edit { putBoolean("use_sd", where != "SD card") }; redraw() } },
            Row(if (running) "■ Stop TDLib" else "▶ Start TDLib") { if (running) stop() else start() },
            Row("Authorization: $auth"),
            Row("Connection: $connection$readyAfter"),
            Row("Proxy ping: $ping") { pingProxy() },
            Row("Memory: $memoryLine"),
            Row("Storage: $storage"),
            Row(apk()),
            Row("Files folder: ${dir.path}"),
        )
        val selected = list.selectedItemPosition
        adapter.clear()
        adapter.addAll(rows.map { it.label })
        if (selected in rows.indices) list.setSelection(selected)
    }

    private fun start() {
        val apiId = prefs.getString("api_id", "").orEmpty().toIntOrNull()
        val apiHash = prefs.getString("api_hash", "").orEmpty()
        if (apiId == null || apiHash.isEmpty()) {
            auth = "set api_id and api_hash first"
            redraw()
            return
        }
        Client.execute(TdApi.SetLogVerbosityLevel(2))
        startedAt = System.currentTimeMillis()
        readyAfter = ""
        auth = "starting"
        client = Client.create({ update -> main.post { onUpdate(update, apiId, apiHash) } }, null, null)
        redraw()
    }

    private fun stop() {
        client?.send(TdApi.Close()) {}
        client = null
        auth = "stopped"
        connection = "-"
        redraw()
    }

    private fun onUpdate(update: TdApi.Object, apiId: Int, apiHash: String) {
        val c = client ?: return
        when (update) {
            is TdApi.UpdateAuthorizationState -> when (update.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    auth = "sending parameters"
                    val files = filesDir().first.apply { mkdirs() }
                    c.send(TdApi.SetTdlibParameters(
                        false, databaseDir().apply { mkdirs() }.path, files.path, ByteArray(0),
                        true, true, true, false, apiId, apiHash, "en",
                        Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME,
                    )) { r -> if (r is TdApi.Error) main.post { auth = "parameters refused: ${r.message}"; redraw() } }
                    // The proxy before anything connects
                    val proxy = parseProxy(prefs.getString("proxy", "").orEmpty())
                    if (proxy != null) c.send(TdApi.AddProxy(proxy, true, "TG Flip test")) { r ->
                        if (r is TdApi.Error) main.post { connection = "proxy refused: ${r.message}"; redraw() }
                    } else c.send(TdApi.DisableProxy()) {}
                }
                is TdApi.AuthorizationStateWaitPhoneNumber -> auth = "waiting for a phone number (the test stops here)"
                is TdApi.AuthorizationStateReady -> auth = "logged in (a database from earlier)"
                is TdApi.AuthorizationStateClosed -> auth = "closed"
                else -> auth = update.authorizationState.javaClass.simpleName
            }
            is TdApi.UpdateConnectionState -> {
                connection = when (update.state) {
                    is TdApi.ConnectionStateWaitingForNetwork -> "waiting for the network"
                    is TdApi.ConnectionStateConnectingToProxy -> "connecting to the proxy…"
                    is TdApi.ConnectionStateConnecting -> "connecting to Telegram…"
                    is TdApi.ConnectionStateUpdating -> "updating…"
                    is TdApi.ConnectionStateReady -> "ready ✓"
                    else -> update.state.javaClass.simpleName
                }
                if (update.state is TdApi.ConnectionStateReady && readyAfter.isEmpty())
                    readyAfter = " (after %.1f s)".format((System.currentTimeMillis() - startedAt) / 1000.0)
            }
        }
        redraw()
    }

    private fun pingProxy() {
        val c = client ?: return
        val proxy = parseProxy(prefs.getString("proxy", "").orEmpty()) ?: return
        ping = "pinging…"
        redraw()
        c.send(TdApi.PingProxy(proxy)) { r ->
            main.post {
                ping = if (r is TdApi.Seconds) "%.2f s".format(r.seconds) else "failed: ${(r as? TdApi.Error)?.message}"
                redraw()
            }
        }
    }

    private fun askApiId() = MessageMenu.askText(this, "api_id", prefs.getString("api_id", "").orEmpty(),
        InputType.TYPE_CLASS_NUMBER) { prefs.edit { putString("api_id", it) }; redraw() }

    private fun askApiHash() = MessageMenu.askText(this, "api_hash", prefs.getString("api_hash", "").orEmpty(),
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) { prefs.edit { putString("api_hash", it) }; redraw() }

    private fun askProxy() = MessageMenu.choose(this, "Proxy", listOf("Enter a proxy link…", "No proxy")) { which ->
        if (which == 1) {
            prefs.edit { remove("proxy") }
            redraw()
        } else MessageMenu.askText(this, "tg://proxy?… or tg://socks?…", prefs.getString("proxy", "").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { text ->
            if (parseProxy(text) == null) auth = "that proxy link wasn't understood"
            else prefs.edit { putString("proxy", text) }
            redraw()
        }
    }

    // tg://proxy?server=&port=&secret= (MTProto), tg://socks?server=&port=[&user=&pass=] (SOCKS5),
    // and the same as https://t.me/proxy?… / https://t.me/socks?…
    private fun parseProxy(link: String): TdApi.Proxy? {
        if (link.isBlank()) return null
        val uri = Uri.parse(link.trim())
        val kind = if (uri.scheme == "tg") uri.host else uri.pathSegments.firstOrNull()
        val server = uri.getQueryParameter("server") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        return when (kind) {
            "proxy" -> TdApi.Proxy(server, port, TdApi.ProxyTypeMtproto(uri.getQueryParameter("secret") ?: return null))
            "socks" -> TdApi.Proxy(server, port, TdApi.ProxyTypeSocks5(uri.getQueryParameter("user").orEmpty(), uri.getQueryParameter("pass").orEmpty()))
            else -> null
        }
    }

    private fun describe(p: TdApi.Proxy) = (if (p.type is TdApi.ProxyTypeMtproto) "MTProto " else "SOCKS5 ") + "${p.server}:${p.port}"

    private fun kb(bytes: Long) = if (bytes < 1048576) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(android.content.Intent(activity, TdlibSpikeActivity::class.java))
        }
    }
}
