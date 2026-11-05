package com.q43b.tgflip

import android.content.Intent
import android.text.InputType
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Settings > Cache: how long media is kept and how much space it may take, clearing
// it, and "Delete all saved data" (also the saved chats and messages: a fresh start
// for when something looks wrong). Reopens after each change, like folder settings.
object CacheMenu {
    fun show(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { MediaCache.sizeBytes(activity) }
            val items = listOf(
                activity.getString(R.string.cache_keep, daysLabel(activity, MediaCache.keepDays(activity))),
                activity.getString(R.string.cache_max, mbLabel(activity, MediaCache.maxMb(activity))),
                activity.getString(R.string.cache_clear_media, Formatter.formatShortFileSize(activity, bytes)),
                activity.getString(R.string.cache_clear_all),
            )
            MessageMenu.choose(activity, activity.getString(R.string.setting_cache), items) { which ->
                when (which) {
                    0 -> pickDays(activity)
                    1 -> pickMb(activity)
                    2 -> confirm(activity, R.string.cache_clear_media_confirm) { clearMedia(activity) }
                    3 -> confirm(activity, R.string.cache_clear_all_confirm) { clearEverything(activity) }
                }
            }
        }
    }

    // The usual choices, then "Custom…" for any number of days
    private fun pickDays(activity: AppCompatActivity) {
        val choices = MediaCache.dayChoices
        val labels = choices.map { daysLabel(activity, it) } + activity.getString(R.string.cache_custom)
        MessageMenu.choose(activity, activity.getString(R.string.cache_keep_title), labels) {
            if (it < choices.size) {
                MediaCache.saveKeepDays(activity, choices[it])
                trimAndReopen(activity)
            } else {
                askNumber(activity, R.string.cache_custom_days, MediaCache.keepDays(activity)) { days ->
                    MediaCache.saveKeepDays(activity, days)
                    trimAndReopen(activity)
                }
            }
        }
    }

    private fun pickMb(activity: AppCompatActivity) {
        val choices = MediaCache.mbChoices
        val labels = choices.map { mbLabel(activity, it) } + activity.getString(R.string.cache_custom)
        MessageMenu.choose(activity, activity.getString(R.string.cache_max_title), labels) {
            if (it < choices.size) {
                MediaCache.saveMaxMb(activity, choices[it])
                trimAndReopen(activity)
            } else {
                askNumber(activity, R.string.cache_custom_mb, MediaCache.maxMb(activity)) { mb ->
                    MediaCache.saveMaxMb(activity, mb)
                    trimAndReopen(activity)
                }
            }
        }
    }

    // A whole number from 1 to MAX_CUSTOM (0, "no limit", is one of the usual choices).
    // Anything else is refused with a note, and the menu reopens.
    private fun askNumber(activity: AppCompatActivity, @StringRes title: Int, current: Int, onDone: (Int) -> Unit) {
        val initial = if (current > 0) current.toString() else ""
        MessageMenu.askText(activity, activity.getString(title), initial, InputType.TYPE_CLASS_NUMBER) { text ->
            val number = text.toIntOrNull()
            if (number != null && number in 1..MAX_CUSTOM) {
                onDone(number)
            } else {
                Toast.makeText(activity, activity.getString(R.string.cache_custom_invalid, MAX_CUSTOM), Toast.LENGTH_LONG).show()
                show(activity)
            }
        }
    }

    // A stricter limit applies at once
    private fun trimAndReopen(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { MediaCache.trim(activity) }
            Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show()
            show(activity)
        }
    }

    private fun clearMedia(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { MediaCache.clear(activity) }
            Toast.makeText(activity, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
            show(activity)
        }
    }

    // Screens hold their own copies of chats and photos in memory, so start the app
    // over from the chat list, which then loads everything fresh from the server
    private fun clearEverything(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { MediaCache.clearEverything(activity) }
            Toast.makeText(activity, R.string.cache_all_cleared, Toast.LENGTH_SHORT).show()
            activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }

    private fun confirm(activity: AppCompatActivity, @StringRes message: Int, onYes: () -> Unit) {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(R.string.cache_delete) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // The usual choices by name, a custom number as "45 days"
    private fun daysLabel(activity: AppCompatActivity, days: Int) = when (days) {
        0 -> activity.getString(R.string.cache_forever)
        1 -> activity.getString(R.string.cache_1_day)
        7 -> activity.getString(R.string.cache_1_week)
        30 -> activity.getString(R.string.cache_1_month)
        90 -> activity.getString(R.string.cache_3_months)
        else -> activity.resources.getQuantityString(R.plurals.cache_days, days, days)
    }

    private const val MAX_CUSTOM = 100_000

    private fun mbLabel(activity: AppCompatActivity, mb: Int) =
        if (mb == 0) activity.getString(R.string.cache_no_limit) else activity.getString(R.string.cache_mb, mb)
}
