package com.q43b.tgflip

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.ListView

// A ListView that gets the OK key before the keyboard app (IME) does.
// On a screen with a text box, every key goes to the keyboard app first, and on the
// flip phone it swallows OK, so a normal ListView (and even the Activity) never sees it.
// onKeyPreIme runs before the keyboard app; it exists only on views, hence this class.
class OkListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListView(context, attrs) {

    // Called with the highlighted row's position when OK is released
    var onOk: ((position: Int) -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        val onOk = onOk
        if (onOk != null && event.isOkKey()) {
            if (event.action == KeyEvent.ACTION_UP && selectedItemPosition != INVALID_POSITION) {
                onOk(selectedItemPosition)
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
