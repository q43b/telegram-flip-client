package com.q43b.tgflip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// The accounts the app can switch between (Settings > Network > Account). Each is its own
// server login: a token and an address per connection mode, its own saved chats and messages,
// its own downloaded media and its own folders. Everything else (themes, cache limits, quick
// phrases, sticker descriptions) is shared. The first account, "main", keeps the keys and
// folders the app has always used, so updating changes nothing for it; the others add
// "_<id>" to theirs (see suffix).
object Profiles {
    // direct: talks to Telegram itself (Direct mode, DirectMode.kt) instead of through a server
    data class Profile(val id: String, val name: String, val direct: Boolean = false)

    const val MAIN = "main"
    private const val KEY_LIST = "profiles"
    private const val KEY_CURRENT = "profile_current"

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun list(context: Context): List<Profile> {
        val saved = prefs(context).getString(KEY_LIST, null)
        val others = try {
            val json = JSONArray(saved ?: "[]")
            List(json.length()) { i ->
                json.getJSONObject(i).let { Profile(it.getString("id"), it.getString("name"), it.optString("kind") == KIND_DIRECT) }
            }
        } catch (e: Exception) {
            emptyList()
        }
        // main always comes first; its name can be changed like the others'
        return others.filter { it.id == MAIN }.ifEmpty { listOf(Profile(MAIN, context.getString(R.string.profile_main))) } +
            others.filter { it.id != MAIN }
    }

    fun currentId(context: Context): String {
        val id = prefs(context).getString(KEY_CURRENT, MAIN) ?: MAIN
        return if (list(context).any { it.id == id }) id else MAIN
    }

    fun current(context: Context): Profile = list(context).first { it.id == currentId(context) }

    // Added to every key and folder that belongs to one account; empty for main
    fun suffix(context: Context, id: String = currentId(context)) = if (id == MAIN) "" else "_$id"

    fun add(context: Context, name: String, direct: Boolean = false): Profile {
        val profile = Profile(System.currentTimeMillis().toString(36), name, direct)
        save(context, list(context) + profile)
        return profile
    }

    fun rename(context: Context, id: String, name: String) =
        save(context, list(context).map { if (it.id == id) it.copy(name = name) else it })

    fun switchTo(context: Context, id: String) {
        prefs(context).edit().putString(KEY_CURRENT, id).apply()
    }

    // Forgets an account on this phone: its token, addresses, folders and saved copies. Main can't be
    // removed (there is always one account). The server is not told: its token keeps working.
    fun remove(context: Context, id: String) {
        if (id == MAIN) return
        if (list(context).any { it.id == id && it.direct }) DirectModes.available?.forget(context, id)
        val suffix = suffix(context, id)
        val edit = prefs(context).edit()
        edit.putString(KEY_LIST, JSONArray(list(context).filter { it.id != id }.map(::toJson)).toString())
        ServerSettings.keysFor(suffix).forEach { edit.remove(it) }
        edit.remove(Folders.KEY + suffix)
        if (currentId(context) == id) edit.putString(KEY_CURRENT, MAIN)
        edit.apply()
        File(context.noBackupFilesDir, LocalCache.DIR + suffix).deleteRecursively()
        File(context.cacheDir, MediaCache.ROOT + suffix).deleteRecursively()
    }

    private fun save(context: Context, profiles: List<Profile>) {
        // main is stored too, so that renaming it sticks
        val json = JSONArray(profiles.map(::toJson))
        prefs(context).edit().putString(KEY_LIST, json.toString()).apply()
    }

    private fun toJson(p: Profile) = JSONObject().put("id", p.id).put("name", p.name).apply {
        if (p.direct) put("kind", KIND_DIRECT)
    }

    private const val KIND_DIRECT = "direct"
}
