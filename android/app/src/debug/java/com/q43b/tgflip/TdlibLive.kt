package com.q43b.tgflip

import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch

// TDLib's updates, on TDLib's own thread: they keep TdlibJson's chats and users current, and become the
// same live events our server sends over /ws (server/tgflip/live.py; the handlers follow it), handed to
// whoever listens (TdlibBackend.openEvents, i.e. LiveConnection). Plus the bookkeeping for sending: TDLib
// answers a send with a temporary message and says later which real one it became.
object TdlibLive {
    val listeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()

    // Sends waiting for their real message: temporary id -> (latch, the real message or the error)
    class Sent {
        val done = CountDownLatch(1)
        @Volatile var message: TdApi.Message? = null
        @Volatile var error: String? = null
    }
    val sending = ConcurrentHashMap<Long, Sent>()

    private val unread = ConcurrentHashMap<Long, Int>()  // to tell a read (count goes down) from a new message
    private val readOutbox = ConcurrentHashMap<Long, Long>()  // only a newer read mark is news
    // The reactions last seen per message: TDLib's "interaction info" also changes with every view and
    // reply count (channels: all the time); only a change of the reactions is an event, as from the server
    private val reactionsSeen = ConcurrentHashMap<String, String>()

    fun clear() {
        TdlibJson.chats.clear()
        TdlibJson.users.clear()
        TdlibJson.supergroups.clear()
        TdlibJson.myId = 0
        unread.clear()
        readOutbox.clear()
        reactionsSeen.clear()
    }

    // For checking on the phone what TDLib makes of its updates: a count per event type, logged every
    // 50 events (adb logcat -s TdlibLive)
    private val counts = ConcurrentHashMap<String, Int>()
    private var total = 0

    private fun broadcast(event: JSONObject) {
        counts.merge(event.optString("type"), 1, Int::plus)
        if (++total % 50 == 0) android.util.Log.i("TdlibLive", "events so far: $counts")
        listeners.forEach { runCatching { it(event) } }
    }

    private fun messageEvent(m: TdApi.Message) {
        val json = TdlibJson.messageJson(m).put("type", "message").put("chat_id", m.chatId)
            .put("topic_id", TdlibJson.topicOf(m) ?: JSONObject.NULL)
        broadcast(json)
    }

    fun onUpdate(update: TdApi.Object) {
        val chats = TdlibJson.chats
        when (update) {
            // ---- the cache ----
            is TdApi.UpdateOption -> if (update.name == "my_id") {
                (update.value as? TdApi.OptionValueInteger)?.let { TdlibJson.myId = it.value }
            }
            is TdApi.UpdateUser -> TdlibJson.users[update.user.id] = update.user
            is TdApi.UpdateSupergroup -> TdlibJson.supergroups[update.supergroup.id] = update.supergroup
            is TdApi.UpdateNewChat -> {
                chats[update.chat.id] = update.chat
                unread[update.chat.id] = update.chat.unreadCount
            }
            is TdApi.UpdateChatTitle -> chats[update.chatId]?.title = update.title
            is TdApi.UpdateChatPhoto -> chats[update.chatId]?.photo = update.photo
            is TdApi.UpdateChatLastMessage -> chats[update.chatId]?.let {
                it.lastMessage = update.lastMessage
                it.positions = update.positions
            }
            is TdApi.UpdateChatPosition -> chats[update.chatId]?.let { chat ->
                chat.positions = chat.positions.filter { it.list.constructor != update.position.list.constructor }
                    .let { if (update.position.order != 0L) it + update.position else it }.toTypedArray()
            }

            // ---- live events (live.py) ----
            is TdApi.UpdateChatReadInbox -> {
                chats[update.chatId]?.let {
                    it.unreadCount = update.unreadCount
                    it.lastReadInboxMessageId = update.lastReadInboxMessageId
                }
                // Only a real read (on this phone or another device), not a new message raising the count
                val before = unread.put(update.chatId, update.unreadCount) ?: 0
                if (update.unreadCount < before) broadcast(JSONObject().put("type", "read").put("chat_id", update.chatId))
            }
            is TdApi.UpdateChatReadOutbox -> {
                chats[update.chatId]?.lastReadOutboxMessageId = update.lastReadOutboxMessageId
                val before = readOutbox.put(update.chatId, update.lastReadOutboxMessageId)
                if (before == null || update.lastReadOutboxMessageId > before) broadcast(JSONObject().put("type", "read_outbox").put("chat_id", update.chatId)
                    .put("max_id", TdlibJson.appId(update.lastReadOutboxMessageId)))
            }
            // Mine while sending: only once Telegram has it (UpdateMessageSendSucceeded)
            is TdApi.UpdateNewMessage -> if (update.message.sendingState == null) messageEvent(update.message)
            is TdApi.UpdateMessageSendSucceeded -> {
                sending.remove(update.oldMessageId)?.let { it.message = update.message; it.done.countDown() }
                messageEvent(update.message)
            }
            is TdApi.UpdateMessageSendFailed -> sending.remove(update.oldMessageId)?.let {
                it.error = update.error.message
                it.done.countDown()
            }
            // Edited text or caption: the whole message read again, like the server's MessageEdited
            is TdApi.UpdateMessageContent -> edited(update.chatId, update.messageId)
            is TdApi.UpdateMessageEdited -> edited(update.chatId, update.messageId)
            is TdApi.UpdateMessageInteractionInfo -> {
                val reactions = TdlibJson.reactionsList(update.interactionInfo?.reactions)
                val text = reactions.toString()
                val before = reactionsSeen.put("${update.chatId}/${update.messageId}", text)
                if (text != (before ?: "[]")) broadcast(JSONObject().put("type", "reactions")
                    .put("chat_id", update.chatId).put("id", TdlibJson.appId(update.messageId))
                    .put("reactions", reactions))
            }
            // Pinned or unpinned (live.py on_pinned): the app asks for the pinned list again
            is TdApi.UpdateMessageIsPinned -> broadcast(JSONObject().put("type", "pinned").put("chat_id", update.chatId)
                .put("ids", JSONArray(listOf(TdlibJson.appId(update.messageId)))).put("pinned", update.isPinned))
            // TDLib also "deletes" messages it only forgets from its cache; those aren't news
            is TdApi.UpdateDeleteMessages -> if (update.isPermanent && !update.fromCache) {
                broadcast(JSONObject().put("type", "deleted").put("chat_id", update.chatId)
                    .put("ids", JSONArray(update.messageIds.map(TdlibJson::appId))))
            }
        }
    }

    private fun edited(chatId: Long, messageId: Long) {
        Tdlib.sendRaw(TdApi.GetMessage(chatId, messageId)) { r ->
            val m = r as? TdApi.Message ?: return@sendRaw
            // A poll's votes change its content: that is a poll event, as from the server (on_poll)
            TdlibJson.pollOf(m)?.let { poll ->
                broadcast(JSONObject().put("type", "poll").put("chat_id", chatId).put("id", TdlibJson.appId(m.id)).put("poll", poll))
                return@sendRaw
            }
            val json = TdlibJson.messageJson(m)
            broadcast(JSONObject().put("type", "edited").put("id", json.get("id")).put("chat_id", chatId)
                .put("text", json.get("text")).put("edited", json.get("edited"))
                .put("reactions", json.get("reactions")).put("custom_emoji", json.get("custom_emoji")))
        }
    }
}
