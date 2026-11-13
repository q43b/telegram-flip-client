package com.q43b.tgflip

import android.net.Uri
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.Response
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

// Talks to the server's HTTP API. Every request carries the token header.
// baseUrl may be missing or invalid (a connection mode not set up yet): screens still
// open from the saved copy, and every request fails with NoServerException.
class ApiClient(baseUrl: String?, val token: String) : Backend {
    private val baseUrl = baseUrl?.takeIf { it.toHttpUrlOrNull() != null }

    // false = no usable server address; do not start a live connection
    override val hasServer get() = baseUrl != null

    private fun endpoint(path: String) = (baseUrl ?: throw NoServerException()) + path

    private val http = OkHttpClient.Builder()
        // The server is on the home network and answers in milliseconds; when the PC is off,
        // nothing answers at all, so give up sooner than OkHttp's default 10 s
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // notices a dead WebSocket (e.g. Wi-Fi dropped)
        // The app's version on every request (since 1.4.0; before, only on the live connections),
        // so modules that adapt to the app's version (the server's filters) know it exactly
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("x-app-version", BuildConfig.VERSION_NAME).build())
        }
        .build()

    // Opens /ws and sends the token as its first message. The version header lets the server
    // print which app version connected. Callbacks come on OkHttp's threads.
    override fun openEvents(listener: LiveListener): LiveStream {
        val request = Request.Builder().url(endpoint("/ws"))
            .header("x-app-version", BuildConfig.VERSION_NAME)
            .build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(token)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    listener.onEvent(JSONObject(text))
                } catch (e: JSONException) {
                    // Not something we understand; ignore it
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                listener.onDropped()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onDropped()
        })
        return object : LiveStream {
            override fun close() {
                socket.close(1000, null)
            }
        }
    }

    // Opens /logs (the server's console log, for the "under the hood" screen). The listener
    // must send the token as the first message. The version header tells the server this app
    // knows private and shared servers (from 0.13.0-beta); older apps get one line instead.
    fun openLogSocket(listener: WebSocketListener): WebSocket {
        val request = Request.Builder().url(endpoint("/logs"))
            .header("x-app-version", BuildConfig.VERSION_NAME)
            .build()
        return http.newWebSocket(request, listener)
    }

    // The functions below block until the server answers, so call them from a background thread.
    override fun getChats(limit: Int): List<Chat> {
        val json = JSONArray(call(Request.Builder().url(endpoint("/chats?limit=$limit"))))
        return List(json.length()) { i -> Chat.fromJson(json.getJSONObject(i)) }
    }

    // Only these chats (an app folder), newest activity first
    override fun getChatsByIds(ids: Collection<Long>): List<Chat> {
        if (ids.isEmpty()) return emptyList()
        val json = JSONArray(call(Request.Builder().url(endpoint("/chats?ids=" + ids.joinToString(",")))))
        return List(json.length()) { i -> Chat.fromJson(json.getJSONObject(i)) }
    }

    // Topics of a forum group, most recently active first
    override fun getTopics(chatId: Long, limit: Int): List<Topic> {
        val json = JSONArray(call(Request.Builder().url(endpoint("/topics/$chatId?limit=$limit"))))
        return List(json.length()) { i -> Topic.fromJson(json.getJSONObject(i)) }
    }

    // In all functions below, topicId = null means an ordinary chat (no topics).

    // Oldest first
    override fun getMessages(chatId: Long, topicId: Long?, limit: Int): List<Message> {
        val url = endpoint("/messages/$chatId?limit=$limit") + topicParam(topicId, "&")
        val json = JSONArray(call(Request.Builder().url(url)))
        return List(json.length()) { i -> Message.fromJson(json.getJSONObject(i)) }
    }

    // Returns the new message's id. replyToId = the message being replied to, if any.
    override fun sendMessage(chatId: Long, topicId: Long?, text: String, replyToId: Long?): Long {
        val body = JSONObject().put("chat_id", chatId).put("text", text)
        if (topicId != null) body.put("topic_id", topicId)
        if (replyToId != null) body.put("reply_to_id", replyToId)
        val request = Request.Builder().url(endpoint("/send")).post(body.toString().toRequestBody(JSON))
        return JSONObject(call(request)).getLong("id")
    }

    // Sends a recording (OGG/Opus bytes) as a voice message; returns its id
    override fun sendVoice(chatId: Long, topicId: Long?, audio: ByteArray, seconds: Int, replyToId: Long?): Long {
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
    override fun sendPhoto(
        chatId: Long,
        topicId: Long?,
        photo: ByteArray,
        mimeType: String,
        caption: String,
        replyToId: Long?,
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
    override fun markRead(chatId: Long, topicId: Long?) {
        val url = endpoint("/read/$chatId") + topicParam(topicId, "?")
        call(Request.Builder().url(url).post("".toRequestBody(null)))
    }

    override fun editMessage(chatId: Long, messageId: Long, text: String) {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("text", text)
        call(Request.Builder().url(endpoint("/edit")).post(body.toString().toRequestBody(JSON)))
    }

    // Which deletes Telegram allows for this message (depends on chat type, age, admin rights)
    override fun getDeleteOptions(chatId: Long, messageId: Long): DeleteOptions {
        val json = JSONObject(call(Request.Builder().url(endpoint("/delete_options/$chatId/$messageId"))))
        return DeleteOptions(json.getBoolean("for_me"), json.getBoolean("for_everyone"))
    }

    // forEveryone = also remove it for the other side (Telegram calls this "revoke")
    override fun deleteMessage(chatId: Long, messageId: Long, forEveryone: Boolean) {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("revoke", forEveryone)
        call(Request.Builder().url(endpoint("/delete")).post(body.toString().toRequestBody(JSON)))
    }

    // The plain emoji reactions I can choose in this chat, my most used first; empty when the
    // chat turned reactions off
    override fun getReactions(chatId: Long): List<String> {
        val json = JSONObject(call(Request.Builder().url(endpoint("/reactions/$chatId")))).getJSONArray("reactions")
        return List(json.length()) { json.getString(it) }
    }

    // Sets my reaction (replacing any I had), or takes it back with emoji = null. Returns the
    // message's reactions as they are now, or null when Telegram didn't say (the live event will).
    override fun react(chatId: Long, messageId: Long, emoji: String?): List<Reaction>? {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("emoji", emoji ?: JSONObject.NULL)
        val json = JSONObject(call(Request.Builder().url(endpoint("/react")).post(body.toString().toRequestBody(JSON))))
        return if (json.isNull("reactions")) null else Reaction.listFromJson(json.getJSONArray("reactions"))
    }

    // Who has read one of my messages in a group: (name, when it was read, ISO date or null) each.
    // Telegram only tells for small groups and recent messages; otherwise this throws IOException.
    override fun getReadBy(chatId: Long, messageId: Long): List<Pair<String, String?>> {
        val json = JSONObject(call(Request.Builder().url(endpoint("/read_by/$chatId/$messageId")))).getJSONArray("readers")
        return List(json.length()) { i ->
            val reader = json.getJSONObject(i)
            reader.getString("name") to (if (reader.isNull("date")) null else reader.getString("date"))
        }
    }

    // My installed sticker packs (the picker adds Recent and Favorites itself)
    override fun getStickerPacks(): List<StickerPack> {
        val json = JSONObject(call(Request.Builder().url(endpoint("/sticker_sets")))).getJSONArray("packs")
        return List(json.length()) { i ->
            val item = json.getJSONObject(i)
            StickerPack(item.getLong("id").toString(), item.optString("title"), item.optInt("count"))
        }
    }

    // One pack's stickers: "recent", "faved", or a pack's id
    override fun getStickerPack(pack: String): List<StickerInfo> {
        val json = JSONObject(call(Request.Builder().url(endpoint("/sticker_set/$pack")))).getJSONArray("stickers")
        return List(json.length()) { StickerInfo.fromJson(json.getJSONObject(it)) }
    }

    // The pack a sticker comes from, and its stickers (chatId/messageId: the message it came in)
    override fun getStickerPackOf(stickerId: Long, chatId: Long, messageId: Long): Pair<PackInfo, List<StickerInfo>> {
        val json = JSONObject(call(Request.Builder()
            .url(endpoint("/sticker_set_of/$stickerId?chat_id=$chatId&message_id=$messageId"))))
        val pack = json.getJSONObject("pack")
        val stickers = json.getJSONArray("stickers")
        return PackInfo(pack.getLong("id").toString(), pack.optString("title"), pack.optBoolean("installed")) to
            List(stickers.length()) { StickerInfo.fromJson(stickers.getJSONObject(it)) }
    }

    // Adds a sticker to my Telegram Favorites, or takes it out (unfave = true)
    override fun faveSticker(stickerId: Long, unfave: Boolean) {
        call(Request.Builder().url(endpoint("/sticker/$stickerId/fave?unfave=$unfave")).post("".toRequestBody(null)))
    }

    // Adds a sticker to the local favourites (the app's own list on the server, no limit), or
    // takes it out (remove = true)
    override fun localFavorite(stickerId: Long, remove: Boolean) {
        call(Request.Builder().url(endpoint("/sticker/$stickerId/local_favorite?remove=$remove")).post("".toRequestBody(null)))
    }

    // Adds a pack to my packs
    override fun installStickerPack(packId: String) {
        call(Request.Builder().url(endpoint("/sticker_set/$packId/install")).post("".toRequestBody(null)))
    }

    // Returns the new message's id. replyToId = the message being replied to, if any.
    override fun sendSticker(chatId: Long, topicId: Long?, stickerId: Long, replyToId: Long?): Long {
        val body = JSONObject().put("chat_id", chatId).put("sticker_id", stickerId)
        if (topicId != null) body.put("topic_id", topicId)
        if (replyToId != null) body.put("reply_to_id", replyToId)
        val request = Request.Builder().url(endpoint("/send_sticker")).post(body.toString().toRequestBody(JSON))
        return JSONObject(call(request)).getLong("id")
    }

    // Returns the forwarded copy's id
    override fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long): Long {
        val body = JSONObject().put("from_chat_id", fromChatId).put("message_id", messageId)
            .put("to_chat_id", toChatId)
        val request = Request.Builder().url(endpoint("/forward")).post(body.toString().toRequestBody(JSON))
        return JSONObject(call(request)).getLong("id")
    }

    override fun getProfile(userId: Long): Profile =
        Profile.fromJson(JSONObject(call(Request.Builder().url(endpoint("/profile/$userId")))))

    // A group's info with its first `members` members
    override fun getGroup(chatId: Long, members: Int): Group =
        Group.fromJson(JSONObject(call(Request.Builder().url(endpoint("/group/$chatId?members=$members")))))

    // A chat's profile photo as size×size JPEG bytes, or null when it has none.
    // photoId (from /chats) lets the server answer from its own saved copy.
    override fun getPhoto(chatId: Long, photoId: String, size: Int): ByteArray? =
        bytes(endpoint("/photo/$chatId?size=$size&photo_id=$photoId"))

    // A photo from a message as JPEG bytes, shrunk by the server to fit the phone's screen
    override fun getImage(chatId: Long, messageId: Long): ByteArray =
        bytes(endpoint("/image/$chatId/$messageId")) ?: throw IOException("No such photo")

    // A photo's biggest size, unchanged (up to 2560 px, a few MB), for saving on the phone
    override fun getFullImage(chatId: Long, messageId: Long): ByteArray =
        bytes(endpoint("/image/$chatId/$messageId?full=true"), slow) ?: throw IOException("No such photo")

    // A video (also a round one) or a file sent as a document. The server answers only once it has the
    // whole file from Telegram, which can take minutes for a big one, hence its own long wait.
    override fun download(kind: Download.Kind, chatId: Long, messageId: Long): Download {
        val path = when (kind) {
            Download.Kind.VIDEO -> "/video/$chatId/$messageId"
            Download.Kind.FILE -> "/file/$chatId/$messageId"
        }
        val call = videos.newCall(Request.Builder().url(endpoint(path)).header("x-token", token).build())
        return object : Download {
            override fun saveTo(file: File, onProgress: (Long, Long) -> Unit) = downloadTo(call, file, onProgress)
            override fun cancel() = call.cancel()
        }
    }

    // Sends a file as a document: the body is the file itself, streamed from openFile (never held
    // in memory); returns its id. onProgress(bytes sent, total) while it goes to the server,
    // then onSent() while the server passes it on to Telegram.
    override fun sendFile(
        chatId: Long,
        topicId: Long?,
        file: FileSender.Prepared,
        openFile: () -> InputStream,
        caption: String,
        replyToId: Long?,
        onProgress: (Long, Long) -> Unit,
        onSent: () -> Unit,
    ): Long {
        val url = endpoint("/send_file").toHttpUrl().newBuilder()
            .addQueryParameter("chat_id", chatId.toString())
            .addQueryParameter("name", file.name)
            .apply {
                if (caption.isNotEmpty()) addQueryParameter("caption", caption)
                if (topicId != null) addQueryParameter("topic_id", topicId.toString())
                if (replyToId != null) addQueryParameter("reply_to_id", replyToId.toString())
            }
            .build()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            // Unknown up front (-1, sent in chunks): a picker's size isn't always exact
            override fun contentLength() = -1L

            override fun writeTo(sink: BufferedSink) {
                var done = 0L
                openFile().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        onProgress(done, file.size)
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

    // Writes the answer of a download call to file, reporting (bytes so far, total) as it goes.
    // Cancelling the call (from another thread) stops it with an IOException.
    private fun downloadTo(call: Call, file: File, onProgress: (Long, Long) -> Unit) {
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
    override fun sendVideo(
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


    private val videos by lazy { http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build() }

    // A sticker or custom emoji as a still PNG at most size×size, or null when there is
    // nothing to show. chatId and messageId: the message a sticker came in, in case the server
    // has to look again; null for a custom emoji (found by its id alone).
    // animated = true: an animated WebP if the server can make one (its animated stickers
    // module), otherwise it answers with the still PNG as always (so do older servers)
    override fun getSticker(id: Long, size: Int, chatId: Long?, messageId: Long?, animated: Boolean): ByteArray? {
        val from = if (chatId != null && messageId != null) "&chat_id=$chatId&message_id=$messageId" else ""
        val anim = if (animated) "&animated=true" else ""
        return bytes(endpoint("/sticker/$id?size=$size$from$anim"))
    }

    // A voice message's OGG/Opus file
    override fun getVoice(chatId: Long, messageId: Long): ByteArray =
        bytes(endpoint("/voice/$chatId/$messageId")) ?: throw IOException("No such voice message")

    override fun getThumb(chatId: Long, messageId: Long, size: Int): ByteArray? =
        bytes(endpoint("/thumb/$chatId/$messageId?size=$size"))

    override fun vote(chatId: Long, messageId: Long, options: List<Int>): PollInfo {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("options", JSONArray(options))
        val json = JSONObject(call(Request.Builder().url(endpoint("/vote")).post(body.toString().toRequestBody(JSON))))
        return PollInfo.fromJson(json.getJSONObject("poll"))
    }

    override fun getPinned(chatId: Long, topicId: Long?): List<Message> {
        val json = JSONArray(call(Request.Builder().url(endpoint("/pinned/$chatId?limit=50") + topicParam(topicId, "&"))))
        return List(json.length()) { Message.fromJson(json.getJSONObject(it)) }
    }

    override fun pin(chatId: Long, messageId: Long, unpin: Boolean, both: Boolean) {
        val body = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("unpin", unpin).put("both", both)
        call(Request.Builder().url(endpoint("/pin")).post(body.toString().toRequestBody(JSON)))
    }

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

    // About the server (since 0.14.1-beta the app receives this itself, so "server_info" is
    // in ServerMessages.KNOWN_KINDS). null = a server older than 0.13.0-beta, which lacks it.
    fun getServerInfo(): ServerInfo? {
        val request = Request.Builder().url(endpoint("/server_info")).header("x-token", token).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            val json = JSONObject(response.body!!.string())
            val capabilities = json.optJSONObject("capabilities")  // since server 0.15.0-beta
            return ServerInfo(json.getString("version"), json.optString("codename"),
                json.getBoolean("shared"), json.getBoolean("log"),
                capabilities?.keys()?.asSequence()?.associateWith { capabilities.getInt(it) }.orEmpty(),
                json.optString("kind", "telegram"), json.optBoolean("demo", false))  // since server 1.5.0
        }
    }

    // The server's modules (since 0.15.1-beta the app shows them itself, so "modules" is in
    // ServerMessages.KNOWN_KINDS). null = a server older than 0.15.0-beta, which has none.
    fun getModules(): List<ServerModule>? {
        val request = Request.Builder().url(endpoint("/modules")).header("x-token", token).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            val list = JSONObject(response.body!!.string()).getJSONArray("modules")
            return List(list.length()) {
                val m = list.getJSONObject(it)
                val provides = m.optJSONObject("provides")
                val uses = m.optJSONArray("uses")  // since server 1.4.0
                ServerModule(m.getString("name"), m.getString("version"), m.getString("status"),
                    provides?.keys()?.asSequence()?.toList().orEmpty(),
                    uses?.let { list -> List(list.length()) { i -> list.getString(i) } }.orEmpty())
            }
        }
    }

    // The admin channel (since 1.0.0, AdminMessages): admin-only texts, empty for other
    // accounts and from servers older than 1.0.0 (404)
    fun getAdminMessages(): List<String> {
        val request = Request.Builder().url(endpoint("/admin_messages"))
            .header("x-app-knows", ServerMessages.KNOWN_KINDS.joinToString(","))
            .header("x-token", token)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            val list = JSONObject(response.body!!.string()).getJSONArray("messages")
            return List(list.length()) { list.getJSONObject(it).getString("text") }
        }
    }

    // "Messages from the server" (Under the hood): the text of everything the server would
    // tell this app that the app can't receive in its own form (see ServerMessages).
    // null = a server older than 0.14.0-beta, which has no such messages.
    fun getServerMessages(): List<String>? {
        val request = Request.Builder().url(endpoint("/server_messages"))
            .header("x-app-knows", ServerMessages.KNOWN_KINDS.joinToString(","))
            .header("x-token", token)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw BadTokenException()
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Server error ${response.code}")
            val list = JSONObject(response.body!!.string()).getJSONArray("messages")
            return List(list.length()) { list.getJSONObject(it).getString("text") }
        }
    }

    // The themes the server's themes_host module hosts (capability "themes_host", docs/themes-plan.md)
    fun getThemes(): List<ServerTheme> {
        val list = JSONObject(call(Request.Builder().url(endpoint("/modules/themes_host/list")))).getJSONArray("themes")
        return List(list.length()) {
            val t = list.getJSONObject(it)
            ServerTheme(t.getString("id"), t.optString("name", t.getString("id")), t.optString("version"),
                t.optLong("size"), t.getString("sha256"))
        }
    }

    // A theme's zip (at most ThemeInstaller.MAX_BYTES; the list's size says so first)
    fun getThemeZip(id: String): ByteArray =
        bytes(endpoint("/modules/themes_host/${Uri.encode(id)}.zip"), slow) ?: throw IOException("No such theme")

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

// GET /server_info: log = this account's app may watch the live log
// GET /modules: status is "on", "off" or "failed: <reason>"; provides = capability names
data class ServerModule(val name: String, val version: String, val status: String, val provides: List<String>,
                        val uses: List<String> = emptyList())

// capabilities: what the server's modules add, e.g. {"animated_stickers": 1}
// kind, demo (since server 1.5.0): the account's kind ("telegram", or a module's, like "mockgram") and
// whether it is a demo account (not Telegram)
data class ServerInfo(val version: String, val codename: String, val shared: Boolean, val log: Boolean,
                      val capabilities: Map<String, Int> = emptyMap(), val kind: String = "telegram",
                      val demo: Boolean = false)

// GET /modules/themes_host/list: one theme the server hosts; size in bytes, sha256 of its zip
data class ServerTheme(val id: String, val name: String, val version: String, val size: Long, val sha256: String)
