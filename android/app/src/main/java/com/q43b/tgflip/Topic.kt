package com.q43b.tgflip

import org.json.JSONObject

// One topic of a forum group, from GET /topics.
data class Topic(val id: Long, val title: String, val unread: Int) {
    // Same keys as the server sends, so saved topics read back with fromJson
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("unread", unread)

    companion object {
        // Telegram gives every forum's General topic id 1
        const val GENERAL_ID = 1L

        fun fromJson(json: JSONObject) = Topic(
            id = json.getLong("id"),
            title = json.getString("title"),
            unread = json.getInt("unread"),
        )
    }
}
