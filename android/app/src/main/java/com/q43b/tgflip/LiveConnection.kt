package com.q43b.tgflip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

// Keeps a WebSocket to the server open and reconnects after drops.
// OkHttp calls the listener on its own threads; every callback hops onto `scope`
// (the screen's main thread), so the fields below are only touched from there.
class LiveConnection(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val onStatus: (Status) -> Unit,
    private val onEvent: (JSONObject) -> Unit,
) {
    enum class Status { CONNECTING, CONNECTED, OFFLINE }

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var retryDelayMs = MIN_RETRY_MS

    fun start() {
        if (socket == null && reconnectJob == null) connect()
    }

    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, null)
        socket = null
    }

    private fun connect() {
        onStatus(Status.CONNECTING)
        socket = api.openSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(api.token)
                scope.launch {
                    if (webSocket !== socket) return@launch
                    retryDelayMs = MIN_RETRY_MS
                    onStatus(Status.CONNECTED)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (webSocket !== socket) return@launch
                    try {
                        onEvent(JSONObject(text))
                    } catch (e: JSONException) {
                        // Not something we understand; ignore it
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                dropped(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropped(webSocket)
            }
        })
    }

    // Ignores sockets we already replaced or stopped, so one drop schedules one retry
    private fun dropped(webSocket: WebSocket) {
        scope.launch {
            if (webSocket !== socket) return@launch
            socket = null
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
