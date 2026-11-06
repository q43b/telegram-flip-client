package com.q43b.tgflip

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView

// The chat screen's rows: the same texts as before, plus a sticker picture right of the text
// for stickers. stickerAt gives the picture for a position, or null (no sticker, data saver, or
// still loading), which hides it. rows is the adapter's own list.
class MessageRowAdapter(
    context: Context,
    rows: MutableList<CharSequence>,
    private val stickerAt: (Int) -> Drawable?,
) : ArrayAdapter<CharSequence>(context, R.layout.message_row, R.id.text, rows) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getView(position, convertView, parent)
        val sticker = stickerAt(position)
        row.findViewById<ImageView>(R.id.sticker).apply {
            setImageDrawable(sticker)
            visibility = if (sticker == null) View.GONE else View.VISIBLE
        }
        return row
    }
}
