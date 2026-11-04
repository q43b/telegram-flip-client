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
    // A plain list dialog: Up/Down to choose, OK to run, Back to close
    fun show(context: Context, title: String, message: Message, actions: List<MessageAction>) {
        val available = actions.filter { it.isAvailable(message) }
        if (available.isEmpty()) return
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(available.map { context.getString(it.label) }.toTypedArray()) { _, which ->
                available[which].perform(message)
            }
            .show()
    }
}
