package com.q43b.tgflip

import org.json.JSONObject

// One message, from GET /messages or a live {type: "message"} event (same fields).
// replyToId = the message this one replies to, if any.
data class Message(
    val id: Long,
    val text: String,
    val out: Boolean,
    val date: String,
    val replyToId: Long? = null,
) {
    // Same keys as the server sends, so saved messages read back with fromJson
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("text", text).put("out", out).put("date", date)
        .put("reply_to_id", replyToId ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = Message(
            id = json.getLong("id"),
            text = json.optString("text"),
            out = json.getBoolean("out"),
            date = json.getString("date"),
            // isNull is also true when the key is missing (older saved copies)
            replyToId = if (json.isNull("reply_to_id")) null else json.getLong("reply_to_id"),
        )
    }
}
