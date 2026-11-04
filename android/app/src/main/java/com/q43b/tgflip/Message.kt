package com.q43b.tgflip

import org.json.JSONObject

// One message, from GET /messages or a live {type: "message"} event (same fields).
data class Message(val id: Long, val text: String, val out: Boolean, val date: String) {
    // Same keys as the server sends, so saved messages read back with fromJson
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("out", out).put("date", date)

    companion object {
        fun fromJson(json: JSONObject) = Message(
            id = json.getLong("id"),
            text = json.optString("text"),
            out = json.getBoolean("out"),
            date = json.getString("date"),
        )
    }
}
