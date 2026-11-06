package com.q43b.tgflip

import org.json.JSONObject

// One message, from GET /messages or a live {type: "message"} event (same fields).
// replyToId = the message this one replies to, if any. edited = show "(edited)".
// call = set when this is a call record instead of a text message.
// voice = set for a voice message (text is then its caption, usually empty).
// image = set for a photo, video = set for a video (text is then the caption).
// sender = who wrote it, only for other people's messages in groups; senderId = their user
// id (null for channels posting into a group), for their profile or a private chat.
data class Message(
    val id: Long,
    val text: String,
    val out: Boolean,
    val date: String,
    val replyToId: Long? = null,
    val edited: Boolean = false,
    val call: CallInfo? = null,
    val sender: String? = null,
    val senderId: Long? = null,
    val voice: VoiceInfo? = null,
    val image: ImageInfo? = null,
    val video: VideoInfo? = null,
) {
    // Same keys as the server sends, so saved messages read back with fromJson
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("text", text).put("out", out).put("date", date)
        .put("reply_to_id", replyToId ?: JSONObject.NULL)
        .put("edited", edited)
        .put("call", call?.toJson() ?: JSONObject.NULL)
        .put("sender", sender ?: JSONObject.NULL)
        .put("sender_id", senderId ?: JSONObject.NULL)
        .put("voice", voice?.toJson() ?: JSONObject.NULL)
        .put("image", image?.toJson() ?: JSONObject.NULL)
        .put("video", video?.toJson() ?: JSONObject.NULL)

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
            senderId = if (json.isNull("sender_id")) null else json.getLong("sender_id"),
            voice = json.optJSONObject("voice")?.let(VoiceInfo::fromJson),
            image = json.optJSONObject("image")?.let(ImageInfo::fromJson),
            video = json.optJSONObject("video")?.let(VideoInfo::fromJson),
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

// A voice message: its length in seconds. The sound itself comes from GET /voice.
data class VoiceInfo(val duration: Int) {
    fun toJson(): JSONObject = JSONObject().put("duration", duration)

    companion object {
        fun fromJson(json: JSONObject) = VoiceInfo(duration = json.optInt("duration"))
    }
}

// A photo: its full size in pixels. The picture itself comes from GET /image, shrunk.
data class ImageInfo(val width: Int, val height: Int) {
    fun toJson(): JSONObject = JSONObject().put("w", width).put("h", height)

    companion object {
        fun fromJson(json: JSONObject) = ImageInfo(width = json.optInt("w"), height = json.optInt("h"))
    }
}

// A video: its length in seconds, its size in pixels and its file size in bytes. The file
// itself comes from GET /video.
data class VideoInfo(val duration: Int, val width: Int, val height: Int, val size: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("duration", duration).put("w", width).put("h", height).put("size", size)

    companion object {
        fun fromJson(json: JSONObject) = VideoInfo(
            duration = json.optInt("duration"),
            width = json.optInt("w"),
            height = json.optInt("h"),
            size = json.optLong("size"),
        )
    }
}
