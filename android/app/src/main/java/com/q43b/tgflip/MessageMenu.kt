package com.q43b.tgflip

import android.content.Context
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

    // A plain list dialog: Up/Down to choose, OK to pick, Back to close without picking.
    // Also used for follow-up choices, like where to forward or how to delete.
    fun choose(context: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .show()
    }
}
