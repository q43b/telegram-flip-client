package com.q43b.tgflip

import android.widget.TextView

// Updates the bar from connection_bar.xml to match the live connection state,
// with the connection mode in use, e.g. "Connected · VPN".
fun TextView.showConnection(state: LiveConnection.Status) {
    val p = Themes.current(context).palette(context)
    val (text, color) = when (state) {
        LiveConnection.Status.CONNECTING -> R.string.conn_connecting to p.connecting
        LiveConnection.Status.CONNECTED -> R.string.conn_connected to p.connected
        LiveConnection.Status.OFFLINE -> R.string.conn_offline to p.offline
    }
    show(text, color)
}

// The active mode has no server address, so there is nothing to connect to
fun TextView.showNoServer() = show(R.string.conn_no_server, Themes.current(context).palette(context).offline)

// color: from the theme's palette (connecting / connected / offline)
private fun TextView.show(text: Int, color: Int) {
    val mode = context.getString(ServerSettings(context).getMode().shortLabel)
    setText("${context.getString(text)} · $mode")
    setBackgroundColor(color)
}
