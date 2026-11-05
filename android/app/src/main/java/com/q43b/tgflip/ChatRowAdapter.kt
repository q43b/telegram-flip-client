package com.q43b.tgflip

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView

// The chat list's rows: the same texts as a plain list, plus a photo on the left.
// photoAt gives the photo for a position, or null for rows without one (folders,
// "show more"), which then have no photo space.
class ChatRowAdapter(
    context: Context,
    private val photoAt: (Int) -> Drawable?,
) : ArrayAdapter<CharSequence>(context, R.layout.chat_row, R.id.text, mutableListOf()) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getView(position, convertView, parent)
        val photo = photoAt(position)
        row.findViewById<ImageView>(R.id.photo).apply {
            setImageDrawable(photo)
            visibility = if (photo == null) View.GONE else View.VISIBLE
        }
        return row
    }
}
