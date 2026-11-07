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

// The rows of Settings > Media and Cache: how long media is kept and how much space it may
// take, clearing it, the separate sticker cache (with its own limits and clearing while on),
// and "Delete all saved data" (also the saved chats and messages: a fresh start for when
// something looks wrong). refresh redraws the rows after a change.
object CacheMenu {
    fun rows(activity: AppCompatActivity, bytes: Long, stickerBytes: Long, refresh: () -> Unit): List<SettingRow> {
        val separate = MediaCache.separateStickers(activity)
        val media = listOf(
            SettingRow(activity.getString(R.string.cache_keep, daysLabel(activity, MediaCache.keepDays(activity)))) {
                pickDays(activity, R.string.cache_keep_title, MediaCache.keepDays(activity),
                    { MediaCache.saveKeepDays(activity, it) }, refresh)
            },
            SettingRow(activity.getString(R.string.cache_max, mbLabel(activity, MediaCache.maxMb(activity)))) {
                pickMb(activity, R.string.cache_max_title, MediaCache.maxMb(activity),
                    { MediaCache.saveMaxMb(activity, it) }, refresh)
            },
            SettingRow(activity.getString(R.string.cache_clear_media, Formatter.formatShortFileSize(activity, bytes))) {
                confirm(activity, R.string.cache_clear_media_confirm) { clearMedia(activity, refresh) }
            },
            SettingRow(activity.getString(R.string.cache_separate_stickers,
                activity.getString(if (separate) R.string.on else R.string.off))) {
                MediaCache.saveSeparateStickers(activity, !separate)
                trimAndRefresh(activity, refresh)
            },
        )
        val stickers = if (!separate) emptyList() else listOf(
            SettingRow(activity.getString(R.string.cache_sticker_keep, daysLabel(activity, MediaCache.stickerKeepDays(activity)))) {
                pickDays(activity, R.string.cache_sticker_keep_title, MediaCache.stickerKeepDays(activity),
                    { MediaCache.saveStickerKeepDays(activity, it) }, refresh)
            },
            SettingRow(activity.getString(R.string.cache_sticker_max, mbLabel(activity, MediaCache.stickerMaxMb(activity)))) {
                pickMb(activity, R.string.cache_sticker_max_title, MediaCache.stickerMaxMb(activity),
                    { MediaCache.saveStickerMaxMb(activity, it) }, refresh)
            },
            SettingRow(activity.getString(R.string.cache_clear_stickers, Formatter.formatShortFileSize(activity, stickerBytes))) {
                confirm(activity, R.string.cache_clear_stickers_confirm) { clearStickers(activity, refresh) }
            },
        )
        val all = SettingRow(activity.getString(R.string.cache_clear_all)) {
            confirm(activity, R.string.cache_clear_all_confirm) { clearEverything(activity) }
        }
        return media + stickers + all
    }

    // The usual choices, then "Custom…" for any number of days; save stores the choice
    private fun pickDays(activity: AppCompatActivity, @StringRes title: Int, current: Int, save: (Int) -> Unit, refresh: () -> Unit) {
        val choices = MediaCache.dayChoices
        val labels = choices.map { daysLabel(activity, it) } + activity.getString(R.string.cache_custom)
        MessageMenu.choose(activity, activity.getString(title), labels) {
            if (it < choices.size) {
                save(choices[it])
                trimAndRefresh(activity, refresh)
            } else {
                askNumber(activity, R.string.cache_custom_days, current) { days ->
                    save(days)
                    trimAndRefresh(activity, refresh)
                }
            }
        }
    }

    private fun pickMb(activity: AppCompatActivity, @StringRes title: Int, current: Int, save: (Int) -> Unit, refresh: () -> Unit) {
        val choices = MediaCache.mbChoices
        val labels = choices.map { mbLabel(activity, it) } + activity.getString(R.string.cache_custom)
        MessageMenu.choose(activity, activity.getString(title), labels) {
            if (it < choices.size) {
                save(choices[it])
                trimAndRefresh(activity, refresh)
            } else {
                askNumber(activity, R.string.cache_custom_mb, current) { mb ->
                    save(mb)
                    trimAndRefresh(activity, refresh)
                }
            }
        }
    }

    // A whole number from 1 to MAX_CUSTOM (0, "no limit", is one of the usual choices).
    // Anything else is refused with a note, and nothing changes.
    private fun askNumber(activity: AppCompatActivity, @StringRes title: Int, current: Int, onDone: (Int) -> Unit) {
        val initial = if (current > 0) current.toString() else ""
        MessageMenu.askText(activity, activity.getString(title), initial, InputType.TYPE_CLASS_NUMBER) { text ->
            val number = text.toIntOrNull()
            if (number != null && number in 1..MAX_CUSTOM) {
                onDone(number)
            } else {
                Toast.makeText(activity, activity.getString(R.string.cache_custom_invalid, MAX_CUSTOM), Toast.LENGTH_LONG).show()
            }
        }
    }

    // A stricter limit applies at once
    private fun trimAndRefresh(activity: AppCompatActivity, refresh: () -> Unit) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { MediaCache.trim(activity) }
            Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun clearMedia(activity: AppCompatActivity, refresh: () -> Unit) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { MediaCache.clear(activity) }
            Toast.makeText(activity, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun clearStickers(activity: AppCompatActivity, refresh: () -> Unit) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { MediaCache.clearStickers(activity) }
            Toast.makeText(activity, R.string.cache_stickers_cleared, Toast.LENGTH_SHORT).show()
            refresh()
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
            .also { Themer.apply(it) }
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
