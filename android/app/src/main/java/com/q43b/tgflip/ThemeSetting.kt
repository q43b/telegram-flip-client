package com.q43b.tgflip

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

// Light, dark, or follow the phone's own setting. Saved in the app's private settings
// file and applied by TGFlipApp when the app starts. The theme (DayNight) does the rest.
object ThemeSetting {
    private const val KEY = "night_mode"

    // The choices in the order Settings lists them
    val choices = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.string.theme_follow_phone,
        AppCompatDelegate.MODE_NIGHT_NO to R.string.theme_light,
        AppCompatDelegate.MODE_NIGHT_YES to R.string.theme_dark,
    )

    fun load(context: Context): Int =
        prefs(context).getInt(KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    @StringRes
    fun label(mode: Int): Int = choices.firstOrNull { it.first == mode }?.second ?: R.string.theme_follow_phone

    // Saving also switches at once: open screens redraw in the new theme
    fun save(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun apply(context: Context) = AppCompatDelegate.setDefaultNightMode(load(context))

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
