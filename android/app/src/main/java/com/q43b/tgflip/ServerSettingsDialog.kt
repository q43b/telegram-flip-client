package com.q43b.tgflip

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Edits one connection mode's server (IP or name, port) and the shared token.
// Used by the chat list (first launch and connection errors, for the active mode) and by
// Settings (any mode). Saved as one address; http or https comes from the mode.
object ServerSettingsDialog {

    // cancelable = false where the app cannot work without it (first launch, errors)
    fun show(
        activity: Activity,
        settings: ServerSettings,
        mode: ConnectionMode,
        cancelable: Boolean,
        onSaved: () -> Unit,
    ): AlertDialog {
        val current = settings.getAddress(mode)?.toHttpUrlOrNull()

        val hostInput = EditText(activity)
        hostInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        hostInput.hint = activity.getString(R.string.host_hint)
        hostInput.setText(current?.host ?: mode.defaultHost)

        val portInput = EditText(activity)
        portInput.inputType = InputType.TYPE_CLASS_NUMBER
        portInput.hint = activity.getString(R.string.port_hint)
        portInput.setText((current?.port ?: mode.defaultPort).toString())

        // Visible on purpose: typing a hidden token on a keypad is too error-prone
        val tokenInput = EditText(activity)
        tokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        tokenInput.hint = activity.getString(R.string.token_hint)
        tokenInput.setText(settings.getToken() ?: "")

        val fields = LinearLayout(activity)
        fields.orientation = LinearLayout.VERTICAL
        fields.addView(hostInput)
        fields.addView(portInput)
        fields.addView(tokenInput)

        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.server_for_mode, activity.getString(mode.label)))
            .setView(fields)
            .setCancelable(cancelable)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().trim().toIntOrNull()
                val address = "${mode.scheme}://$host:$port"
                if (host.isEmpty() || port == null || port !in 1..65535 || address.toHttpUrlOrNull() == null) {
                    Toast.makeText(activity, R.string.error_bad_ip_port, Toast.LENGTH_LONG).show()
                    // Ask again: where it is not cancelable, the app needs a valid address
                    show(activity, settings, mode, cancelable, onSaved)
                    return@setPositiveButton
                }
                settings.saveAddress(address, mode)
                settings.saveToken(tokenInput.text.toString().trim())
                onSaved()
            }
        if (cancelable) builder.setNegativeButton(android.R.string.cancel, null)
        return builder.show().also { Themer.apply(it) }
    }
}
