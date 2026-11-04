package com.q43b.tgflip

import org.json.JSONObject

// One row from GET /chats. Telegram ids can be larger than Int, so id is a Long.
// forum = a group split into topics; it opens a topic list instead of messages.
data class Chat(val id: Long, val name: String, val unread: Int, val forum: Boolean) {
    // Same keys as the server sends, so saved chats read back with fromJson
    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("name", name).put("unread", unread).put("forum", forum)

    companion object {
        fun fromJson(json: JSONObject) = Chat(
            id = json.getLong("id"),
            name = if (json.isNull("name")) "" else json.getString("name"),
            unread = json.getInt("unread"),
            forum = json.optBoolean("forum"),
        )
    }
}
