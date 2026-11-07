package com.q43b.tgflip

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import org.json.JSONObject
import java.io.File

// A theme (docs/themes-plan.md): data only, never code. theme.json fills in colors and sizes
// and picks among the shapes the app knows how to draw (Themer paints the screens from it).
// Format 1 only ever grows: unknown keys are ignored, missing ones come from `base` (for
// downloaded themes, the built-in Pixel theme), so a theme made for a newer app still works.
class Theme(
    val id: String,
    val name: String,
    val version: String,
    val light: Palette,
    val dark: Palette,
    val shapes: Shapes,
    val sizes: Sizes,
    val frameFont: Typeface?,  // titles, bars, menus, lists; null = the phone's font
    val textFont: Typeface?,  // message text and the message box; null = the phone's font
) {
    class Palette(
        val background: Int, val text: Int, val textSecondary: Int,
        val bar: Int, val barText: Int,
        val focus: Int, val focusOutline: Int, val divider: Int, val accent: Int,
        val connecting: Int, val connected: Int, val offline: Int,
    )

    class Shapes(
        val focus: String,  // fill, frame, marker
        val corners: String,  // square, round, notch
        val outlineDp: Float,
        val divider: String,  // solid, dashed, none
        val dividerDp: Float,
        val activeTab: String,  // bar: the open tab is plain bar, the other boxed; box: the open one boxed
    )

    class Sizes(val textSp: Float, val smallSp: Float, val titleSp: Float, val rowPaddingDp: Float)

    fun palette(context: Context): Palette {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) dark else light
    }

    companion object {
        const val FORMAT = 1

        // fontFile opens a font named in theme.json (inside the theme's folder), or gives null
        fun parse(json: JSONObject, base: Theme?, fontFile: (String) -> Typeface?): Theme {
            val palettes = json.optJSONObject("palettes")
            val shapes = json.optJSONObject("shapes") ?: JSONObject()
            val sizes = json.optJSONObject("sizes") ?: JSONObject()
            val fonts = json.optJSONObject("fonts") ?: JSONObject()
            return Theme(
                id = json.optString("id", base?.id ?: "theme"),
                name = json.optString("name", base?.name ?: "Theme"),
                version = json.optString("version", base?.version ?: "1.0.0"),
                light = palette(palettes?.optJSONObject("light"), base?.light ?: DEFAULT_PALETTE),
                dark = palette(palettes?.optJSONObject("dark"), base?.dark ?: DEFAULT_PALETTE),
                shapes = Shapes(
                    focus = shapes.optString("focus", base?.shapes?.focus ?: "fill"),
                    corners = shapes.optString("corners", base?.shapes?.corners ?: "square"),
                    outlineDp = shapes.optDouble("outline_dp", (base?.shapes?.outlineDp ?: 2f).toDouble()).toFloat(),
                    divider = shapes.optString("divider", base?.shapes?.divider ?: "solid"),
                    dividerDp = shapes.optDouble("divider_dp", (base?.shapes?.dividerDp ?: 1f).toDouble()).toFloat(),
                    activeTab = shapes.optString("active_tab", base?.shapes?.activeTab ?: "bar"),
                ),
                sizes = Sizes(
                    textSp = size(sizes, "text_sp", base?.sizes?.textSp ?: 16f),
                    smallSp = size(sizes, "small_sp", base?.sizes?.smallSp ?: 13f),
                    titleSp = size(sizes, "title_sp", base?.sizes?.titleSp ?: 18f),
                    rowPaddingDp = size(sizes, "row_padding_dp", base?.sizes?.rowPaddingDp ?: 6f),
                ),
                frameFont = font(fonts, "frame", fontFile, base?.frameFont),
                textFont = font(fonts, "text", fontFile, base?.textFont),
            )
        }

        private fun palette(json: JSONObject?, base: Palette): Palette {
            fun color(key: String, fallback: Int): Int = try {
                json?.optString(key)?.takeIf { it.isNotEmpty() }?.let(Color::parseColor) ?: fallback
            } catch (e: IllegalArgumentException) {
                fallback  // not a color: keep the base theme's
            }
            return Palette(
                background = color("background", base.background), text = color("text", base.text),
                textSecondary = color("text_secondary", base.textSecondary),
                bar = color("bar", base.bar), barText = color("bar_text", base.barText),
                focus = color("focus", base.focus), focusOutline = color("focus_outline", base.focusOutline),
                divider = color("divider", base.divider), accent = color("accent", base.accent),
                connecting = color("connecting", base.connecting), connected = color("connected", base.connected),
                offline = color("offline", base.offline),
            )
        }

        // Sizes are kept within reason, so a theme can't make the app unusable
        private fun size(json: JSONObject, key: String, fallback: Float): Float =
            json.optDouble(key, fallback.toDouble()).toFloat().takeIf { it in 0f..48f } ?: fallback

        // A key set to null means the phone's own font; a font without the Russian alphabet
        // (the owner's chats are in Russian) is refused and the phone's font used instead
        private fun font(json: JSONObject, key: String, fontFile: (String) -> Typeface?, fallback: Typeface?): Typeface? {
            if (!json.has(key)) return fallback
            if (json.isNull(key)) return null
            val typeface = fontFile(json.getString(key)) ?: return fallback
            val paint = Paint().apply { this.typeface = typeface }
            return if ("АБЖЯабжя".all { paint.hasGlyph(it.toString()) }) typeface else {
                Log.i("TGFlip", "Theme font ${json.getString(key)} has no Russian letters: using the phone's")
                null
            }
        }

        // Only for keys even the built-in theme leaves out: plain black on white
        private val DEFAULT_PALETTE = Palette(
            background = Color.WHITE, text = Color.BLACK, textSecondary = Color.DKGRAY,
            bar = Color.DKGRAY, barText = Color.WHITE, focus = Color.LTGRAY, focusOutline = Color.BLACK,
            divider = Color.LTGRAY, accent = Color.RED,
            connecting = Color.GRAY, connected = Color.rgb(0x2E, 0x7D, 0x32), offline = Color.rgb(0xC6, 0x28, 0x28),
        )
    }
}

