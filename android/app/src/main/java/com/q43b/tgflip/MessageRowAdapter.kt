package com.q43b.tgflip

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView

// The chat screen's rows. A message with media has two: its message row and, under it, a media row
// that is drawn as part of the same message: indented behind a bar in the theme's media color, with
// no padding or divider between the two (the divider comes after the pair). When one row of a pair has
// the focus, the other gets a faint tint of the focus color, so both read as one message while the
// focused one (drawn by the list's selector) stays the clear target of OK. rows is the adapter's own list.
class MessageRowAdapter(
    context: Context,
    rows: MutableList<CharSequence>,
    private val infoAt: (Int) -> Info,
) : ArrayAdapter<CharSequence>(context, R.layout.message_row, R.id.text, rows) {

    // media: this is a media row. partnerBelow: the next row belongs to the same message.
    // partnerAbove: the previous row does. partnerFocused: the other row of this message has the
    // focus (and the list has it). sticker: a picture for a sticker's media row.
    class Info(
        val media: Boolean = false,
        val partnerBelow: Boolean = false,
        val partnerAbove: Boolean = false,
        val partnerFocused: Boolean = false,
        val sticker: Drawable? = null,
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getView(position, convertView, parent) as MessageRowLayout
        val info = infoAt(position)
        val theme = Themes.current(context)
        val palette = theme.palette(context)
        row.findViewById<ImageView>(R.id.sticker).apply {
            setImageDrawable(info.sticker)
            visibility = if (info.sticker == null) View.GONE else View.VISIBLE
        }
        row.findViewById<View>(R.id.bar).apply {
            visibility = if (info.media) View.VISIBLE else View.GONE
            setBackgroundColor(palette.media)
        }
        row.findViewById<TextView>(R.id.text).setTextColor(if (info.media) palette.media else palette.text)
        // The two rows of a message sit close together: no padding where they meet
        val pad = Themer.rowPaddingPx(context)
        row.setPaddingRelative(row.paddingStart, if (info.partnerAbove) 0 else pad, row.paddingEnd, if (info.partnerBelow) 0 else pad)
        // The divider after the message (or the pair), not between its rows
        val divider = if (info.partnerBelow) null else Themer.newDivider(context)
        row.divider = divider?.first
        row.dividerHeight = divider?.second ?: 0
        // Faint tint behind the partner of the focused row
        if (info.partnerFocused) row.setBackgroundColor(Color.argb(TINT_ALPHA, Color.red(palette.focus), Color.green(palette.focus), Color.blue(palette.focus)))
        else row.background = null
        return row
    }

    private companion object {
        const val TINT_ALPHA = 64  // about 25% of the focus color: visible, but clearly weaker than the focus itself
    }
}
