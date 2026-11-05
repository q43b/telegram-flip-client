package com.q43b.tgflip

import org.json.JSONObject

// One message, from GET /messages or a live {type: "message"} event (same fields).
// replyToId = the message this one replies to, if any. edited = show "(edited)".
// call = set when this is a call record instead of a text message.
// sender = who wrote it, only for other people's messages in groups.
data class Message(
    val id: Long,
    val text: String,
    val out: Boolean,
    val date: String,
    val replyToId: Long? = null,
    val edited: Boolean = false,
    val call: CallInfo? = null,
    val sender: String? = null,
) {
    // Same keys as the server sends, so saved messages read back with fromJson
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("text", text).put("out", out).put("date", date)
        .put("reply_to_id", replyToId ?: JSONObject.NULL)
        .put("edited", edited)
        .put("call", call?.toJson() ?: JSONObject.NULL)
        .put("sender", sender ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = Message(
            id = json.getLong("id"),
            text = json.optString("text"),
            out = json.getBoolean("out"),
            date = json.getString("date"),
            // isNull is also true when the key is missing (older saved copies)
            replyToId = if (json.isNull("reply_to_id")) null else json.getLong("reply_to_id"),
            edited = json.optBoolean("edited"),
            call = json.optJSONObject("call")?.let(CallInfo::fromJson),
            sender = if (json.isNull("sender")) null else json.getString("sender"),
        )
    }
}

// A call record. reason: "missed", "busy", "hangup", "disconnect" or null (unknown).
// duration: seconds talked, null if the call was never answered.
data class CallInfo(val video: Boolean, val reason: String?, val duration: Int?) {
    fun toJson(): JSONObject = JSONObject()
        .put("video", video)
        .put("reason", reason ?: JSONObject.NULL)
        .put("duration", duration ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = CallInfo(
            video = json.optBoolean("video"),
            reason = if (json.isNull("reason")) null else json.getString("reason"),
            duration = if (json.isNull("duration")) null else json.getInt("duration"),
        )
    }
}
