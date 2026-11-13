package com.q43b.tgflip

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

// The one TDLib client of the app (Direct mode, dev builds until 2.0), for the current account. TDLib
// works on its own thread; everything here is handed to the main thread, so screens can read `auth` and
// `connection` and get told of changes (listen) without locking. Only one client per account folder may
// run, so a restart (another account, new settings) waits until the old one has closed.
object Tdlib {
    private val main = Handler(Looper.getMainLooper())
    private var client: Client? = null
    private var profileId: String? = null
    private var settings: DirectSettings? = null
    private var closing = 0  // clients asked to close that haven't said "closed" yet
    private var pending: (() -> Unit)? = null  // a start waiting for them

    var auth: TdApi.AuthorizationState? = null
        private set
    var connection: TdApi.ConnectionState? = null
        private set
    var lastError: String? = null  // the last refused request, for the screen
        private set
    var errorCount = 0  // goes up with every refusal, so the same words twice still count
        private set

    private var forgetting: String? = null  // an account being removed: log out, close, delete its folders
    private var forgetFolders: List<File> = emptyList()

    private val listeners = mutableSetOf<() -> Unit>()

    fun listen(listener: () -> Unit) { listeners += listener }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    private fun changed() = listeners.toList().forEach { it() }

    init {
        // TDLib's own log: only warnings and errors, into the phone's log (which shows Log.i and up)
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(2)) }
        Client.setLogMessageHandler(2) { level, message -> Log.i("TDLib", "[$level] $message") }
    }

    val running get() = client != null

    fun isFor(id: String) = client != null && profileId == id

    // Starts TDLib for this account (no-op when it already runs for it); another account's client is closed first
    fun start(context: Context, id: String) {
        if (isFor(id)) return
        stop()
        val app = context.applicationContext
        appContext = app
        val begin = {
            val s = DirectSettings(app, id)
            settings = s
            profileId = id
            auth = null
            connection = null
            lastError = null
            lateinit var me: Client
            me = Client.create({ update -> main.post { onUpdate(me, update) } }, null, null)
            client = me
            changed()
        }
        if (closing > 0) pending = begin else begin()
    }

    fun stop() {
        val c = client ?: return
        client = null
        profileId = null
        closing++
        c.send(TdApi.Close()) {}
        auth = null
        connection = null
        changed()
    }

    // New api_id/api_hash or media folder: TDLib reads them only when it starts
    fun restart(context: Context) {
        val id = profileId ?: return
        stop()
        start(context, id)
    }

    private fun onUpdate(from: Client, update: TdApi.Object) {
        if (update is TdApi.UpdateAuthorizationState && update.authorizationState is TdApi.AuthorizationStateClosed && from !== client) {
            // An old client finished closing: a start may have been waiting for it
            closing = (closing - 1).coerceAtLeast(0)
            if (closing == 0) pending?.also { pending = null }?.invoke()
            return
        }
        if (from !== client) return
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuth(from, update.authorizationState)
            is TdApi.UpdateConnectionState -> {
                connection = update.state
                changed()
            }
        }
    }

    // The account is removed from the phone: logged out of Telegram if it was logged in (so the session
    // doesn't stay in Telegram's device list), then its database, files and settings are deleted
    fun forget(context: Context, id: String) {
        val s = DirectSettings(context.applicationContext, id)
        val folders = s.allFolders()
        if (!s.loggedIn && !isFor(id)) {
            folders.forEach { it.deleteRecursively() }
            s.clear()
            return
        }
        forgetting = id
        forgetFolders = folders
        if (isFor(id)) auth?.let { forgetStep(client!!, it) } else start(context, id)
    }

    private fun forgetStep(c: Client, state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateReady -> c.send(TdApi.LogOut()) {}
            is TdApi.AuthorizationStateWaitTdlibParameters -> if (settings?.hasApi != true) c.send(TdApi.Close()) {}
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing, is TdApi.AuthorizationStateClosed -> {}
            else -> c.send(TdApi.Close()) {}
        }
    }

    private fun onAuth(c: Client, state: TdApi.AuthorizationState) {
        val s = settings ?: return
        auth = state
        if (forgetting != null && forgetting == profileId) {
            if (state is TdApi.AuthorizationStateClosed) {
                client = null
                profileId = null
                settings = null
                auth = null
                connection = null
                forgetFolders.forEach { it.deleteRecursively() }
                s.clear()
                forgetting = null
                pending?.also { pending = null }?.invoke()
            } else forgetStep(c, state)
            changed()
            return
        }
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                // Without the user's own api_id/api_hash TDLib can't go on: the screen asks for them
                if (s.hasApi) {
                    c.send(TdApi.SetTdlibParameters(
                        false, s.databaseDir.apply { mkdirs() }.path, s.filesDir.apply { mkdirs() }.path, ByteArray(0),
                        true, true, true, false, s.apiId!!, s.apiHash, "en",
                        Build.MANUFACTURER + " " + Build.MODEL, "Android " + Build.VERSION.RELEASE,
                        "TG Flip " + BuildConfig.VERSION_NAME,
                    )) { r -> if (r is TdApi.Error) main.post { lastError = r.message; errorCount++; changed() } }
                    applyProxy(s.proxy)
                }
            }
            is TdApi.AuthorizationStateReady -> {
                s.loggedIn = true
                c.send(TdApi.GetMe()) { r ->
                    if (r is TdApi.User) main.post {
                        s.me = listOf(r.firstName, r.lastName).filter { it.isNotEmpty() }.joinToString(" ") +
                            if (r.phoneNumber.isNotEmpty()) " (+${r.phoneNumber})" else ""
                        changed()
                    }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber, is TdApi.AuthorizationStateLoggingOut -> s.loggedIn = false
            is TdApi.AuthorizationStateClosed -> {
                // Closed by itself (after logging out): start again, so the screen can log in anew
                client = null
                val id = profileId
                profileId = null
                if (id != null && !s.loggedIn) main.post { if (client == null && pending == null) startAgain(id) }
            }
        }
        changed()
    }

    private var appContext: Context? = null
    private fun startAgain(id: String) = appContext?.let { start(it, id) }

    // The proxy TDLib uses from now on: the old ones removed, the new one added and on (or none)
    fun applyProxy(proxy: DirectSettings.Proxy) {
        val c = client ?: return
        c.send(TdApi.GetProxies()) { r ->
            (r as? TdApi.AddedProxies)?.proxies?.forEach { c.send(TdApi.RemoveProxy(it.id)) {} }
            val p = proxy.toTdlib()
            if (p == null) c.send(TdApi.DisableProxy()) {}
            else c.send(TdApi.AddProxy(p, true, "TG Flip")) { a ->
                if (a is TdApi.Error) main.post { lastError = a.message; errorCount++; changed() }
            }
        }
    }

    // A request whose answer is wanted: TDLib's refusal becomes an exception with its words
    suspend fun call(function: TdApi.Function<*>): TdApi.Object {
        val c = client ?: throw IOException("TDLib isn't running")
        val result = suspendCancellableCoroutine { cont -> c.send(function) { r -> cont.resume(r) } }
        if (result is TdApi.Error) throw TdlibException(result.code, result.message)
        return result
    }

    // A request that only needs sending; a refusal shows on the screen (lastError)
    fun send(function: TdApi.Function<*>) {
        val c = client ?: return
        c.send(function) { r -> if (r is TdApi.Error) main.post { lastError = r.message; errorCount++; changed() } }
    }
}

class TdlibException(val code: Int, message: String) : IOException(message)
