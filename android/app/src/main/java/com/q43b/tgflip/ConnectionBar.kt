package com.q43b.tgflip

import android.widget.TextView

// Updates the bar from connection_bar.xml to match the live connection state.
fun TextView.showConnection(state: LiveConnection.Status) {
    val (text, color) = when (state) {
        LiveConnection.Status.CONNECTING -> R.string.conn_connecting to R.color.conn_connecting
        LiveConnection.Status.CONNECTED -> R.string.conn_connected to R.color.conn_connected
        LiveConnection.Status.OFFLINE -> R.string.conn_offline to R.color.conn_offline
    }
    setText(text)
    setBackgroundResource(color)
}
