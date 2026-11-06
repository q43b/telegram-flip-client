package com.q43b.tgflip

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

// My own words for stickers ("cat laughing"), set from a pack's F2 menu, so stickers still say
// something in data saver mode, where no pictures show. Kept as a JSON object (sticker id ->
// text) in the settings file, so "Delete all saved data" keeps them like quick phrases.
object StickerDescriptions {
    private const val KEY = "sticker_descriptions"

    fun get(context: Context, stickerId: Long): String? = load(context).optString(stickerId.toString()).ifEmpty { null }

    // text null or blank = no description
    fun save(context: Context, stickerId: Long, text: String?) {
        val all = load(context)
        if (text.isNullOrBlank()) all.remove(stickerId.toString()) else all.put(stickerId.toString(), text.trim())
        prefs(context).edit().putString(KEY, all.toString()).apply()
    }

    private fun load(context: Context): JSONObject = try {
        JSONObject(prefs(context).getString(KEY, null) ?: "{}")
    } catch (e: JSONException) {
        JSONObject()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
