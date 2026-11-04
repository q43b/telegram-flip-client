package com.q43b.tgflip

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// Talks to the server's HTTP API. Every request carries the token header.
class ApiClient(private val baseUrl: String, val token: String) {
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)  // notices a dead WebSocket (e.g. Wi-Fi dropped)
        .build()

    // Opens /ws. The listener must send the token as the first message.
    fun openSocket(listener: WebSocketListener): WebSocket {
        val request = Request.Builder().url("$baseUrl/ws").build()
        return http.newWebSocket(request, listener)
    }

    // The functions below block until the server answers, so call them from a background thread.
    fun getChats(limit: Int = 20): List<Chat> {
        val json = JSONArray(call(Request.Builder().url("$baseUrl/chats?limit=$limit")))
        return List(json.length()) { i ->
            val item = json.getJSONObject(i)
            val name = if (item.isNull("name")) "" else item.getString("name")
            Chat(item.getLong("id"), name, item.getInt("unread"))
        }
    }

    // Oldest first
    fun getMessages(chatId: Long, limit: Int = 20): List<Message> {
        val json = JSONArray(call(Request.Builder().url("$baseUrl/messages/$chatId?limit=$limit")))
        return List(json.length()) { i -> Message.fromJson(json.getJSONObject(i)) }
    }

    // Returns the new message's id
    fun sendMessage(chatId: Long, text: String): Long {
        val body = JSONObject().put("chat_id", chatId).put("text", text).toString()
        val request = Request.Builder().url("$baseUrl/send").post(body.toRequestBody(JSON))
        return JSONObject(call(request)).getLong("id")
    }

    private fun call(request: Request.Builder): String {
        http.newCall(request.header("x-token", token).build()).execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            return response.body!!.string()
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

class BadTokenException : IOException("Bad token")