// The theme in use, and the UI size. The built-in Pixel theme lives in assets/themes/pixel/;
// installed themes (ThemeInstaller: from a file, or from a server's themes_host module) in
// filesDir/themes/<id>/, the same folder format. A chosen theme that's gone or broken falls
// back to the built-in one.
object Themes {
    private const val BUILT_IN = "themes/pixel"
    private const val KEY_THEME = "theme_id"
    private const val KEY_UI_SIZE = "ui_size"
    val UI_SIZES = listOf(80, 90, 100, 115, 130)  // percent; Custom… takes any in UI_SIZE_RANGE
    val UI_SIZE_RANGE = 50..200

    @Volatile private var builtIn: Theme? = null
    @Volatile private var chosen: Theme? = null

    fun current(context: Context): Theme = chosen ?: synchronized(this) {
        chosen ?: run {
            val app = context.applicationContext
            val id = prefs(app).getString(KEY_THEME, null)
            (if (id == null || id == builtIn(app).id) null else load(app, id)) ?: builtIn(app)
        }.also { chosen = it }
    }

    fun builtIn(context: Context): Theme = builtIn ?: synchronized(this) {
        builtIn ?: loadBuiltIn(context.applicationContext).also { builtIn = it }
    }

    // The built-in theme first, then the installed ones by name
    fun all(context: Context): List<Theme> {
        val installed = folder(context).listFiles()?.filter { it.isDirectory && !it.name.endsWith(".new") }
            ?.mapNotNull { load(context, it.name) }.orEmpty()
        return listOf(builtIn(context)) + installed.sortedBy { it.name.lowercase() }
    }

    fun isBuiltIn(context: Context, theme: Theme) = theme.id == builtIn(context).id

    fun choose(context: Context, id: String) {
        prefs(context).edit().putString(KEY_THEME, id).apply()
        chosen = null
    }

    fun remove(context: Context, id: String) {
        File(folder(context), id).deleteRecursively()
        if (prefs(context).getString(KEY_THEME, null) == id) prefs(context).edit().remove(KEY_THEME).apply()
        chosen = null
    }

    // Where installed themes are kept: filesDir/themes/<id>/
    fun folder(context: Context) = File(context.filesDir, "themes")

    // An installed theme, on top of the built-in one (keys it leaves out come from Pixel);
    // null when it's missing or broken
    fun load(context: Context, id: String): Theme? {
        val dir = File(folder(context), id)
        return try {
            val json = JSONObject(File(dir, "theme.json").readText())
            Theme.parse(json, builtIn(context)) { path ->
                val file = File(dir, path).canonicalFile
                if (file.path.startsWith(dir.canonicalPath + File.separator) && file.isFile) {
                    try { Typeface.createFromFile(file) } catch (e: RuntimeException) { null }
                } else {
                    null  // outside the theme's folder, or missing
                }
            }
        } catch (e: Exception) {
            Log.i("TGFlip", "Theme $id can't be loaded: $e")
            null
        }
    }

    private fun loadBuiltIn(context: Context): Theme {
        val json = context.assets.open("$BUILT_IN/theme.json").bufferedReader().use { it.readText() }
        return Theme.parse(JSONObject(json), base = null) { path ->
            try {
                Typeface.createFromAsset(context.assets, "$BUILT_IN/$path")
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    // Every theme size is multiplied by this (Appearance > UI size)
    fun scale(context: Context): Float = uiSize(context) / 100f

    fun uiSize(context: Context): Int = prefs(context).getInt(KEY_UI_SIZE, 100).coerceIn(UI_SIZE_RANGE)

    fun saveUiSize(context: Context, percent: Int) = prefs(context).edit().putInt(KEY_UI_SIZE, percent).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
