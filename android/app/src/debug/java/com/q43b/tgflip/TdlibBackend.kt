package com.q43b.tgflip

import android.os.Handler
import android.os.Looper
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Direct mode's Backend (2.0 step 4): the same questions the screens ask our server, answered by TDLib
// on the phone, in the same models (built from the same JSON by TdlibJson). Like ApiClient, every call
// blocks until it has an answer, so screens call it from background threads; failures are IOExceptions.
// What isn't built yet says so (step 5: media; step 6: stickers).
class TdlibBackend : Backend {
    override val hasServer get() = true

    private fun call(function: TdApi.Function<*>) = Tdlib.callBlocking(function)

    private fun notYet(what: String): Nothing = throw IOException("Direct mode can't $what yet")

    private fun chat(chatId: Long): TdApi.Chat =
        TdlibJson.chats[chatId] ?: (call(TdApi.GetChat(chatId)) as TdApi.Chat).also { TdlibJson.chats[chatId] = it }

    private fun user(userId: Long): TdApi.User? =
        TdlibJson.users[userId] ?: runCatching { call(TdApi.GetUser(userId)) as TdApi.User }.getOrNull()

    private fun message(chatId: Long, messageId: Long) =
        call(TdApi.GetMessage(chatId, TdlibJson.tdId(messageId))) as TdApi.Message

    // General (and no topic) means no topic mark, as with the server's reply_target
    private fun topicOf(topicId: Long?): TdApi.MessageTopic? =
        topicId?.takeIf { it != Topic.GENERAL_ID }?.let { TdApi.MessageTopicForum(it.toInt()) }

    // ---- live events: our server's /ws, here TDLib's updates (TdlibLive) ----

    // Open = TDLib is connected to Telegram; when that connection drops, onDropped, and the caller opens
    // again (LiveConnection's reconnect), which waits until TDLib is connected again. So screens refetch
    // after every reconnect, as with the server.
    override fun openEvents(listener: LiveListener): LiveStream {
        val main = Handler(Looper.getMainLooper())
        val forward: (JSONObject) -> Unit = { listener.onEvent(it) }
        var open = false
        var closed = false
        lateinit var watch: () -> Unit
        fun close() {
            closed = true
            TdlibLive.listeners.remove(forward)
            Tdlib.unlisten(watch)
        }
        watch = {
            val ready = Tdlib.connection is TdApi.ConnectionStateReady
            if (!closed && ready && !open) {
                open = true
                TdlibLive.listeners.add(forward)
                listener.onOpen()
            } else if (!closed && open && !ready) {
                close()
                listener.onDropped()
            }
        }
        main.post {
            Tdlib.listen(watch)
            watch()
        }
        return object : LiveStream {
            override fun close() {
                main.post { close() }
            }
        }
    }

    // ---- chats and topics (routes/chats.py) ----

    // TDLib keeps the chat list in order itself; it loads more from Telegram when asked to
    override fun getChats(limit: Int): List<Chat> {
        var ids = (call(TdApi.GetChats(TdApi.ChatListMain(), limit)) as TdApi.Chats).chatIds
        var tries = 0
        while (ids.size < limit && tries++ < 5) {
            try {
                call(TdApi.LoadChats(TdApi.ChatListMain(), limit))
            } catch (e: TdlibException) {
                if (e.code == 404) break  // all chats are loaded
                throw e
            }
            ids = (call(TdApi.GetChats(TdApi.ChatListMain(), limit)) as TdApi.Chats).chatIds
        }
        return ids.map { Chat.fromJson(TdlibJson.chatJson(chat(it))) }
    }

    // An app folder's chats, newest activity first; unknown ones are left out, like the server's
    override fun getChatsByIds(ids: Collection<Long>): List<Chat> =
        ids.mapNotNull { id -> runCatching { chat(id) }.getOrNull() }
            .sortedByDescending { it.lastMessage?.date ?: 0 }
            .map { Chat.fromJson(TdlibJson.chatJson(it)) }

