package com.q43b.tgflip

import org.json.JSONArray
import org.json.JSONObject

// One message, from GET /messages or a live {type: "message"} event (same fields).
// replyToId = the message this one replies to, if any. edited = show "(edited)".
// call = set when this is a call record instead of a text message.
// voice = set for a voice message (text is then its caption, usually empty).
// image = set for a photo, video = set for a video (text is then the caption).
// round = set for a round video message. sticker = set for a sticker.
// file = set for a file sent as a document (text is then the caption).
// read = my message: true once the other person has read it (private chats), else null or false.
// gif = set for a GIF, poll = set for a poll (since 1.9.0); pinned = the message is pinned;
// service = "pin" for "X pinned a message" (replyToId is the pinned one), else null.
// customEmoji = where custom emoji sit in text (their plain emoji is in the text there).
// reactions = people's reactions, in Telegram's order.
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
    val round: RoundInfo? = null,
    val reactions: List<Reaction> = emptyList(),
    val sticker: StickerInfo? = null,
    val customEmoji: List<CustomEmoji> = emptyList(),
    val file: FileInfo? = null,
    val read: Boolean? = null,
    val gif: GifInfo? = null,
    val poll: PollInfo? = null,
    val pinned: Boolean = false,
    val service: String? = null,
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
        .put("round", round?.toJson() ?: JSONObject.NULL)
        .put("reactions", Reaction.toJson(reactions))
        .put("sticker", sticker?.toJson() ?: JSONObject.NULL)
        .put("custom_emoji", CustomEmoji.toJson(customEmoji))
        .put("file", file?.toJson() ?: JSONObject.NULL)
        .put("read", read ?: JSONObject.NULL)
        .put("gif", gif?.toJson() ?: JSONObject.NULL)
        .put("poll", poll?.toJson() ?: JSONObject.NULL)
        .put("pinned", pinned)
        .put("service", service ?: JSONObject.NULL)

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
            round = json.optJSONObject("round")?.let(RoundInfo::fromJson),
            reactions = Reaction.listFromJson(json.optJSONArray("reactions")),
            sticker = json.optJSONObject("sticker")?.let(StickerInfo::fromJson),
            customEmoji = CustomEmoji.listFromJson(json.optJSONArray("custom_emoji")),
            file = json.optJSONObject("file")?.let(FileInfo::fromJson),
            read = if (json.isNull("read")) null else json.getBoolean("read"),
            gif = json.optJSONObject("gif")?.let(GifInfo::fromJson),
            poll = json.optJSONObject("poll")?.let(PollInfo::fromJson),
            pinned = json.optBoolean("pinned"),
            service = if (json.isNull("service")) null else json.getString("service"),
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

// A file sent as a document: its name (may be empty), extension with the dot (".docx", may be
// empty), size in bytes and type. The file itself comes from GET /file.
data class FileInfo(val name: String, val ext: String, val size: Long, val mime: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("ext", ext).put("size", size).put("mime", mime)

    companion object {
        fun fromJson(json: JSONObject) = FileInfo(
            name = json.optString("name"),
            ext = json.optString("ext"),
            size = json.optLong("size"),
            mime = json.optString("mime"),
        )
    }
}

// A GIF: its size in pixels, length in seconds and file size. Telegram keeps GIFs as small silent MP4s
// (GET /video), with a still picture of it (GET /thumb).
data class GifInfo(val width: Int, val height: Int, val duration: Int, val size: Long) {
    fun toJson(): JSONObject = JSONObject().put("w", width).put("h", height).put("duration", duration).put("size", size)

    companion object {
        fun fromJson(json: JSONObject) = GifInfo(json.optInt("w"), json.optInt("h"), json.optInt("duration"), json.optLong("size"))
    }
}

// One answer of a poll: votes is null while Telegram doesn't show the results (until I vote, or it
// closes); chosen = mine; correct = the right answer of a quiz, once known.
data class PollAnswer(val text: String, val votes: Int?, val chosen: Boolean, val correct: Boolean)

