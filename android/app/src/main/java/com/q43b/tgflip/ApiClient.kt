package com.q43b.tgflip

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
// baseUrl may be missing or invalid (a connection mode not set up yet): screens still
// open from the saved copy, and every request fails with NoServerException.
class ApiClient(baseUrl: String?, val token: String) {
    private val baseUrl = baseUrl?.takeIf { it.toHttpUrlOrNull() != null }

    // false = no usable server address; do not start a live connection
    val hasServer get() = baseUrl != null

    private fun endpoint(path: String) = (baseUrl ?: throw NoServerException()) + path

    private val http = OkHttpClient.Builder()
        // The server is on the home network and answers in milliseconds; when the PC is off,
        // nothing answers at all, so give up sooner than OkHttp's default 10 s
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // notices a dead WebSocket (e.g. Wi-Fi dropped)
        .build()

    // Opens /ws. The listener must send the token as the first message.
    fun openSocket(listener: WebSocketListener): WebSocket {
        val request = Request.Builder().url(endpoint("/ws")).build()
        return http.newWebSocket(request, listener)
    }

    // The functions below block until the server answers, so call them from a background thread.
    fun getChats(limit: Int = 20): List<Chat> {
        val json = JSONArray(call(Request.Builder().url(endpoint("/chats?limit=$limit"))))
        return List(json.length()) { i -> Chat.fromJson(json.getJSONObject(i)) }
    }

    // Topics of a forum group, most recently active first
    fun getTopics(chatId: Long, limit: Int = 50): List<Topic> {
        val json = JSONArray(call(Request.Builder().url(endpoint("/topics/$chatId?limit=$limit"))))
        return List(json.length()) { i -> Topic.fromJson(json.getJSONObject(i)) }
    }

    // In all functions below, topicId = null means an ordinary chat (no topics).

    // Oldest first
    fun getMessages(chatId: Long, topicId: Long?, limit: Int = 20): List<Message> {
        val url = endpoint("/messages/$chatId?limit=$limit") + topicParam(topicId, "&")
        val json = JSONArray(call(Request.Builder().url(url)))
        return List(json.length()) { i -> Message.fromJson(json.getJSONObject(i)) }
    }

    // Returns the new message's id. replyToId = the message being replied to, if any.
    fun sendMessage(chatId: Long, topicId: Long?, text: String, replyToId: Long? = null): Long {
        val body = JSONObject().put("chat_id", chatId).put("text", text)
        if (topicId != null) body.put("topic_id", topicId)
        if (replyToId != null) body.put("reply_to_id", replyToId)
        val request = Request.Builder().url(endpoint("/send")).post(body.toString().toRequestBody(JSON))
        return JSONObject(call(request)).getLong("id")
    }

    // Sends a read receipt to the other side, so only call it on a deliberate key press
    fun markRead(chatId: Long, topicId: Long?) {
        val url = endpoint("/read/$chatId") + topicParam(topicId, "?")
        call(Request.Builder().url(url).post("".toRequestBody(null)))
    }

    fun editMessage(chatId: Long, messageId: Long, text: String) {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("text", text)
        call(Request.Builder().url(endpoint("/edit")).post(body.toString().toRequestBody(JSON)))
    }

    // Which deletes Telegram allows for this message (depends on chat type, age, admin rights)
    fun getDeleteOptions(chatId: Long, messageId: Long): DeleteOptions {
        val json = JSONObject(call(Request.Builder().url(endpoint("/delete_options/$chatId/$messageId"))))
        return DeleteOptions(json.getBoolean("for_me"), json.getBoolean("for_everyone"))
    }

    // forEveryone = also remove it for the other side (Telegram calls this "revoke")
    fun deleteMessage(chatId: Long, messageId: Long, forEveryone: Boolean) {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("revoke", forEveryone)
        call(Request.Builder().url(endpoint("/delete")).post(body.toString().toRequestBody(JSON)))
    }

    // Returns the forwarded copy's id
    fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long): Long {
        val body = JSONObject().put("from_chat_id", fromChatId).put("message_id", messageId)
            .put("to_chat_id", toChatId)
        val request = Request.Builder().url(endpoint("/forward")).post(body.toString().toRequestBody(JSON))
        return JSONObject(call(request)).getLong("id")
    }

    private fun topicParam(topicId: Long?, separator: String) =
        if (topicId == null) "" else "${separator}topic_id=$topicId"

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

// The active connection mode has no (valid) server address
class NoServerException : IOException("No server set")

data class DeleteOptions(val forMe: Boolean, val forEveryone: Boolean)
