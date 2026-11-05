package com.q43b.tgflip

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONException

// Ready-made phrases to drop into the message box (F2 > Quick phrases in a chat), since
// typing on a keypad is slow. Kept as a JSON list in the app's private settings file.
class QuickPhrases(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Until the list is first edited, a few examples
    fun load(): List<String> {
        val saved = prefs.getString(KEY, null) ?: return DEFAULTS
        return try {
            val json = JSONArray(saved)
            List(json.length()) { json.getString(it) }
        } catch (e: JSONException) {
            DEFAULTS
        }
    }

    fun save(phrases: List<String>) {
        prefs.edit().putString(KEY, JSONArray(phrases).toString()).apply()
    }

    private companion object {
        const val KEY = "quick_phrases"
        val DEFAULTS = listOf("OK", "On my way", "I'll call you later")
    }
}

// The Settings editor: a list of the phrases plus "+ Add phrase". A phrase opens
// Edit / Delete. After each change the list opens again, so several edits are quick.
object QuickPhrasesEditor {
    fun show(activity: Activity, onChanged: () -> Unit) {
        val store = QuickPhrases(activity)
        val phrases = store.load()
        val items = phrases.map { MessageFormat.snippet(it, 40) } + activity.getString(R.string.add_phrase)
        MessageMenu.choose(activity, activity.getString(R.string.quick_phrases), items) { which ->
            if (which == phrases.size) {
                askText(activity, R.string.add_phrase, "") { text ->
                    store.save(phrases + text)
                    onChanged()
                    show(activity, onChanged)
                }
            } else {
                editOrDelete(activity, store, phrases, which, onChanged)
            }
        }
    }

    private fun editOrDelete(
        activity: Activity,
        store: QuickPhrases,
        phrases: List<String>,
        index: Int,
        onChanged: () -> Unit,
    ) {
        val choices = listOf(activity.getString(R.string.action_edit), activity.getString(R.string.action_delete))
        MessageMenu.choose(activity, MessageFormat.snippet(phrases[index], 30), choices) { which ->
            if (which == 0) {
                askText(activity, R.string.action_edit, phrases[index]) { text ->
                    store.save(phrases.toMutableList().also { it[index] = text })
                    onChanged()
                    show(activity, onChanged)
                }
            } else {
                store.save(phrases.filterIndexed { i, _ -> i != index })
                onChanged()
                show(activity, onChanged)
            }
        }
    }

    private fun askText(activity: Activity, title: Int, initial: String, onDone: (String) -> Unit) =
        MessageMenu.askText(activity, activity.getString(title), initial, onDone)
}
