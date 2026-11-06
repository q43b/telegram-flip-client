package com.q43b.tgflip

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    // The version header lets the server print which app version connected.
    fun openSocket(listener: WebSocketListener): WebSocket {
        val request = Request.Builder().url(endpoint("/ws"))
            .header("x-app-version", BuildConfig.VERSION_NAME)
            .build()
        return http.newWebSocket(request, listener)
    }

    // Opens /logs (the server's console log, for the "under the hood" screen). The listener
    // must send the token as the first message.
    fun openLogSocket(listener: WebSocketListener): WebSocket =
        http.newWebSocket(Request.Builder().url(endpoint("/logs")).build(), listener)

    // The functions below block until the server answers, so call them from a background thread.
    fun getChats(limit: Int = 20): List<Chat> {
        val json = JSONArray(call(Request.Builder().url(endpoint("/chats?limit=$limit"))))
        return List(json.length()) { i -> Chat.fromJson(json.getJSONObject(i)) }
    }

    // Only these chats (an app folder), newest activity first
    fun getChatsByIds(ids: Collection<Long>): List<Chat> {
        if (ids.isEmpty()) return emptyList()
        val json = JSONArray(call(Request.Builder().url(endpoint("/chats?ids=" + ids.joinToString(",")))))
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

    // Sends a recording (OGG/Opus bytes) as a voice message; returns its id
    fun sendVoice(chatId: Long, topicId: Long?, audio: ByteArray, seconds: Int, replyToId: Long? = null): Long {
        var url = endpoint("/send_voice?chat_id=$chatId&duration=$seconds") + topicParam(topicId, "&")
        if (replyToId != null) url += "&reply_to_id=$replyToId"
        val request = Request.Builder().url(url).post(audio.toRequestBody(OGG)).header("x-token", token).build()
        // Telegram takes a few seconds to accept a file through the proxy
        slow.newCall(request).execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            return JSONObject(response.body!!.string()).getLong("id")
        }
    }

    // Sends a picture (JPEG, PNG or WebP bytes) as a photo, with an optional caption;
    // returns its id
    fun sendPhoto(
        chatId: Long,
        topicId: Long?,
        photo: ByteArray,
        mimeType: String,
        caption: String,
        replyToId: Long? = null,
    ): Long {
        val url = endpoint("/send_photo").toHttpUrl().newBuilder()
            .addQueryParameter("chat_id", chatId.toString())
            .apply {
                if (caption.isNotEmpty()) addQueryParameter("caption", caption)
                if (topicId != null) addQueryParameter("topic_id", topicId.toString())
                if (replyToId != null) addQueryParameter("reply_to_id", replyToId.toString())
            }
            .build()
        val request = Request.Builder().url(url).post(photo.toRequestBody(mimeType.toMediaType()))
            .header("x-token", token).build()
        slow.newCall(request).execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            return JSONObject(response.body!!.string()).getLong("id")
        }
    }

    // For big files to or from Telegram: the server passes them through the proxy before it
    // answers, and an original photo of a few MB can take a minute
    private val slow by lazy { http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }

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

    // The plain emoji reactions I can choose in this chat, my most used first; empty when the
    // chat turned reactions off
    fun getReactions(chatId: Long): List<String> {
        val json = JSONObject(call(Request.Builder().url(endpoint("/reactions/$chatId")))).getJSONArray("reactions")
        return List(json.length()) { json.getString(it) }
    }

    // Sets my reaction (replacing any I had), or takes it back with emoji = null. Returns the
    // message's reactions as they are now, or null when Telegram didn't say (the live event will).
    fun react(chatId: Long, messageId: Long, emoji: String?): List<Reaction>? {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("emoji", emoji ?: JSONObject.NULL)
        val json = JSONObject(call(Request.Builder().url(endpoint("/react")).post(body.toString().toRequestBody(JSON))))
        return if (json.isNull("reactions")) null else Reaction.listFromJson(json.getJSONArray("reactions"))
    }

    // My installed sticker packs (the picker adds Recent and Favorites itself)
    fun getStickerPacks(): List<StickerPack> {
        val json = JSONObject(call(Request.Builder().url(endpoint("/sticker_sets")))).getJSONArray("packs")
        return List(json.length()) { i ->
            val item = json.getJSONObject(i)
            StickerPack(item.getLong("id").toString(), item.optString("title"), item.optInt("count"))
        }
    }

    // One pack's stickers: "recent", "faved", or a pack's id
    fun getStickerPack(pack: String): List<StickerInfo> {
        val json = JSONObject(call(Request.Builder().url(endpoint("/sticker_set/$pack")))).getJSONArray("stickers")
        return List(json.length()) { StickerInfo.fromJson(json.getJSONObject(it)) }
    }

    // The pack a sticker comes from, and its stickers (chatId/messageId: the message it came in)
    fun getStickerPackOf(stickerId: Long, chatId: Long, messageId: Long): Pair<PackInfo, List<StickerInfo>> {
        val json = JSONObject(call(Request.Builder()
            .url(endpoint("/sticker_set_of/$stickerId?chat_id=$chatId&message_id=$messageId"))))
        val pack = json.getJSONObject("pack")
        val stickers = json.getJSONArray("stickers")
        return PackInfo(pack.getLong("id").toString(), pack.optString("title"), pack.optBoolean("installed")) to
            List(stickers.length()) { StickerInfo.fromJson(stickers.getJSONObject(it)) }
    }

    // Adds a pack to my packs
    fun installStickerPack(packId: String) {
        call(Request.Builder().url(endpoint("/sticker_set/$packId/install")).post("".toRequestBody(null)))
    }

    // Returns the new message's id. replyToId = the message being replied to, if any.
    fun sendSticker(chatId: Long, topicId: Long?, stickerId: Long, replyToId: Long?): Long {
        val body = JSONObject().put("chat_id", chatId).put("sticker_id", stickerId)
        if (topicId != null) body.put("topic_id", topicId)
        if (replyToId != null) body.put("reply_to_id", replyToId)
        val request = Request.Builder().url(endpoint("/send_sticker")).post(body.toString().toRequestBody(JSON))
        return JSONObject(call(request)).getLong("id")
    }

    // Returns the forwarded copy's id
    fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long): Long {
        val body = JSONObject().put("from_chat_id", fromChatId).put("message_id", messageId)
            .put("to_chat_id", toChatId)
        val request = Request.Builder().url(endpoint("/forward")).post(body.toString().toRequestBody(JSON))
        return JSONObject(call(request)).getLong("id")
    }

    fun getProfile(userId: Long): Profile =
        Profile.fromJson(JSONObject(call(Request.Builder().url(endpoint("/profile/$userId")))))

    // A group's info with its first `members` members
    fun getGroup(chatId: Long, members: Int): Group =
        Group.fromJson(JSONObject(call(Request.Builder().url(endpoint("/group/$chatId?members=$members")))))

    // A chat's profile photo as size×size JPEG bytes, or null when it has none.
    // photoId (from /chats) lets the server answer from its own saved copy.
    fun getPhoto(chatId: Long, photoId: String, size: Int): ByteArray? =
        bytes(endpoint("/photo/$chatId?size=$size&photo_id=$photoId"))

    // A photo from a message as JPEG bytes, shrunk by the server to fit the phone's screen
    fun getImage(chatId: Long, messageId: Long): ByteArray =
        bytes(endpoint("/image/$chatId/$messageId")) ?: throw IOException("No such photo")

    // A photo's biggest size, unchanged (up to 2560 px, a few MB), for saving on the phone
    fun getFullImage(chatId: Long, messageId: Long): ByteArray =
        bytes(endpoint("/image/$chatId/$messageId?full=true"), slow) ?: throw IOException("No such photo")

    // A video's file. The server answers only once it has the whole video from Telegram,
    // which can take minutes for a big one, hence its own long wait. Run it with downloadTo.
    fun videoCall(chatId: Long, messageId: Long): Call =
        videos.newCall(Request.Builder().url(endpoint("/video/$chatId/$messageId")).header("x-token", token).build())

    // Writes the answer of a download call to file, reporting (bytes so far, total) as it goes.
    // Cancelling the call (from another thread) stops it with an IOException.
    fun downloadTo(call: Call, file: File, onProgress: (Long, Long) -> Unit) {
        call.execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (response.code == 413) throw TooBigException()
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            val body = response.body!!
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        }
    }

    // Sends a video: the body is its preview picture followed by the file, streamed from
    // openVideo (never held in memory); returns its id. onProgress(bytes sent, total) while
    // it goes to the server, then onSent() while the server passes it on to Telegram.
    fun sendVideo(
        chatId: Long,
        topicId: Long?,
        video: VideoSender.Prepared,
        openVideo: () -> InputStream,
        caption: String,
        replyToId: Long?,
        onProgress: (Long, Long) -> Unit,
        onSent: () -> Unit,
    ): Long {
        val url = endpoint("/send_video").toHttpUrl().newBuilder()
            .addQueryParameter("chat_id", chatId.toString())
            .addQueryParameter("duration", video.seconds.toString())
            .addQueryParameter("w", video.width.toString())
            .addQueryParameter("h", video.height.toString())
            .addQueryParameter("thumb_size", video.thumb.size.toString())
            .apply {
                if (caption.isNotEmpty()) addQueryParameter("caption", caption)
                if (topicId != null) addQueryParameter("topic_id", topicId.toString())
                if (replyToId != null) addQueryParameter("reply_to_id", replyToId.toString())
            }
            .build()
        val total = video.thumb.size + video.size
        val body = object : RequestBody() {
            override fun contentType() = "video/mp4".toMediaType()

            // Unknown up front (-1, sent in chunks): a picker's size isn't always exact
            override fun contentLength() = -1L

            override fun writeTo(sink: BufferedSink) {
                sink.write(video.thumb)
                var done = video.thumb.size.toLong()
                openVideo().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
                onSent()
            }
        }
        val request = Request.Builder().url(url).post(body).header("x-token", token).build()
        videos.newCall(request).execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (response.code == 413) throw TooBigException()
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            return JSONObject(response.body!!.string()).getLong("id")
        }
    }

    // The server refused: the file is over its size limit
    class TooBigException : IOException("Too big")

    private val videos by lazy { http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build() }

    // A sticker or custom emoji as a still PNG at most size×size, or null when there is
    // nothing to show. chatId and messageId: the message a sticker came in, in case the server
    // has to look again; null for a custom emoji (found by its id alone).
    fun getSticker(id: Long, size: Int, chatId: Long?, messageId: Long?): ByteArray? {
        val from = if (chatId != null && messageId != null) "&chat_id=$chatId&message_id=$messageId" else ""
        return bytes(endpoint("/sticker/$id?size=$size$from"))
    }

    // A voice message's OGG/Opus file
    fun getVoice(chatId: Long, messageId: Long): ByteArray =
        bytes(endpoint("/voice/$chatId/$messageId")) ?: throw IOException("No such voice message")

    // A file's bytes, or null for 404
    private fun bytes(url: String, client: OkHttpClient = http): ByteArray? {
        client.newCall(Request.Builder().url(url).header("x-token", token).build()).execute().use { response ->
            if (response.code == 404) return null
            if (response.code == 401) throw BadTokenException()
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            return response.body!!.bytes()
        }
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
        val OGG = "audio/ogg".toMediaType()
    }
}

class BadTokenException : IOException("Bad token")

// The active connection mode has no (valid) server address
class NoServerException : IOException("No server set")

data class DeleteOptions(val forMe: Boolean, val forEveryone: Boolean)
