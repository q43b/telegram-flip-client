package com.q43b.tgflip

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// The theme menus (docs/themes-plan.md step 3): Appearance > Theme (pick one; an installed one
// can also be removed), Appearance > Get themes from the server (only when the server's modules
// provide "themes_host", the themes_host module), and Under the hood > Load a theme from a file.
// 1.2.0 looked for "themes" at /modules/themes/; since 1.2.1 the module is named themes_host
// (it hosts themes; themes already on the phone work with any server).
// Changing the theme starts the app over at the chat list and comes back to `back` (the screen
// the change was made on): screens read some colors once, when they open.
object ThemeMenu {
    const val CAPABILITY = "themes_host"

    fun pick(activity: AppCompatActivity, back: List<Intent>) {
        val current = Themes.current(activity)
        val themes = Themes.all(activity)
        val labels = themes.map { theme ->
            val mark = if (theme.id == current.id) "✓ " else ""
            if (Themes.isBuiltIn(activity, theme)) {
                activity.getString(R.string.theme_built_in, mark + theme.name)
            } else {
                "$mark${theme.name} ${theme.version}"
            }
        }
        MessageMenu.choose(activity, activity.getString(R.string.look_title), labels) { which ->
            val theme = themes[which]
            if (Themes.isBuiltIn(activity, theme)) {
                use(activity, theme, back)
            } else {
                installedMenu(activity, theme, back)
            }
        }
    }

    private fun installedMenu(activity: AppCompatActivity, theme: Theme, back: List<Intent>) {
        val use = activity.getString(R.string.theme_use)
        val remove = activity.getString(R.string.theme_remove)
        MessageMenu.choose(activity, "${theme.name} ${theme.version}", listOf(use, remove)) { which ->
            if (which == 0) {
                use(activity, theme, back)
            } else {
                AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.theme_remove_confirm, theme.name))
                    .setPositiveButton(R.string.theme_remove) { _, _ ->
                        val wasCurrent = Themes.current(activity).id == theme.id
                        Themes.remove(activity, theme.id)
                        if (wasCurrent) restart(activity, back) else Toast.makeText(activity, R.string.saved, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .also { Themer.apply(it) }
            }
        }
    }

    private fun use(activity: AppCompatActivity, theme: Theme, back: List<Intent>) {
        if (theme.id == Themes.current(activity).id) return
        Themes.choose(activity, theme.id)
        restart(activity, back)
    }

    // A theme zip chosen on the phone (Under the hood): install it, then offer to use it
    fun installFile(activity: AppCompatActivity, uri: Uri, back: List<Intent>) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val input = activity.contentResolver.openInputStream(uri) ?: throw IOException("Can't open")
                    input.use { Result.success(ThemeInstaller.install(activity, it)) }
                } catch (e: ThemeInstaller.ThemeException) {
                    Result.failure(e)
                } catch (e: IOException) {
                    Result.failure(ThemeInstaller.ThemeException(activity.getString(R.string.theme_error_not_zip)))
                }
            }
            installed(activity, result, back)
        }
    }

    // The server's themes (its themes_host module); installed ones are marked with their version
    fun fromServer(activity: AppCompatActivity, back: List<Intent>) {
        val settings = ServerSettings(activity)
        val api = ApiClient(settings.getAddress(), settings.getToken().orEmpty())
        activity.lifecycleScope.launch {
            val themes = try {
                withContext(Dispatchers.IO) { api.getThemes() }
            } catch (e: IOException) {
                Toast.makeText(activity, R.string.theme_server_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (themes.isEmpty()) {
                Toast.makeText(activity, R.string.theme_server_none, Toast.LENGTH_LONG).show()
                return@launch
            }
            val installed = Themes.all(activity).associateBy { it.id }
            val labels = themes.map { t ->
                val have = installed[t.id]?.let { activity.getString(R.string.theme_server_installed, it.version) }.orEmpty()
                "${t.name} ${t.version} · ${t.size / 1024} KB$have"
            }
            MessageMenu.choose(activity, activity.getString(R.string.theme_server_title), labels) { which ->
                download(activity, api, themes[which], back)
            }
        }
    }

    private fun download(activity: AppCompatActivity, api: ApiClient, theme: ServerTheme, back: List<Intent>) {
        if (theme.size > ThemeInstaller.MAX_BYTES) {
            Toast.makeText(activity, R.string.theme_error_too_big, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, activity.getString(R.string.theme_downloading, theme.name), Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(ThemeInstaller.install(activity, api.getThemeZip(theme.id), theme.sha256))
                } catch (e: ThemeInstaller.ThemeException) {
                    Result.failure(e)
                } catch (e: IOException) {
                    Result.failure(ThemeInstaller.ThemeException(activity.getString(R.string.theme_server_failed)))
                }
            }
            installed(activity, result, back)
        }
    }

    private fun installed(activity: AppCompatActivity, result: Result<Theme>, back: List<Intent>) {
        val theme = result.getOrElse {
            AlertDialog.Builder(activity)
                .setTitle(R.string.theme_error_title)
                .setMessage(it.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
                .also { dialog -> Themer.apply(dialog) }
            return
        }
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.theme_installed, theme.name, theme.version))
            .setPositiveButton(R.string.theme_use) { _, _ ->
                Themes.choose(activity, theme.id)
                restart(activity, back)
            }
            .setNegativeButton(R.string.theme_later, null)
            .show()
            .also { Themer.apply(it) }
    }

    // The chat list fresh, then the screens the change was made from
    private fun restart(activity: AppCompatActivity, back: List<Intent>) {
        val main = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivities((listOf(main) + back).toTypedArray())
    }
}
