package com.q43b.tgflip

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.ListView

// The app's list: Up on the first row goes to the last, Down on the last row goes to the
// first. Every list screen uses it in its layout (com.q43b.tgflip.WrapListView instead of
// ListView), and pop-up lists get the same through wrapAround() (MessageMenu does this).
// Where something sits below the list (a text box, buttons, the version line), set
// wrapsDown = false so Down still reaches it; onUpFromTop can send Up somewhere else.
// Only a fresh press wraps: holding the key stops at the end instead of looping.
open class WrapListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListView(context, attrs) {
    var wrapsDown = true
    var onUpFromTop: (() -> Unit)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        wrapKey(this, keyCode, event, wrapsDown, onUpFromTop) || super.onKeyDown(keyCode, event)
}

// The same for a list that isn't a WrapListView, such as the one inside a pop-up
fun ListView.wrapAround(wrapsDown: Boolean = true) = setOnKeyListener { _, keyCode, event ->
    event.action == KeyEvent.ACTION_DOWN && wrapKey(this, keyCode, event, wrapsDown, null)
}

private fun wrapKey(
    list: ListView,
    keyCode: Int,
    event: KeyEvent,
    wrapsDown: Boolean,
    onUpFromTop: (() -> Unit)?,
): Boolean {
    if (event.repeatCount > 0 || list.count == 0) return false
    val last = list.count - 1
    return when {
        keyCode == KeyEvent.KEYCODE_DPAD_UP && list.selectedItemPosition == 0 -> {
            if (onUpFromTop != null) onUpFromTop() else list.setSelection(last)
            true
        }
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN && wrapsDown && list.selectedItemPosition == last -> {
            list.setSelection(0)
            true
        }
        else -> false
    }
}