    // A forum's topics in Telegram's order (pinned first, then by activity); General keeps id 1
    override fun getTopics(chatId: Long, limit: Int): List<Topic> {
        chat(chatId)
        val found = call(TdApi.GetForumTopics(chatId, "", 0, 0, 0, limit)) as TdApi.ForumTopics
        return found.topics.map { Topic.fromJson(TdlibJson.topicJson(it)) }
    }

    // The newest messages of a chat or a topic, oldest first. TDLib may answer a page in pieces.
    override fun getMessages(chatId: Long, topicId: Long?, limit: Int): List<Message> {
        chat(chatId)
        val found = mutableListOf<TdApi.Message>()
        var from = 0L
        var tries = 0
        while (found.size < limit && tries++ < 10) {
            val page = if (topicId == null) TdApi.GetChatHistory(chatId, from, 0, limit - found.size, false)
            else TdApi.GetForumTopicHistory(chatId, topicId.toInt(), from, 0, limit - found.size)
            val batch = (call(page) as TdApi.Messages).messages
            if (batch.isEmpty()) break
            found += batch
            from = batch.last().id
        }
        return found.take(limit).reversed().map { Message.fromJson(TdlibJson.messageJson(it)) }
    }

    // The pinned messages, newest first (routes/chats.py /pinned)
    override fun getPinned(chatId: Long, topicId: Long?): List<Message> {
        chat(chatId)
        val found = call(TdApi.SearchChatMessages(chatId, topicOf(topicId), "", null, 0, 0, 50,
            TdApi.SearchMessagesFilterPinned())) as TdApi.FoundChatMessages
        return found.messages.map { Message.fromJson(TdlibJson.messageJson(it)) }
    }

    // ---- writing ----

    // Sent once Telegram has it: TDLib answers with a temporary message first, then says which real
    // message it became (TdlibLive); the real id is what the app gets, as from the server
    override fun sendMessage(chatId: Long, topicId: Long?, text: String, replyToId: Long?): Long {
        chat(chatId)
        val reply = replyToId?.let { TdApi.InputMessageReplyToMessage(TdlibJson.tdId(it), null, 0, "") }
        return sendAndWait(TdApi.SendMessage(chatId, topicOf(topicId), reply, null, null, plainText(text)))
    }

