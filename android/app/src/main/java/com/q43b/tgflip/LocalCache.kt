package com.q43b.tgflip

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

// Keeps the last chats, topics and messages the server sent, so screens can show them
// at once and while offline. One small JSON file per list.
// Stored in noBackupFilesDir: private to the app and never copied into phone backups,
// since it holds message text. Reads and writes files, so call from a background thread.
class LocalCache(context: Context) {
    private val dir = File(context.noBackupFilesDir, "cache")

    fun loadChats(): List<Chat>? = load("chats", Chat::fromJson)

    fun saveChats(chats: List<Chat>) = save("chats", chats.map(Chat::toJson))

    fun loadTopics(chatId: Long): List<Topic>? = load("topics_$chatId", Topic::fromJson)

    fun saveTopics(chatId: Long, topics: List<Topic>) = save("topics_$chatId", topics.map(Topic::toJson))

    fun loadMessages(chatId: Long, topicId: Long?): List<Message>? =
        load(messagesName(chatId, topicId), Message::fromJson)

    // Only the newest ones, so a busy chat's file stays small
    fun saveMessages(chatId: Long, topicId: Long?, messages: List<Message>) =
        save(messagesName(chatId, topicId), messages.takeLast(MAX_MESSAGES).map(Message::toJson))

    private fun messagesName(chatId: Long, topicId: Long?) =
        if (topicId == null) "messages_$chatId" else "messages_${chatId}_$topicId"

    // null = nothing saved yet (or the file is unreadable)
    private fun <T> load(name: String, parse: (JSONObject) -> T): List<T>? = synchronized(LOCK) {
        try {
            val json = JSONArray(String(file(name).readFully(), Charsets.UTF_8))
            List(json.length()) { i -> parse(json.getJSONObject(i)) }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    // AtomicFile writes to a side file first, so a crash mid-write never leaves half a file
    private fun save(name: String, items: List<JSONObject>) = synchronized(LOCK) {
        dir.mkdirs()
        val file = file(name)
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            return@synchronized
        }
        try {
            out.write(JSONArray(items).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
        }
    }

    private fun file(name: String) = AtomicFile(File(dir, "$name.json"))

    private companion object {
        const val MAX_MESSAGES = 100
        val LOCK = Any()  // one writer at a time, even across screens
    }
}
