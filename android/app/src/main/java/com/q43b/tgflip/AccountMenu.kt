package com.q43b.tgflip

import android.content.Intent
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

// Settings > Network > Account: switch between the accounts saved on this phone (Profiles), add
// one, rename or remove the current one. Switching starts the app over at the chat list, since
// screens hold the old account's chats, photos and live connection in memory.
object AccountMenu {
    fun show(activity: AppCompatActivity, onChanged: () -> Unit) {
        val profiles = Profiles.list(activity)
        val current = Profiles.currentId(activity)
        val items = profiles.map { (if (it.id == current) "● " else "○ ") + it.name } +
            activity.getString(R.string.account_add) +
            activity.getString(R.string.account_rename) +
            (if (current != Profiles.MAIN) listOf(activity.getString(R.string.account_remove)) else emptyList())
        MessageMenu.choose(activity, activity.getString(R.string.account_title), items) { which ->
            when {
                which < profiles.size -> if (profiles[which].id != current) switchTo(activity, profiles[which].id)
                which == profiles.size -> askName(activity, R.string.account_add_title, "") { name ->
                    // The new account has no token yet: the chat list asks for it on its own
                    switchTo(activity, Profiles.add(activity, name).id)
                }
                which == profiles.size + 1 -> askName(activity, R.string.account_rename_title, Profiles.current(activity).name) { name ->
                    Profiles.rename(activity, current, name)
                    onChanged()
                }
                else -> confirmRemove(activity, current)
            }
        }
    }

    private fun switchTo(activity: AppCompatActivity, id: String) {
        Profiles.switchTo(activity, id)
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    private fun confirmRemove(activity: AppCompatActivity, id: String) {
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.account_remove_ask, Profiles.current(activity).name))
            .setPositiveButton(R.string.account_remove_yes) { _, _ ->
                Profiles.remove(activity, id)
                switchTo(activity, Profiles.MAIN)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { Themer.apply(it) }
    }

    private fun askName(activity: AppCompatActivity, title: Int, initial: String, onName: (String) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initial)
            setSelection(initial.length)
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { Themer.apply(it) }
    }
}
