package com.q43b.tgflip

import android.content.Context

// Saves and loads the server address and token in the app's private storage.
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getAddress(): String? = prefs.getString(KEY_ADDRESS, null)

    fun saveAddress(address: String) {
        prefs.edit().putString(KEY_ADDRESS, address).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    private companion object {
        const val KEY_ADDRESS = "server_address"
        const val KEY_TOKEN = "api_token"
    }
}
