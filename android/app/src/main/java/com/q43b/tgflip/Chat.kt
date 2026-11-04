package com.q43b.tgflip

// One row from GET /chats. Telegram ids can be larger than Int, so id is a Long.
data class Chat(val id: Long, val name: String, val unread: Int)
