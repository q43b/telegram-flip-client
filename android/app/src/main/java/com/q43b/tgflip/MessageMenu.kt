package com.q43b.tgflip

import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog

// One entry in the menu that OK on a message opens.
// To add a feature (forward, reaction, delete, edit), add one more MessageAction
// to the list in ChatActivity. isAvailable hides it where it does not apply,
// e.g. edit: isAvailable = { it.out } for own messages only.
class MessageAction(
    @StringRes val label: Int,
    val isAvailable: (Message) -> Boolean = { true },
    val perform: (Message) -> Unit,
)

object MessageMenu {
    fun show(context: Context, title: String, message: Message, actions: List<MessageAction>) {
        val available = actions.filter { it.isAvailable(message) }
        if (available.isEmpty()) return
        choose(context, title, available.map { context.getString(it.label) }) { which ->
            available[which].perform(message)
        }
    }

    // A plain list dialog: Up/Down to choose (wrapping around at the ends, like every
    // list in the app), OK to pick, Back to close without picking. Also used for
    // follow-up choices, like where to forward or how to delete. New pop-up lists should
    // use this, or call wrapAround() on their dialog's listView after show().
    fun choose(context: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .show()
            .listView.wrapAround()
    }

    // A one-field text dialog (a phrase, a folder name, a number, ...); blank text is ignored
    fun askText(
        context: Context,
        title: String,
        initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        onDone: (String) -> Unit,
    ) {
        val input = EditText(context)
        input.inputType = inputType
        input.setText(initial)
        input.setSelection(input.text.length)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onDone(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
