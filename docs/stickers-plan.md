# Plan: stickers and custom emoji

Status: designed 2026-11-06 at v0.9.0-beta; step 1 built in 0.10.0-beta, step 2 in 0.10.1-beta, sending in 0.11.0-beta (the rest of step 3 is split into 0.11.1-0.11.3, see below). Roadmap: 0.10.0 lays the
foundation. To pick it up, ask: "let's continue the sticker plan from docs/stickers-plan.md".

## What they are

- A sticker is a file in a Telegram document: static (WebP image), animated (TGS: gzipped
  Lottie JSON, vector animation) or video (WebM, VP9 with transparency).
- A custom emoji is a small sticker of the same three kinds, used inside text or as a
  reaction. Every one names the plain emoji it stands for ("alt"); message text already
  carries that alt character, and since 0.8.1 reactions show it too.
- Every sticker, animated and video ones included, also has a small still preview ("thumb")
  that Telegram makes. That's what lets the still mode skip animations entirely.

## The setting: three modes (Settings > Media and Cache > "Stickers and custom emoji")

1. **Data saver**: nothing downloads, and already downloaded files aren't used either.
   Stickers show as text: "🏷 Sticker 😂" (the sticker's emoji). Custom emoji show as their
   plain emoji, as today.
2. **Still pictures** (default): stickers and custom emoji show as still pictures. Static
   stickers: the WebP itself, shrunk. Animated and video ones: their still preview.
3. **Animated** ("the way Durov intended"): stickers and custom emoji play.

A second switch: **Sticker packs: Download any / Only chosen packs**. Default "Download
any": like official clients, anything downloads when it's about to be shown. "Only chosen"
limits it to packs ticked in a pack list (stickers from other packs show as in data saver);
the pack list arrives with sticker sending, which needs it anyway.

## Where they show (owner's choices)

- **Stickers: a small picture right in the chat** (96 dp on the right of the message's row,
  the text wrapping left of it; built in 0.10.0), like official apps. A deliberate exception to the "no pictures inside chats" rule. In data saver
  the row shows the text line instead.
- OK on a sticker: the message menu gets "View sticker", full size (reuses the photo
  viewer idea).
- **Custom emoji**: text-sized pictures inside message text and reaction lines (ImageSpan
  over the alt character), in modes 2 and 3. Mode 3 animates them only where it is cheap
  (see "Animated mode").

## Server

- Messages carry `sticker`: `{id, emoji, set_id, kind: "static" | "animated" | "video"}` or
  null (`sticker_of`); `video_of` already leaves stickers out. Chat list preview kind
  "sticker" with the emoji.
- Messages also carry `custom_emoji`: `[{offset, length, id}]` from the text's
  MessageEntityCustomEmoji entities (offsets in UTF-16 units, like Kotlin strings), or [].
- A document registry: every sticker/custom emoji document the server sees (in messages,
  from GetCustomEmojiDocuments, from sticker sets) is remembered by id, so `/sticker/{id}`
  can download it later (downloads need the document's file reference). Custom emoji can
  always be fetched again by id alone (GetCustomEmojiDocuments); message stickers through
  `?chat_id=&message_id=` as a fallback after a server restart.
- `GET /sticker/{id}?variant=still|animated&size=N` -> the picture:
  - still: static WebP shrunk to `size` (PNG/WebP via Pillow); animated/video: the
    document's thumb, shrunk.
  - animated: see below.
  - cached in `sticker_cache/<id>_<variant>_<size>` through `cache_path` (per-account later,
    see docs/multi-account-plan.md; stickers and custom emoji are the same for every account,
    but keep the helper).
- Pack list (with sticker sending): `GET /sticker_sets` (installed packs: GetAllStickers,
  custom emoji: GetEmojiStickers), `GET /sticker_set/{id}` (its stickers).

## App

- Message rows become a small custom adapter (like `ChatRowAdapter`): the text, plus an
  optional ImageView for a sticker. The list stays a WrapListView/OkListView.
- A `StickerLoader` like `PhotoLoader`: memory LRU, disk (`media/stickers/`, so the Cache
  limits apply), then `/sticker`, at most 2 downloads at a time, redraws the row when ready.
  Asked for nothing in data saver.
- Custom emoji: same loader at text size; spans replaced when loaded, row redrawn.
- Mode and pack setting in `SettingsGroupActivity` (Media and Cache rows).

## Animated mode: an optional server add-on (owner's design, 2026-11-06, for 0.12.0)

> Since 0.15.0 this becomes a **server module** (`docs/modules-plan.md`): the `animated` module,
> its own release `module-animated-v<version>`, installed with maintenance mode's "x) Modules"
> instead of the add-on download and `addons/` folder described below. It provides the capability
> `animated_stickers` 1 and hooks into `/sticker`. The rest of the design (formats, sizes, the
> app side) still holds.

"I don't need it, I don't install it": animation is an **add-on to the server**, not part
of it. Weaker phones, or owners who don't care, never download it; the app works the same,
with still pictures. The app learns from the server whether the add-on is there.

- **What it is:** PyAV (ffmpeg's libraries, with libvpx-vp9, which keeps video stickers'
  transparency) for video stickers and rlottie-python for Lottie (TGS) ones. Checked: both
  have Windows wheels for the server's Python, about 70 MB unpacked (~28 MB zipped), almost
  all of it ffmpeg.
- **Packaged server:** a separate release file, `tgflip-animated-addon-<version>-win64.zip`
  (+ its `.sha256`), unpacked into `addons/animated/` next to the exe; the server adds that
  folder to its import path at startup when it's there. Installed:
  - at first-run setup: "Install the optional animated stickers add-on? (~28 MB) [y/N]";
  - or any time with `tgflip-server.exe --maintenancemode`: a small menu to install, update
    or remove the add-on (then start the server or quit).
  It downloads the add-on of the server's own version from the GitHub release and checks it
  against the published SHA-256; with no internet, a downloaded add-on zip placed next to the
  exe is used instead. Removing deletes `addons/animated/`.
- **Development server (venv):** `pip install -r server/requirements-animated.txt`.
- **Server side:** `GET /server_info` -> `{version, animated}`; `/sticker/{id}?variant=animated`
  turns animated and video stickers into **animated WebP** (rlottie for TGS, PyAV decoding
  with libvpx-vp9 for WebM, Pillow writing the WebP), cached as `<id>_anim_<size>.webp`.
  Without the add-on, or for static stickers, it answers with the still picture.
- **App side:** Settings > Stickers "Animated" becomes choosable when the server says it has
  the add-on (otherwise it says "needs the server's animated stickers add-on"). Played with
  Android's own `AnimatedImageDrawable` (Android 9+; older phones stay still): only rows on
  screen play (stopped when a row scrolls away), in the chat, in the sticker grid and in the
  viewer. Custom emoji stay still in text; animating them in reaction lines is a later step.

### Measured with module animated 1.0.0 (2026-11-07), and what comes later

- At 144 px (the chat's 96dp on the Kyocera), 20 fps, WebP quality 70: video stickers 20-185 KB
  (often smaller than the WebM), made in 0.1-1.1 s; Lottie stickers 185-323 KB, made in about
  0.5 s. Transparency is kept for both. The WebP quality setting barely changes the size; the
  number of frames does (10 fps halves it, but looks choppy). WebP method 6 is far too slow.
- **Done in module 1.1.0 (with server 0.16.3-beta): heavy stickers are slimmed on the server**: repeated
  frames merged, then 15/12/10 fps until under 150 KB at 144 px (scaled by area for other sizes);
  chat Lotties 185/323 -> 94/165 KB. Module 1.1.1: 1.1.0 used Pillow's ImageStat, which the packaged
  servers don't carry, so it only worked from source; the averages now come from histogram(). The original plan (owner, 2026-11-07):
  Ideas to try: a lower frame rate only for Lotties over a size budget, dropping frames that
  barely differ from the one before (longer durations instead), a per-sticker size budget.
  A new module version only; the server and the hook stay as they are.
- **0.16.0-beta (done):** animated stickers play in chats. **0.16.1-beta (done; owner, 2026-11-07):**
  in the sticker picker's grids only the highlighted sticker plays (a whole playing pack is too heavy
  for the phone); the others stay still. **0.16.2-beta (done):** the viewer plays them, at 384 px
  (still stickers 512): 384 px made 595-985 KB Lotties and 71-593 KB videos in 0.8-3.5 s, 512 px
  830-1356 KB and 110-833 KB in 1.3-5.9 s.

## Steps

1. **0.10.0 foundation**: server `sticker` field, document registry, `/sticker` (still
   variant) with cache; app setting (all three modes listed; Animated behaves as Still until
   step 4, marked "coming"), pack switch (Only chosen marked "coming"), sticker rows with
   small pictures, "View sticker", data saver as text.
2. **0.10.1**: custom emoji inside text and reactions as text-sized still pictures.
3. **0.11.x stickers, step by step** (owner's split, 2026-11-06):
   - 0.11.0 sending stickers: F2 > Stickers, Recent, Favorites, installed packs, a grid. Done.
   - 0.11.1 from a sticker in a chat (message menu): view its pack, and add the pack to my packs
     (F1 "Add pack" while it isn't mine). Done.
   - 0.11.2 in a pack's grid, F2 on a sticker: a menu to add it to / remove it from Favorites
     (Telegram's, at most 5 on a free account), and my own description of it, shown in data
     saver mode. Built.
   - 0.11.3 "local favourites": the app's own favourites list (no limit), with add/remove in the
     same F2 menu. Built: kept on the server in local_favorites.json (with each sticker's pack, to
     reload it after a restart).
   - Later: per-pack "chosen" ticks (makes "Only chosen packs" work), "download whole pack".
4. **0.12.0 animated mode as an optional server add-on** (see above): the add-on build and
   install (setup, --maintenancemode), /server_info, animated WebP conversion, the app's
   Animated mode for stickers. **0.12.1**: animated custom emoji in reaction lines.
