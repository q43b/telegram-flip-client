package com.q43b.tgflip

// One topic of a forum group, from GET /topics.
data class Topic(val id: Long, val title: String, val unread: Int) {
    companion object {
        // Telegram gives every forum's General topic id 1
        const val GENERAL_ID = 1L
    }
}
