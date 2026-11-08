package com.q43b.tgflip

import android.content.Context

// Saves and loads the connection mode, one server address per mode, and the token,
// in the app's private storage. getAddress() without a mode gives the active mode's.
// The token and the addresses belong to the current account (Profiles); the mode is shared.
class ServerSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val suffix get() = Profiles.suffix(context)

    fun getMode(): ConnectionMode = ConnectionMode.fromKey(prefs.getString(KEY_MODE, null))

    fun saveMode(mode: ConnectionMode) {
        prefs.edit().putString(KEY_MODE, mode.key).apply()
    }

    // Before modes existed there was one address; it was the home Wi-Fi one
    fun getAddress(mode: ConnectionMode = getMode()): String? =
        prefs.getString(addressKey(mode) + suffix, null)
            ?: if (mode == ConnectionMode.LOCAL && suffix.isEmpty()) prefs.getString(KEY_OLD_ADDRESS, null) else null

    fun saveAddress(address: String, mode: ConnectionMode = getMode()) {
        prefs.edit().putString(addressKey(mode) + suffix, address).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN + suffix, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN + suffix, token).apply()
    }

    companion object {
        private fun addressKey(mode: ConnectionMode) = "server_address_${mode.key}"

        // Every key that belongs to the account with this suffix, for Profiles.remove
        fun keysFor(suffix: String) = ConnectionMode.entries.map { addressKey(it) + suffix } + (KEY_TOKEN + suffix)

        private const val KEY_MODE = "connection_mode"
        private const val KEY_OLD_ADDRESS = "server_address"
        private const val KEY_TOKEN = "api_token"
    }
}
