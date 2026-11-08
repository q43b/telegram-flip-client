package com.q43b.tgflip

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.LinearLayout

// A chat row that draws its own divider at the bottom (the chat list has no divider of its own, see
// Themer), so the two rows of one message, the message and its media, can go without one between them.
class MessageRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var divider: Drawable? = null
    var dividerHeight = 0

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val line = divider ?: return
        if (dividerHeight <= 0) return
        line.setBounds(0, height - dividerHeight, width, height)
        line.draw(canvas)
    }
}