    private fun plainText(text: String) = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false)

    // Into a forum it lands in General, as from the server
    override fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long): Long {
        chat(toChatId)
        return sendAndWait(TdApi.ForwardMessages(toChatId, null, fromChatId, longArrayOf(TdlibJson.tdId(messageId)), null, false, false))
    }

    private fun sendAndWait(function: TdApi.Function<*>): Long {
        val started = CountDownLatch(1)
        var sent: TdlibLive.Sent? = null
        var refused: String? = null
        // The waiting slot is made on TDLib's thread, before it can report the real message
        Tdlib.sendRaw(function) { r ->
            when (r) {
                is TdApi.Message -> sent = TdlibLive.Sent().also { TdlibLive.sending[r.id] = it }
                // Forwarding answers with a list (of one, here)
                is TdApi.Messages -> r.messages.firstOrNull()?.let { m -> sent = TdlibLive.Sent().also { TdlibLive.sending[m.id] = it } }
                is TdApi.Error -> refused = r.message
            }
            started.countDown()
        }
        if (!started.await(30, TimeUnit.SECONDS)) throw IOException("Telegram didn't answer in time")
        refused?.let { throw IOException(it) }
        val waiting = sent ?: throw IOException("Not sent")
        if (!waiting.done.await(120, TimeUnit.SECONDS)) throw IOException("Telegram didn't confirm the message in time")
        waiting.error?.let { throw IOException(it) }
        return TdlibJson.appId(waiting.message!!.id)
    }

    override fun editMessage(chatId: Long, messageId: Long, text: String) {
        call(TdApi.EditMessageText(chatId, TdlibJson.tdId(messageId), null, plainText(text)))
    }

    // TDLib knows what Telegram allows for this message (the server works it out from the rules)
    override fun getDeleteOptions(chatId: Long, messageId: Long): DeleteOptions {
        val p = call(TdApi.GetMessageProperties(chatId, TdlibJson.tdId(messageId))) as TdApi.MessageProperties
        return DeleteOptions(p.canBeDeletedOnlyForSelf, p.canBeDeletedForAllUsers)
    }

    override fun deleteMessage(chatId: Long, messageId: Long, forEveryone: Boolean) {
        call(TdApi.DeleteMessages(chatId, longArrayOf(TdlibJson.tdId(messageId)), forEveryone))
    }

    // Everything up to the newest message read (a read receipt, so only when the user asks)
    override fun markRead(chatId: Long, topicId: Long?) {
        if (topicId == null) {
            val last = chat(chatId).lastMessage?.id ?: return
            call(TdApi.ViewMessages(chatId, longArrayOf(last), TdApi.MessageSourceChatHistory(), true))
            return
        }
        val topic = call(TdApi.GetForumTopic(chatId, topicId.toInt())) as TdApi.ForumTopic
        val last = topic.lastMessage?.id ?: return
        call(TdApi.ViewMessages(chatId, longArrayOf(last), TdApi.MessageSourceForumTopicHistory(), true))
    }

    // Quietly, like the server; in a private chat for both of us unless asked otherwise
    override fun pin(chatId: Long, messageId: Long, unpin: Boolean, both: Boolean) {
        if (unpin) call(TdApi.UnpinChatMessage(chatId, TdlibJson.tdId(messageId)))
        else call(TdApi.PinChatMessage(chatId, TdlibJson.tdId(messageId), true, !both))
    }

    // The answers' positions; empty takes my vote back. The poll as it is now.
    override fun vote(chatId: Long, messageId: Long, options: List<Int>): PollInfo {
        call(TdApi.SetPollAnswer(chatId, TdlibJson.tdId(messageId), options.toIntArray()))
        val poll = TdlibJson.pollOf(message(chatId, messageId)) ?: throw IOException("Not a poll")
        return PollInfo.fromJson(poll)
    }

    // ---- reactions (routes/reactions.py) ----

    // The plain emoji reactions I may use in this chat, my most used first: TDLib's list for the chat's
    // newest message (top, recent, then popular), Premium-only ones left out
    override fun getReactions(chatId: Long): List<String> {
        val last = chat(chatId).lastMessage?.id ?: return emptyList()
        val r = call(TdApi.GetMessageAvailableReactions(chatId, last, 8)) as TdApi.AvailableReactions
        if (r.areTags) return emptyList()  // Saved Messages: tags, a Premium thing (the server says the same)
        return (r.topReactions + r.recentReactions + r.popularReactions)
            .filter { !it.needsPremium }
            .mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
            .distinct()
    }

    // One reaction of mine at a time, replacing any I had; null takes mine back. The reactions as they are now.
    override fun react(chatId: Long, messageId: Long, emoji: String?): List<Reaction>? {
        val types: Array<TdApi.ReactionType> = if (emoji.isNullOrEmpty()) emptyArray() else arrayOf(TdApi.ReactionTypeEmoji(emoji))
        call(TdApi.SetMessageReactions(chatId, TdlibJson.tdId(messageId), types, false))
        return Reaction.listFromJson(TdlibJson.reactionsOf(message(chatId, messageId)))
    }

    // Who read my message in a small group: (name, ISO date) each; Telegram's refusal as an IOException
    override fun getReadBy(chatId: Long, messageId: Long): List<Pair<String, String?>> {
        val v = call(TdApi.GetMessageViewers(chatId, TdlibJson.tdId(messageId))) as TdApi.MessageViewers
        return v.viewers.map { viewer ->
            TdlibJson.userName(user(viewer.userId)).ifEmpty { viewer.userId.toString() } to
                viewer.viewDate.takeIf { it > 0 }?.let(TdlibJson::date)
        }
    }

    // ---- people and groups ----

    override fun getProfile(userId: Long): Profile {
        val u = user(userId) ?: throw IOException("Not found")
        return Profile.fromJson(TdlibJson.profileJson(u))
    }

    // A group's or channel's info and members (routes/chats.py /group); channels hide their members
    override fun getGroup(chatId: Long, members: Int): Group {
        val c = chat(chatId)
        val json = JSONObject().put("id", chatId).put("name", c.title).put("photo", TdlibJson.photoIdOf(c) ?: JSONObject.NULL)
        when (val type = c.type) {
            is TdApi.ChatTypeSupergroup -> {
                val info = call(TdApi.GetSupergroupFullInfo(type.supergroupId)) as TdApi.SupergroupFullInfo
                json.put("channel", type.isChannel).put("total", info.memberCount)
                if (type.isChannel || !info.canGetMembers) {
                    json.put("members", JSONObject.NULL).put("partial", false)
                } else {
                    val found = call(TdApi.GetSupergroupMembers(type.supergroupId, TdApi.SupergroupMembersFilterRecent(), 0,
                        members.coerceIn(1, 200))) as TdApi.ChatMembers
                    val list = found.members.mapNotNull(TdlibJson::memberJson)
                    json.put("members", JSONArray(list))
                        .put("partial", list.size < members && list.size < info.memberCount)
                }
            }
            is TdApi.ChatTypeBasicGroup -> {
                val info = call(TdApi.GetBasicGroupFullInfo(type.basicGroupId)) as TdApi.BasicGroupFullInfo
                val list = info.members.mapNotNull(TdlibJson::memberJson)
                json.put("channel", false).put("total", list.size)
                    .put("members", JSONArray(list.take(members))).put("partial", false)
            }
            else -> throw IOException("Not a group")
        }
        return Group.fromJson(json)
    }

    // A chat's (or person's) small profile photo, 160 px as Telegram keeps it; the loader scales it.
    // A person may have no chat with me yet (a group member): then their own profile photo.
    override fun getPhoto(chatId: Long, photoId: String, size: Int): ByteArray? {
        val file = runCatching { chat(chatId).photo?.small }.getOrNull()
            ?: user(chatId)?.profilePhoto?.small ?: return null
        val done = call(TdApi.DownloadFile(file.id, 1, 0, 0, true)) as TdApi.File
        return done.local?.path?.takeIf { done.local.isDownloadingCompleted }?.let { File(it).readBytes() }
    }

    // ---- not in Direct mode yet ----
    override fun download(kind: Download.Kind, chatId: Long, messageId: Long): Download = notYet("download media")
    override fun sendVoice(chatId: Long, topicId: Long?, audio: ByteArray, seconds: Int, replyToId: Long?): Long = notYet("send voice messages")
    override fun sendPhoto(chatId: Long, topicId: Long?, photo: ByteArray, mimeType: String, caption: String, replyToId: Long?): Long = notYet("send photos")
    override fun getStickerPacks(): List<StickerPack> = notYet("show sticker packs")
    override fun getStickerPack(pack: String): List<StickerInfo> = notYet("show sticker packs")
    override fun getStickerPackOf(stickerId: Long, chatId: Long, messageId: Long): Pair<PackInfo, List<StickerInfo>> = notYet("show sticker packs")
    override fun faveSticker(stickerId: Long, unfave: Boolean) = notYet("change favourite stickers")
    override fun localFavorite(stickerId: Long, remove: Boolean) = notYet("keep local favourites")
    override fun installStickerPack(packId: String) = notYet("add sticker packs")
    override fun sendSticker(chatId: Long, topicId: Long?, stickerId: Long, replyToId: Long?): Long = notYet("send stickers")
    override fun getImage(chatId: Long, messageId: Long): ByteArray = notYet("show photos")
    override fun getFullImage(chatId: Long, messageId: Long): ByteArray = notYet("download photos")
    override fun sendFile(chatId: Long, topicId: Long?, file: FileSender.Prepared, openFile: () -> InputStream, caption: String, replyToId: Long?, onProgress: (Long, Long) -> Unit, onSent: () -> Unit): Long = notYet("send files")
    override fun sendVideo(chatId: Long, topicId: Long?, video: VideoSender.Prepared, openVideo: () -> InputStream, caption: String, replyToId: Long?, onProgress: (Long, Long) -> Unit, onSent: () -> Unit): Long = notYet("send videos")
    override fun getSticker(id: Long, size: Int, chatId: Long?, messageId: Long?, animated: Boolean): ByteArray? = null
    override fun getVoice(chatId: Long, messageId: Long): ByteArray = notYet("play voice messages")
    override fun getThumb(chatId: Long, messageId: Long, size: Int): ByteArray? = null
}
