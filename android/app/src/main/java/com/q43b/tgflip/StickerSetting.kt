package com.q43b.tgflip

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes

// Settings > Media and Cache: how stickers (and later custom emoji) show, and from which
// packs they may download. See docs/stickers-plan.md.
// - DATA_SAVER: nothing downloads and saved pictures aren't used either; stickers are text.
// - STILL: still pictures (the default).
// - ANIMATED (since 0.16.0-beta): stickers play, where the server has its animated stickers
//   module (capability animated_stickers) and the phone has Android 9 or newer.
object StickerSetting {
    enum class Mode(val key: String, @StringRes val label: Int) {
        DATA_SAVER("off", R.string.stickers_data_saver),
        STILL("still", R.string.stickers_still),
        ANIMATED("animated", R.string.stickers_animated),
    }

    // CHOSEN (only packs ticked in a pack list) comes with sticker sending
    enum class Packs(val key: String, @StringRes val label: Int) {
        ANY("any", R.string.sticker_packs_any),
        CHOSEN("chosen", R.string.sticker_packs_chosen),
    }

    private const val KEY_MODE = "sticker_mode"
    private const val KEY_PACKS = "sticker_packs"
    private const val KEY_PICKER_ALL = "sticker_picker_all"

    fun mode(context: Context): Mode =
        Mode.entries.firstOrNull { it.key == prefs(context).getString(KEY_MODE, null) } ?: Mode.STILL

    fun saveMode(context: Context, mode: Mode) = prefs(context).edit().putString(KEY_MODE, mode.key).apply()

    fun packs(context: Context): Packs =
        Packs.entries.firstOrNull { it.key == prefs(context).getString(KEY_PACKS, null) } ?: Packs.ANY

    // Pictures are wanted (still or animated)
    fun showsPictures(context: Context) = mode(context) != Mode.DATA_SAVER

    // Animated stickers are asked for and played: Animated chosen, on Android 9+ (where
    // AnimatedImageDrawable exists)
    fun plays(context: Context) = mode(context) == Mode.ANIMATED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    // The sticker pack screen: false = only the highlighted sticker plays (the d-pad phone's way, light
    // on the phone); true = every sticker in view plays, for touchscreens where nothing is highlighted
    fun pickerAll(context: Context) = prefs(context).getBoolean(KEY_PICKER_ALL, false)

    fun savePickerAll(context: Context, all: Boolean) = prefs(context).edit().putBoolean(KEY_PICKER_ALL, all).apply()

    // How many playing stickers the pack screen keeps loaded in "all play" mode (more than a screenful)
    const val PICKER_ALL_KEPT = 40

    const val CAPABILITY = "animated_stickers"

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
