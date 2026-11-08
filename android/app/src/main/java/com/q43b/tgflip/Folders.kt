package com.q43b.tgflip

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// The app's own chat folders (not Telegram's): a name and the chats in it, kept only on
// the phone. Chat names are kept too, so the checklists can show chats that aren't loaded.
// hidden: its chats don't show in All chats (an alternative to archiving them).
// countUnread: its chats count toward the total unread number on the folders row.
data class Folder(
    val id: Long,
    val name: String,
    val chats: List<FolderChat>,
    val hidden: Boolean = false,
    val countUnread: Boolean = true,
) {
    val chatIds: Set<Long> get() = chats.map { it.id }.toSet()
}

data class FolderChat(val id: Long, val name: String)

// Stored as a JSON list in the app's private settings file
class Folders(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val key = KEY + Profiles.suffix(context)  // each account has its own folders

    fun load(): List<Folder> {
        val saved = prefs.getString(key, null) ?: return emptyList()
        return try {
            val json = JSONArray(saved)
            List(json.length()) { i ->
                val f = json.getJSONObject(i)
                val chats = f.getJSONArray("chats")
                Folder(
                    id = f.getLong("id"),
                    name = f.getString("name"),
                    chats = List(chats.length()) { j ->
                        val c = chats.getJSONObject(j)
                        FolderChat(c.getLong("id"), c.getString("name"))
                    },
                    // Folders saved before these settings existed get the defaults
                    hidden = f.optBoolean("hidden", false),
                    countUnread = f.optBoolean("count_unread", true),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun find(id: Long): Folder? = load().firstOrNull { it.id == id }

    fun create(name: String): Folder {
        val all = load()
        val folder = Folder((all.maxOfOrNull { it.id } ?: 0) + 1, name, emptyList())
        save(all + folder)
        return folder
    }

    fun rename(id: Long, name: String) = update(id) { it.copy(name = name) }

    fun addChats(id: Long, chats: List<FolderChat>) =
        update(id) { f -> f.copy(chats = f.chats + chats.filter { it.id !in f.chatIds }) }

    fun removeChats(id: Long, chatIds: Set<Long>) =
        update(id) { f -> f.copy(chats = f.chats.filter { it.id !in chatIds }) }

    fun setHidden(id: Long, hidden: Boolean) = update(id) { it.copy(hidden = hidden) }

    fun setCountUnread(id: Long, count: Boolean) = update(id) { it.copy(countUnread = count) }

    fun delete(id: Long) = save(load().filter { it.id != id })

    private fun update(id: Long, change: (Folder) -> Folder) =
        save(load().map { if (it.id == id) change(it) else it })

    private fun save(folders: List<Folder>) {
        val json = JSONArray(folders.map { f ->
            JSONObject().put("id", f.id).put("name", f.name)
                .put("hidden", f.hidden).put("count_unread", f.countUnread)
                .put("chats", JSONArray(f.chats.map { JSONObject().put("id", it.id).put("name", it.name) }))
        })
        prefs.edit().putString(key, json.toString()).apply()
    }

    companion object {
        const val KEY = "folders"
    }
}
