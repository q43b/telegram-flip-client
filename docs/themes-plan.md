# Themes and the UI overhaul (plan)

Status: planned 2026-11-07 at v1.1.0; steps 1-3 built in 1.2.0 (step 4, the server module, is next). Owner's choices: the default look is **Pixel** (like the
app's icons: sky blue, dark outlines, a pixel font); other looks (Classic 2000s phone,
Sketchbook, Terminal; mockups 2026-11-07) come later as **themes the server hands out**, through a
`themes` server module, the same way modules work. Sizes must be changeable: the owner may want a
smaller UI to fit more on the screen, and friends with smartphones may use the app one day.

## Rules

- **A theme is data, never code.** Android can't load new code or resource files into an
  installed app, and a theme from a server must not be able to do anything harmful. So a theme
  only fills in values and picks among drawing choices the app already knows; the app paints
  every screen from them (`Themer`). Message text is still never rendered as HTML.
- **The default theme is just the first theme**: it ships inside the APK (`assets/themes/pixel/`)
  in exactly the format downloaded themes use, so every screen goes through one engine and the
  format is proven by the theme everybody sees.
- **Backwards compatible, like the module API**: `format` 1 only ever grows. An app ignores keys
  it doesn't know; a missing key falls back to the built-in default theme's value. A theme
  with a higher `format` than the app knows is still shown, with what the app understands.
- **Sizes are never fixed in pixels.** Every size in a theme is in dp/sp (so screen density
  is handled: the Kyocera is 480x854 at 240 dpi, 1.5 px per dp), then multiplied by the user's
  **UI size** setting (Appearance: 80 / 90 / 100 / 115 / 130%, default 100%). Layouts stretch to
  any width; rows react to touch as well as the d-pad, as lists already do.
- **D-pad first**: the focused row must always be obvious, in every theme and both palettes.
  A theme's focus fill must stay readable with the theme's normal text color (no separate
  "text on focus" color in format 1).

## Format 1

A theme is a folder (zipped for downloading): `theme.json`, optional fonts, a license file.

```json
{
  "format": 1,
  "id": "pixel",
  "name": "Pixel",
  "version": "1.0.0",
  "author": "q43b",
  "palettes": {
    "light": {"background": "#99D9EA", "text": "#0B1E3A", "text_secondary": "#2E5A78",
              "bar": "#0B1E3A", "bar_text": "#FFFFFF", "focus": "#FFFFFF", "focus_outline": "#0B1E3A",
              "divider": "#5FA9C0", "accent": "#E8590C",
              "connecting": "#5F6B78", "connected": "#2E7D32", "offline": "#C62828"},
    "dark": { ...the same keys... }
  },
  "shapes": {"focus": "frame", "corners": "notch", "outline_dp": 2, "divider": "solid", "divider_dp": 1,
             "active_tab": "bar"},
  "sizes": {"text_sp": 16, "small_sp": 13, "title_sp": 18, "row_padding_dp": 6},
  "fonts": {"frame": "fonts/PixelifySans.ttf", "text": null}
}
```

- `palettes`: `light` and `dark` (the Light / Dark / Follow phone setting picks one).
- `shapes.focus`: `fill` (a filled bar), `frame` (filled and outlined), `marker` (a ► before the
  row, no fill). `corners`: `square`, `round`, `notch` (pixel-art corners: the corner pixel
  left out). `divider`: `solid`, `dashed`, `none`. `active_tab` (added during 1.2.0): `bar` (the open
  tab is plain bar, the other in the focus color) or `box` (the open one in the focus color); which
  reads as "open" depends on whether the theme's focus color is lighter or darker than its bar.
- `fonts.frame`: titles, bars, menus, lists, settings. `fonts.text`: message text and the message
  box; `null` = the phone's own font (readable for long text). A font must have the Russian
  alphabet, or the phone's font is used for it.
- `sizes`: before the UI size factor.

Built-in Pixel theme: Pixelify Sans (OFL, 79 KB, full Cyrillic; picked 2026-11-07 over Tiny5,
Press Start 2P (too wide for 480 px), VT323, Silkscreen, Micro 5, Jersey 10 (no Cyrillic)).

## Steps

1. **1.2.0 step 1**: the format, `Theme` (parse with fallbacks), `Themer` (applies a theme to every
   screen through the app's activity callbacks: window background, roles by `android:tag`
   "theme:title" / "theme:bar" / "theme:message" ..., list focus and dividers, rows as lists add
   them, pop-up menus), the built-in Pixel theme, Appearance > Theme and UI size. Shared parts
   first: title lines, the connection bar, soft key bar, tab bar, lists, MessageMenu pop-ups.
2. **1.2.0 step 2**: every screen checked and moved onto it (chat, settings, viewers, Under the
   hood, sticker picker, dialogs and their buttons, the message box), colors set in code (calls,
   tabs) taken from the theme.
3. **1.2.0 step 3**: installed themes in `filesDir/themes/<id>/`, the theme picker listing
   built-in + installed, a developer-only "Load a theme from a file" in Under the hood (to test
   the format before the module exists), and "Get themes from the server" shown only when
   `/server_info` capabilities has `themes`.
4. **Later: `module-themes-v1.0.0`** (server): capability `themes` 1, `GET /modules/themes/list`
   -> `{themes: [{id, name, version, size, sha256}]}`, `GET /modules/themes/<id>.zip`; ships
   Classic, Sketchbook and Terminal. The app checks the sha256, refuses paths outside the theme
   folder and sizes over 2 MB, and keeps the zip's folder as-is.
