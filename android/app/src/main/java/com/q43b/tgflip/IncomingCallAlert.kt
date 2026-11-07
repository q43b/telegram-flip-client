package com.q43b.tgflip

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

// "☎ Incoming call: Anna is calling" while someone is calling, on whichever screen is open.
// The app can't take calls (no voice), so it asks to answer elsewhere.
// It closes by itself when that call stops ringing, or with OK / Back.
// Works only while the app is open: in the background there is no live connection.
object IncomingCallAlert {
    private var dialog: AlertDialog? = null
    private var callId: Long? = null

    // Every screen passes its live events here first; true = it was a call event
    fun handle(activity: Activity, event: JSONObject): Boolean {
        when (event.optString("type")) {
            "call_ringing" -> show(activity, event)
            "call_ended" -> if (event.optLong("call_id") == callId) close()
            else -> return false
        }
        return true
    }

    private fun show(activity: Activity, event: JSONObject) {
        close()  // a previous call's alert, if any
        callId = event.optLong("call_id")
        val title = if (event.optBoolean("video")) R.string.incoming_video_call else R.string.incoming_call
        val name = event.optString("name").ifEmpty { activity.getString(R.string.someone) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(activity.getString(R.string.incoming_call_message, name))
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .also { Themer.apply(it) }
    }

    private fun close() {
        dialog?.dismiss()
        dialog = null
        callId = null
    }
}
