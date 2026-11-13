package com.q43b.tgflip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// Keeps the live events coming (Backend.openEvents: our server's /ws, or in Direct mode the app's own
// Telegram connection) and reconnects after drops. The backend calls the listener on its own threads;
// every callback hops onto `scope` (the screen's main thread), so the fields below are only touched
// from there.
class LiveConnection(
    private val api: Backend,
    private val scope: CoroutineScope,
    private val onStatus: (Status) -> Unit,
    private val onEvent: (JSONObject) -> Unit,
) {
    enum class Status { CONNECTING, CONNECTED, OFFLINE }

    private var stream: LiveStream? = null
    private var reconnectJob: Job? = null
    private var retryDelayMs = MIN_RETRY_MS
    private var offline = false  // dropped and not back yet: retries keep showing OFFLINE

    fun start() {
        if (stream == null && reconnectJob == null) connect()
    }

    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        stream?.close()
        stream = null
    }

    private fun connect() {
        // CONNECTING only for the first try; a retry would flash gray while the server is down
        if (!offline) onStatus(Status.CONNECTING)
        // Callbacks hop onto the main thread, where this runs, so `mine` is set before any of them look
        var mine: LiveStream? = null
        mine = api.openEvents(object : LiveListener {
            override fun onOpen() {
                scope.launch {
                    if (mine !== stream) return@launch
                    retryDelayMs = MIN_RETRY_MS
                    offline = false
                    onStatus(Status.CONNECTED)
                }
            }

            override fun onEvent(event: JSONObject) {
                scope.launch {
                    if (mine === stream) onEvent(event)
                }
            }

            override fun onDropped() = dropped { mine }
        })
        stream = mine
    }

    // Ignores streams we already replaced or stopped, so one drop schedules one retry
    private fun dropped(which: () -> LiveStream?) {
        scope.launch {
            if (which() !== stream) return@launch
            stream = null
            offline = true
            onStatus(Status.OFFLINE)
            reconnectJob = scope.launch {
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
                reconnectJob = null
                connect()
            }
        }
    }

    private companion object {
        const val MIN_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 30_000L
    }
}
