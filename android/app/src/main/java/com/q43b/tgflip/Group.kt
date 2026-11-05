package com.q43b.tgflip

import org.json.JSONObject

// A group's (or channel's) info from GET /group. total = member count, if known.
// members = null where Telegram refuses the list (channels); partial = Telegram shows
// only some members (a group that hides them: usually just the admins).
data class Group(
    val id: Long,
    val name: String,
    val photo: String?,
    val channel: Boolean,
    val total: Int?,
    val members: List<Member>?,
    val partial: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject) = Group(
            id = json.getLong("id"),
            name = json.optString("name"),
            photo = if (json.isNull("photo")) null else json.getString("photo"),
            channel = json.optBoolean("channel"),
            total = if (json.isNull("total")) null else json.getInt("total"),
            members = json.optJSONArray("members")?.let { a ->
                List(a.length()) { i -> Member.fromJson(a.getJSONObject(i)) }
            },
            partial = json.optBoolean("partial"),
        )
    }
}

// role: "owner", "admin" or null
data class Member(val id: Long, val name: String, val photo: String?, val role: String?, val bot: Boolean) {
    companion object {
        fun fromJson(json: JSONObject) = Member(
            id = json.getLong("id"),
            name = json.optString("name"),
            photo = if (json.isNull("photo")) null else json.getString("photo"),
            role = if (json.isNull("role")) null else json.getString("role"),
            bot = json.optBoolean("bot"),
        )
    }
}
