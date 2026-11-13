package com.q43b.tgflip

import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

// Direct mode's "server in the phone": turns TDLib's objects into exactly the JSON our server sends
// (server/tgflip/messages.py and routes/chats.py; the function names follow it), so the screens read
// both with the same Message.fromJson / Chat.fromJson and can't tell the two apart.
// Ids: TDLib numbers messages as Telegram's id × 2^20 (room for its own unsent ones); the app always sees
// Telegram's (appId / tdId). Chat ids are the same as Telethon's (users +, groups -, supergroups -100…).
object TdlibJson {
    private const val SHIFT = 20

    fun appId(tdId: Long) = tdId shr SHIFT
    fun tdId(appId: Long) = appId shl SHIFT

    // What TDLib has told us about chats, users and supergroups (filled by TdlibCache from its updates)
    val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    val users = ConcurrentHashMap<Long, TdApi.User>()
    val supergroups = ConcurrentHashMap<Long, TdApi.Supergroup>()
    @Volatile var myId = 0L

    // Like Python's isoformat() of the server's UTC dates: 2026-11-13T14:05:00+00:00
    fun date(seconds: Int): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(seconds * 1000L)) + "+00:00"

    private fun JSONObject.putOrNull(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    // ---- chats (routes/chats.py chat_json) ----

    fun isForum(chat: TdApi.Chat): Boolean =
        (chat.type as? TdApi.ChatTypeSupergroup)?.let { supergroups[it.supergroupId]?.isForum } == true

    fun isPrivate(chat: TdApi.Chat?) = chat?.type is TdApi.ChatTypePrivate || chat?.type is TdApi.ChatTypeSecret

    fun isChannel(chat: TdApi.Chat?) = (chat?.type as? TdApi.ChatTypeSupergroup)?.isChannel == true

    // A photo's id for the app: changes with the photo (the server sends Telegram's photo id)
    fun photoIdOf(chat: TdApi.Chat): String? = chat.photo?.small?.remote?.uniqueId?.takeIf { it.isNotEmpty() }

    fun chatJson(chat: TdApi.Chat): JSONObject = JSONObject()
        .put("id", chat.id)
        .put("name", chat.title)
        .put("unread", chat.unreadCount)
        .put("forum", isForum(chat))
        .putOrNull("last", chat.lastMessage?.let(::lastMessagePreview))
        .putOrNull("photo", photoIdOf(chat))

    // ---- topics (messages.py topic_of, routes/chats.py /topics) ----

    // Forum topics keep Telegram's own ids (General is 1); a message's topic, null for General and
    // ordinary chats, like the server's topic_of
    fun topicOf(m: TdApi.Message): Long? =
        (m.topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong()?.takeIf { it != Topic.GENERAL_ID }

    fun topicJson(t: TdApi.ForumTopic): JSONObject = JSONObject()
        .put("id", if (t.info.isGeneral) Topic.GENERAL_ID else t.info.forumTopicId.toLong())
        .put("title", t.info.name)
        .put("unread", t.unreadCount)

    // ---- people and groups (routes/chats.py /profile, /group) ----

    fun userName(u: TdApi.User?): String =
        u?.let { listOf(it.firstName, it.lastName).filter { n -> n.isNotEmpty() }.joinToString(" ") }.orEmpty()

    fun userPhotoId(u: TdApi.User?): String? = u?.profilePhoto?.small?.remote?.uniqueId?.takeIf { it.isNotEmpty() }

    fun profileJson(u: TdApi.User): JSONObject = JSONObject()
        .put("id", u.id)
        .put("name", userName(u))
        .putOrNull("username", u.usernames?.activeUsernames?.firstOrNull())
        .putOrNull("phone", u.phoneNumber.takeIf { it.isNotEmpty() }?.let { "+$it" })
        .putOrNull("photo", userPhotoId(u))
        .put("bot", u.type is TdApi.UserTypeBot)

    fun memberJson(m: TdApi.ChatMember): JSONObject? {
        val userId = (m.memberId as? TdApi.MessageSenderUser)?.userId ?: return null
        val u = users[userId]
        return JSONObject().put("id", userId).put("name", userName(u)).putOrNull("photo", userPhotoId(u))
            .putOrNull("role", when (m.status) {
                is TdApi.ChatMemberStatusCreator -> "owner"
                is TdApi.ChatMemberStatusAdministrator -> "admin"
                else -> null
            })
            .put("bot", u?.type is TdApi.UserTypeBot)
    }

    // ---- messages (messages.py message_json) ----

    private fun captionOf(content: TdApi.MessageContent): String = when (content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> content.caption.text
        is TdApi.MessageVideo -> content.caption.text
        is TdApi.MessageDocument -> content.caption.text
        is TdApi.MessageAnimation -> content.caption.text
        is TdApi.MessageVoiceNote -> content.caption.text
        else -> ""
    }

    private fun entitiesOf(content: TdApi.MessageContent): Array<TdApi.TextEntity> = when (content) {
        is TdApi.MessageText -> content.text.entities
        is TdApi.MessagePhoto -> content.caption.entities
        is TdApi.MessageVideo -> content.caption.entities
        is TdApi.MessageDocument -> content.caption.entities
        is TdApi.MessageAnimation -> content.caption.entities
        is TdApi.MessageVoiceNote -> content.caption.entities
        else -> emptyArray()
    }

    // The message this one replies to, in the same chat; null for none
    fun replyOf(m: TdApi.Message): Long? = when (val r = m.replyTo) {
        is TdApi.MessageReplyToMessage -> if (r.chatId == 0L || r.chatId == m.chatId) appId(r.messageId).takeIf { it > 0 } else null
        else -> null
    }.let { reply ->
        // "X pinned a message" points at the pinned one, like the server's
        (m.content as? TdApi.MessagePinMessage)?.let { appId(it.messageId) } ?: reply
    }

    fun callOf(m: TdApi.Message): JSONObject? {
        val c = m.content as? TdApi.MessageCall ?: return null
        val reason = when (c.discardReason) {
            is TdApi.CallDiscardReasonMissed -> "missed"
            is TdApi.CallDiscardReasonDeclined -> "busy"
            is TdApi.CallDiscardReasonHungUp -> "hangup"
            is TdApi.CallDiscardReasonDisconnected -> "disconnect"
            else -> null
        }
        return JSONObject().put("video", c.isVideo).putOrNull("reason", reason).putOrNull("duration", c.duration.takeIf { it > 0 })
    }

    fun voiceOf(m: TdApi.Message): JSONObject? =
        (m.content as? TdApi.MessageVoiceNote)?.let { JSONObject().put("duration", it.voiceNote.duration) }

    fun imageOf(m: TdApi.Message): JSONObject? {
        val sizes = (m.content as? TdApi.MessagePhoto)?.photo?.sizes ?: return null
        val biggest = sizes.maxByOrNull { it.width.toLong() * it.height } ?: return null
        return JSONObject().put("w", biggest.width).put("h", biggest.height)
    }

    private fun size(file: TdApi.File) = if (file.size > 0) file.size else file.expectedSize

    fun videoOf(m: TdApi.Message): JSONObject? {
        val v = (m.content as? TdApi.MessageVideo)?.video ?: return null
        return JSONObject().put("duration", v.duration).put("w", v.width).put("h", v.height).put("size", size(v.video))
    }

    fun roundOf(m: TdApi.Message): JSONObject? {
        val v = (m.content as? TdApi.MessageVideoNote)?.videoNote ?: return null
        return JSONObject().put("duration", v.duration).put("size", size(v.video))
    }

    fun fileOf(m: TdApi.Message): JSONObject? {
        val d = (m.content as? TdApi.MessageDocument)?.document ?: (m.content as? TdApi.MessageAudio)?.audio?.let {
            TdApi.Document(it.fileName, it.mimeType, null, null, it.audio)
        } ?: return null
        val name = d.fileName.orEmpty()
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && name.contains('.') }?.let { ".${it.lowercase()}" } ?: ""
        return JSONObject().put("name", name).put("ext", ext).put("size", size(d.document)).put("mime", d.mimeType.orEmpty())
    }

    fun gifOf(m: TdApi.Message): JSONObject? {
        val a = (m.content as? TdApi.MessageAnimation)?.animation ?: return null
        return JSONObject().put("w", a.width).put("h", a.height).put("duration", a.duration).put("size", size(a.animation))
    }

    fun stickerJson(s: TdApi.Sticker): JSONObject = JSONObject()
        .put("id", s.id).put("emoji", s.emoji)
        .put("kind", when (s.format) {
            is TdApi.StickerFormatTgs -> "animated"
            is TdApi.StickerFormatWebm -> "video"
            else -> "static"
        })

    fun stickerOf(m: TdApi.Message): JSONObject? = (m.content as? TdApi.MessageSticker)?.sticker?.let(::stickerJson)

    fun pollOf(m: TdApi.Message): JSONObject? {
        val p = (m.content as? TdApi.MessagePoll)?.poll ?: return null
        val quiz = p.type as? TdApi.PollTypeQuiz
        val shown = p.canSeeResults || p.isClosed || p.options.any { it.isChosen }
        val answers = JSONArray()
        p.options.forEachIndexed { i, o ->
            answers.put(JSONObject().put("text", o.text.text)
                .putOrNull("votes", if (shown) o.voterCount else null)
                .put("chosen", o.isChosen)
                .put("correct", quiz?.correctOptionIds?.contains(i) == true))
        }
        return JSONObject().put("question", p.question.text).put("answers", answers)
            .putOrNull("total", if (shown) p.totalVoterCount else null)
            .put("closed", p.isClosed).put("public", !p.isAnonymous).put("multiple", p.allowsMultipleAnswers)
            .put("quiz", quiz != null).put("revoting", p.allowsRevoting)
            .putOrNull("solution", quiz?.explanation?.text?.takeIf { it.isNotEmpty() })
    }

    fun serviceOf(m: TdApi.Message): String? = if (m.content is TdApi.MessagePinMessage) "pin" else null

    // Is this a service line (joined, pinned, ...) rather than something someone wrote?
    private fun isService(content: TdApi.MessageContent) = when (content) {
        is TdApi.MessageText, is TdApi.MessagePhoto, is TdApi.MessageVideo, is TdApi.MessageDocument,
        is TdApi.MessageAudio, is TdApi.MessageAnimation, is TdApi.MessageVoiceNote, is TdApi.MessageVideoNote,
        is TdApi.MessageSticker, is TdApi.MessagePoll, is TdApi.MessageCall -> false
        else -> true
    }

    // My messages in a private chat: read by the other person or not; null elsewhere (messages.py read_mark)
    fun readOf(m: TdApi.Message): Boolean? {
        if (!m.isOutgoing) return null
        val chat = chats[m.chatId] ?: return null
        if (!isPrivate(chat) || m.chatId == myId) return null
        return m.id <= chat.lastReadOutboxMessageId
    }

    // Plain reactions as they are; custom emoji as "✦" until Direct mode learns their plain emoji (step 6)
    fun reactionsOf(m: TdApi.Message): JSONArray = reactionsList(m.interactionInfo?.reactions)

    fun reactionsList(reactions: TdApi.MessageReactions?): JSONArray {
        val list = JSONArray()
        reactions?.reactions?.forEach { r ->
            val (emoji, custom) = when (val t = r.type) {
                is TdApi.ReactionTypeEmoji -> t.emoji to null
                is TdApi.ReactionTypeCustomEmoji -> "✦" to t.customEmojiId
                is TdApi.ReactionTypePaid -> "⭐" to null
                else -> return@forEach
            }
            list.put(JSONObject().put("emoji", emoji).put("count", r.totalCount).put("mine", r.isChosen).putOrNull("custom_id", custom))
        }
        return list
    }

    fun customEmojiOf(m: TdApi.Message): JSONArray {
        val list = JSONArray()
        entitiesOf(m.content).forEach { e ->
            (e.type as? TdApi.TextEntityTypeCustomEmoji)?.let {
                list.put(JSONObject().put("offset", e.offset).put("length", e.length).put("id", it.customEmojiId))
            }
        }
        return list
    }

    // Who wrote it, for groups only: a person's first name or a channel's title (messages.py sender_name)
    fun senderName(m: TdApi.Message): String? {
        if (m.isOutgoing || m.isChannelPost) return null
        if (isPrivate(chats[m.chatId])) return null
        return when (val s = m.senderId) {
            is TdApi.MessageSenderUser -> users[s.userId]?.firstName
            is TdApi.MessageSenderChat -> chats[s.chatId]?.title
            else -> null
        }
    }

    fun senderIdOf(m: TdApi.Message): Long? {
        if (senderName(m) == null) return null
        return (m.senderId as? TdApi.MessageSenderUser)?.userId
    }

    fun messageJson(m: TdApi.Message): JSONObject = JSONObject()
        .put("id", appId(m.id))
        .put("text", if (isService(m.content)) "" else captionOf(m.content))
        .put("out", m.isOutgoing)
        .put("date", date(m.date))
        .putOrNull("reply_to_id", replyOf(m))
        .put("edited", m.editDate != 0)
        .putOrNull("call", callOf(m))
        .putOrNull("voice", voiceOf(m))
        .putOrNull("image", imageOf(m))
        .putOrNull("video", videoOf(m))
        .putOrNull("round", roundOf(m))
        .putOrNull("file", fileOf(m))
        .putOrNull("gif", gifOf(m))
        .putOrNull("poll", pollOf(m))
        .put("pinned", m.isPinned)
        .putOrNull("service", serviceOf(m))
        .putOrNull("read", readOf(m))
        .put("reactions", reactionsOf(m))
        .putOrNull("sticker", stickerOf(m))
        .put("custom_emoji", customEmojiOf(m))
        .putOrNull("sender", senderName(m))
        .putOrNull("sender_id", senderIdOf(m))

    // ---- the chat list's preview line (messages.py last_message_preview) ----

    private const val PREVIEW_LENGTH = 100

    fun lastMessagePreview(m: TdApi.Message): JSONObject {
        val content = m.content
        val caption = captionOf(content)
        val (text, kind) = when {
            content is TdApi.MessageCall -> "" to "call"
            content is TdApi.MessageVoiceNote -> caption to "voice"
            content is TdApi.MessagePhoto -> caption to "photo"
            content is TdApi.MessageVideo -> caption to "video"
            content is TdApi.MessageVideoNote -> "" to "round"
            content is TdApi.MessageSticker -> content.sticker.emoji to "sticker"
            fileOf(m) != null -> caption to "file"
            content is TdApi.MessageAnimation -> caption to "gif"
            content is TdApi.MessagePoll -> content.poll.question.text to "poll"
            content is TdApi.MessagePinMessage -> "" to "pin"
            isService(content) -> "" to "event"
            else -> caption to if (caption.isNotEmpty()) "text" else "media"
        }
        return JSONObject()
            .put("text", text.take(PREVIEW_LENGTH))
            .put("out", m.isOutgoing)
            .put("date", date(m.date))
            .putOrNull("sender", senderName(m))
            .put("kind", kind)
            .putOrNull("call", callOf(m))
            .putOrNull("voice", voiceOf(m))
            .putOrNull("file", fileOf(m))
    }
}
