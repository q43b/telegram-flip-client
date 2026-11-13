package com.q43b.tgflip

import java.io.File
import java.io.IOException
import java.io.InputStream
import org.json.JSONObject

// What the screens ask of Telegram, whoever answers: our server (ApiClient, "Server mode") or, from 2.0,
// TDLib inside the app ("Direct mode", docs/direct-mode-plan.md). The models (Message, Chat, Topic,
// Reaction, StickerInfo...) and the live events' JSON ({type: "message", ...}, see CLAUDE.md) are the
// contract both speak, so the screens don't care which one they have. Things only a server has (its
// info, modules, logs, notices, hosted themes) stay on ApiClient.
// Every function blocks until it has an answer, so call them from a background thread; failures are
// IOExceptions (BadTokenException, NoServerException, TooBigException...).
interface Backend {
    // Somewhere to connect to is set (Server mode: an address for the current connection mode)
    val hasServer: Boolean

    // Live events until closed; callbacks may come on any thread
    fun openEvents(listener: LiveListener): LiveStream

    // A video (also round) or a file sent as a document, fetched whole
    fun download(kind: Download.Kind, chatId: Long, messageId: Long): Download

    fun getChats(limit: Int = 20): List<Chat>
    fun getChatsByIds(ids: Collection<Long>): List<Chat>
    fun getTopics(chatId: Long, limit: Int = 50): List<Topic>
    fun getMessages(chatId: Long, topicId: Long?, limit: Int = 20): List<Message>
    fun sendMessage(chatId: Long, topicId: Long?, text: String, replyToId: Long? = null): Long
    fun sendVoice(chatId: Long, topicId: Long?, audio: ByteArray, seconds: Int, replyToId: Long? = null): Long
    fun sendPhoto(chatId: Long, topicId: Long?, photo: ByteArray, mimeType: String, caption: String, replyToId: Long? = null): Long
    fun markRead(chatId: Long, topicId: Long?)
    fun editMessage(chatId: Long, messageId: Long, text: String)
    fun getDeleteOptions(chatId: Long, messageId: Long): DeleteOptions
    fun deleteMessage(chatId: Long, messageId: Long, forEveryone: Boolean)
    fun getReactions(chatId: Long): List<String>
    fun react(chatId: Long, messageId: Long, emoji: String?): List<Reaction>?
    fun getReadBy(chatId: Long, messageId: Long): List<Pair<String, String?>>
    fun getStickerPacks(): List<StickerPack>
    fun getStickerPack(pack: String): List<StickerInfo>
    fun getStickerPackOf(stickerId: Long, chatId: Long, messageId: Long): Pair<PackInfo, List<StickerInfo>>
    fun faveSticker(stickerId: Long, unfave: Boolean)
    fun localFavorite(stickerId: Long, remove: Boolean)
    fun installStickerPack(packId: String)
    fun sendSticker(chatId: Long, topicId: Long?, stickerId: Long, replyToId: Long?): Long
    fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long): Long
    fun getProfile(userId: Long): Profile
    fun getGroup(chatId: Long, members: Int): Group
    fun getPhoto(chatId: Long, photoId: String, size: Int): ByteArray?
    fun getImage(chatId: Long, messageId: Long): ByteArray
    fun getFullImage(chatId: Long, messageId: Long): ByteArray
    fun sendFile(chatId: Long, topicId: Long?, file: FileSender.Prepared, openFile: () -> InputStream, caption: String, replyToId: Long?, onProgress: (Long, Long) -> Unit, onSent: () -> Unit): Long
    fun sendVideo(chatId: Long, topicId: Long?, video: VideoSender.Prepared, openVideo: () -> InputStream, caption: String, replyToId: Long?, onProgress: (Long, Long) -> Unit, onSent: () -> Unit): Long
    fun getSticker(id: Long, size: Int, chatId: Long?, messageId: Long?, animated: Boolean = false): ByteArray?
    fun getVoice(chatId: Long, messageId: Long): ByteArray
}

interface LiveListener {
    fun onOpen()
    fun onEvent(event: JSONObject)
    fun onDropped()  // closed or failed: the caller reconnects
}

interface LiveStream {
    fun close()
}

// A file on its way to the phone: saveTo blocks until it is written (onProgress(done, total) as it goes,
// total may be -1 when unknown); cancel, from another thread, makes saveTo stop with an IOException
interface Download {
    enum class Kind { VIDEO, FILE }

    fun saveTo(file: File, onProgress: (Long, Long) -> Unit)
    fun cancel()
}

// Over the size limit (the server's, or the phone's)
class TooBigException : IOException("Too big")
