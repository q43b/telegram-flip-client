package com.q43b.tgflip

import android.content.Context

// Saves and loads the connection mode, one server address per mode, and the token,
// in the app's private storage. getAddress() without a mode gives the active mode's.
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getMode(): ConnectionMode = ConnectionMode.fromKey(prefs.getString(KEY_MODE, null))

    fun saveMode(mode: ConnectionMode) {
        prefs.edit().putString(KEY_MODE, mode.key).apply()
    }

    // Before modes existed there was one address; it was the home Wi-Fi one
    fun getAddress(mode: ConnectionMode = getMode()): String? =
        prefs.getString(addressKey(mode), null)
            ?: if (mode == ConnectionMode.LOCAL) prefs.getString(KEY_OLD_ADDRESS, null) else null

    fun saveAddress(address: String, mode: ConnectionMode = getMode()) {
        prefs.edit().putString(addressKey(mode), address).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    private fun addressKey(mode: ConnectionMode) = "server_address_${mode.key}"

    private companion object {
        const val KEY_MODE = "connection_mode"
        const val KEY_OLD_ADDRESS = "server_address"
        const val KEY_TOKEN = "api_token"
    }
}
