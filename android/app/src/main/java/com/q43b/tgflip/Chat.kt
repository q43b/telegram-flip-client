package com.q43b.tgflip

// One row from GET /chats. Telegram ids can be larger than Int, so id is a Long.
// forum = a group split into topics; it opens a topic list instead of messages.
data class Chat(val id: Long, val name: String, val unread: Int, val forum: Boolean)
