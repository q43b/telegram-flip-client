package com.q43b.tgflip

import android.content.Context
import android.os.Build

// Which format voice messages are played in. Telegram keeps them as OGG/Opus, which
// Android plays from version 10. "Compatible" is for Android 7-9: the server is to convert
// them into a format those versions play. That conversion comes in a later version; until
// then the player only says so. On by default on phones older than Android 10.
object VoiceFormat {
    private const val KEY = "voice_compatible"

    fun isCompatible(context: Context) =
        prefs(context).getBoolean(KEY, Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)

    fun saveCompatible(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY, on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