// A poll. public = the voters are shown (not anonymous); quiz = one answer is right (solution: the
// explanation, once known); revoting = a vote can be taken back; total = how many voted (null: unknown).
data class PollInfo(
    val question: String,
    val answers: List<PollAnswer>,
    val total: Int?,
    val closed: Boolean,
    val public: Boolean,
    val multiple: Boolean,
    val quiz: Boolean,
    val revoting: Boolean,
    val solution: String?,
) {
    val voted get() = answers.any { it.chosen }

    fun toJson(): JSONObject = JSONObject()
        .put("question", question)
        .put("answers", JSONArray().apply {
            answers.forEach {
                put(JSONObject().put("text", it.text).put("votes", it.votes ?: JSONObject.NULL)
                    .put("chosen", it.chosen).put("correct", it.correct))
            }
        })
        .put("total", total ?: JSONObject.NULL).put("closed", closed).put("public", public)
        .put("multiple", multiple).put("quiz", quiz).put("revoting", revoting)
        .put("solution", solution ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): PollInfo {
            val list = json.optJSONArray("answers") ?: JSONArray()
            return PollInfo(
                question = json.optString("question"),
                answers = List(list.length()) { i ->
                    val a = list.getJSONObject(i)
                    PollAnswer(a.optString("text"), if (a.isNull("votes")) null else a.getInt("votes"),
                        a.optBoolean("chosen"), a.optBoolean("correct"))
                },
                total = if (json.isNull("total")) null else json.getInt("total"),
                closed = json.optBoolean("closed"),
                public = json.optBoolean("public"),
                multiple = json.optBoolean("multiple"),
                quiz = json.optBoolean("quiz"),
                revoting = json.optBoolean("revoting", true),
                solution = if (json.isNull("solution")) null else json.getString("solution"),
            )
        }
    }
}

// A round video message: its length in seconds and file size in bytes. Played from GET /video.
data class RoundInfo(val duration: Int, val size: Long) {
    fun toJson(): JSONObject = JSONObject().put("duration", duration).put("size", size)

    companion object {
        fun fromJson(json: JSONObject) = RoundInfo(duration = json.optInt("duration"), size = json.optLong("size"))
    }
}

// One reaction under a message: the emoji (for a custom emoji its plain emoji, or "✦";
// "⭐" for paid stars), how many chose it, whether I did, and a custom emoji's id (its picture
// comes from GET /sticker), else null
data class Reaction(val emoji: String, val count: Int, val mine: Boolean, val customId: Long? = null) {
    companion object {
        fun toJson(reactions: List<Reaction>) = JSONArray().apply {
            reactions.forEach {
                put(JSONObject().put("emoji", it.emoji).put("count", it.count).put("mine", it.mine)
                    .put("custom_id", it.customId ?: JSONObject.NULL))
            }
        }

        // Missing (older saved copies, call records) = none
        fun listFromJson(json: JSONArray?): List<Reaction> {
            if (json == null) return emptyList()
            return List(json.length()) { i ->
                val item = json.getJSONObject(i)
                Reaction(item.optString("emoji"), item.optInt("count"), item.optBoolean("mine"),
                    if (item.isNull("custom_id")) null else item.getLong("custom_id"))
            }
        }
    }
}

// A sticker: its id (for GET /sticker), the emoji it stands for, and its kind: "static",
// "animated" or "video"
data class StickerInfo(val id: Long, val emoji: String, val kind: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("emoji", emoji).put("kind", kind)

    companion object {
        fun fromJson(json: JSONObject) = StickerInfo(
            id = json.optLong("id"),
            emoji = json.optString("emoji"),
            kind = json.optString("kind", "static"),
        )
    }
}

// A custom emoji in a message's text: where it sits (offset and length in the text, which holds
// its plain emoji there) and its id, for its picture from GET /sticker
data class CustomEmoji(val offset: Int, val length: Int, val id: Long) {
    companion object {
        fun toJson(list: List<CustomEmoji>) = JSONArray().apply {
            list.forEach { put(JSONObject().put("offset", it.offset).put("length", it.length).put("id", it.id)) }
        }

        // Missing (older saved copies) = none
        fun listFromJson(json: JSONArray?): List<CustomEmoji> {
            if (json == null) return emptyList()
            return List(json.length()) { i ->
                val item = json.getJSONObject(i)
                CustomEmoji(item.optInt("offset"), item.optInt("length"), item.optLong("id"))
            }
        }
    }
}
