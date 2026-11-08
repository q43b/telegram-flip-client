package com.q43b.tgflip

import org.json.JSONObject

// One row from GET /chats. Telegram ids can be larger than Int, so id is a Long.
// forum = a group split into topics; it opens a topic list instead of messages.
// last = the newest message, for the preview line (in a forum: of any topic).
// photo = id of the current profile photo (changes with the photo), null without one.
data class Chat(
    val id: Long,
    val name: String,
    val unread: Int,
    val forum: Boolean,
    val last: LastMessage? = null,
    val photo: String? = null,
) {
    // Same keys as the server sends, so saved chats read back with fromJson
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("unread", unread).put("forum", forum)
        .put("last", last?.toJson() ?: JSONObject.NULL)
        .put("photo", photo ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = Chat(
            id = json.getLong("id"),
            name = if (json.isNull("name")) "" else json.getString("name"),
            unread = json.getInt("unread"),
            forum = json.optBoolean("forum"),
            last = json.optJSONObject("last")?.let(LastMessage::fromJson),
            photo = if (json.isNull("photo")) null else json.getString("photo"),
        )
    }
}

// kind: "text", "media" (no text, e.g. a file), "photo" / "video" / "file" (text = the caption), "round", "sticker" (text = its emoji), "voice" (details in voice), "call" (details in call)
// or "event" (joined, pinned, ...). sender: who wrote it, only for other people's
// messages in groups.
data class LastMessage(
    val text: String,
    val out: Boolean,
    val date: String,
    val sender: String?,
    val kind: String,
    val call: CallInfo? = null,
    val voice: VoiceInfo? = null,
    val file: FileInfo? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text).put("out", out).put("date", date)
        .put("sender", sender ?: JSONObject.NULL).put("kind", kind)
        .put("call", call?.toJson() ?: JSONObject.NULL)
        .put("voice", voice?.toJson() ?: JSONObject.NULL)
        .put("file", file?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = LastMessage(
            text = json.optString("text"),
            out = json.optBoolean("out"),
            date = json.optString("date"),
            sender = if (json.isNull("sender")) null else json.getString("sender"),
            kind = json.optString("kind", "text"),
            call = json.optJSONObject("call")?.let(CallInfo::fromJson),
            voice = json.optJSONObject("voice")?.let(VoiceInfo::fromJson),
            file = json.optJSONObject("file")?.let(FileInfo::fromJson),
        )
    }
}
