package com.q43b.tgflip

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

// Installs a theme zip (docs/themes-plan.md) from a file on the phone or from the server's
// themes module. A theme is data from elsewhere, so the zip is read whole into memory first
// and checked before anything is written: at most MAX_BYTES unpacked and MAX_FILES files, only
// theme.json, fonts and text files, no paths outside the theme (no "..", no absolute paths),
// theme.json at the top or inside one top folder, an id of plain letters, and a theme that
// loads. Then it's written to filesDir/themes/<id>.new/ and swapped in for any older copy.
object ThemeInstaller {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_FILES = 32
    private val ALLOWED = Regex("""[A-Za-z0-9_\-./ ]+\.(json|ttf|otf|txt|md)""")
    private val ID = Regex("[a-z0-9_-]{1,40}")

    class ThemeException(message: String) : Exception(message)

    // The installed theme; ThemeException says why one was refused
    fun install(context: Context, zip: InputStream): Theme {
        val files = read(zip)
        val top = files.keys.map { it.substringBefore('/') }.toSet()
        // theme.json at the top, or everything in one folder with theme.json in it
        val prefix = when {
            "theme.json" in files -> ""
            top.size == 1 && "${top.first()}/theme.json" in files -> "${top.first()}/"
            else -> throw ThemeException(context.getString(R.string.theme_error_no_json))
        }
        val json = try {
            JSONObject(String(files.getValue("${prefix}theme.json"), Charsets.UTF_8))
        } catch (e: Exception) {
            throw ThemeException(context.getString(R.string.theme_error_json))
        }
        val id = json.optString("id")
        if (!ID.matches(id)) throw ThemeException(context.getString(R.string.theme_error_id))
        if (id == Themes.builtIn(context).id) throw ThemeException(context.getString(R.string.theme_error_built_in))

        val folder = Themes.folder(context)
        val fresh = File(folder, "$id.new")
        fresh.deleteRecursively()
        for ((name, bytes) in files) {
            if (!name.startsWith(prefix)) continue
            val file = File(fresh, name.removePrefix(prefix))
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        val old = File(folder, id)
        old.deleteRecursively()
        if (!fresh.renameTo(old)) {
            fresh.deleteRecursively()
            throw ThemeException(context.getString(R.string.theme_error_write))
        }
        return Themes.load(context, id) ?: run {
            old.deleteRecursively()
            throw ThemeException(context.getString(R.string.theme_error_json))
        }
    }

    // The same, for a zip whose sha256 is known (from the server's list); a mismatch is refused
    fun install(context: Context, zip: ByteArray, sha256: String): Theme {
        val actual = MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256, ignoreCase = true)) throw ThemeException(context.getString(R.string.theme_error_sha256))
        return install(context, zip.inputStream())
    }

    private fun read(input: InputStream): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("/") || name.contains("\\") || name.split('/').any { it == ".." || it == "." } ||
                    !ALLOWED.matches(name)) {
                    throw ThemeException("Not allowed in a theme: $name")
                }
                if (files.size >= MAX_FILES) throw ThemeException("Too many files (at most $MAX_FILES)")
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw ThemeException("Too big (at most ${MAX_BYTES / 1024 / 1024} MB)")
                    out.write(buffer, 0, n)
                }
                files[name] = out.toByteArray()
            }
        }
        if (files.isEmpty()) throw ThemeException("Not a theme zip")
        return files
    }
}
