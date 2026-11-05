package com.q43b.tgflip

import org.json.JSONObject

// A person's profile from GET /profile. username and phone are null when not set or
// hidden by their privacy settings; phone comes with its "+".
data class Profile(
    val id: Long,
    val name: String,
    val username: String?,
    val phone: String?,
    val photo: String?,
    val bot: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject) = Profile(
            id = json.getLong("id"),
            name = json.optString("name"),
            username = if (json.isNull("username")) null else json.getString("username"),
            phone = if (json.isNull("phone")) null else json.getString("phone"),
            photo = if (json.isNull("photo")) null else json.getString("photo"),
            bot = json.optBoolean("bot"),
        )
    }
}
