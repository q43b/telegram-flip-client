package com.q43b.tgflip

import android.widget.TextView

// Updates the bar from connection_bar.xml to match the live connection state,
// with the connection mode in use, e.g. "Connected · VPN".
fun TextView.showConnection(state: LiveConnection.Status) {
    val (text, color) = when (state) {
        LiveConnection.Status.CONNECTING -> R.string.conn_connecting to R.color.conn_connecting
        LiveConnection.Status.CONNECTED -> R.string.conn_connected to R.color.conn_connected
        LiveConnection.Status.OFFLINE -> R.string.conn_offline to R.color.conn_offline
    }
    show(text, color)
}

// The active mode has no server address, so there is nothing to connect to
fun TextView.showNoServer() = show(R.string.conn_no_server, R.color.conn_offline)

private fun TextView.show(text: Int, color: Int) {
    val mode = context.getString(ServerSettings(context).getMode().shortLabel)
    setText("${context.getString(text)} · $mode")
    setBackgroundResource(color)
}
