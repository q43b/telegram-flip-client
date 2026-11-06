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

## Animated mode (the expensive one: its own step)

- Recommended: the server turns animated and video stickers into **animated WebP**, and the
  phone plays them with Android's own `AnimatedImageDrawable` (Android 9+; on 7-8 it falls
  back to still). No big library in the app.
  - TGS (Lottie) on the server: `rlottie-python` (small wheel with Windows builds).
  - WebM video stickers: need a video decoder on the server: PyAV (bundles ffmpeg libraries,
    roughly +30 MB to the server zip) or ffmpeg. Decide when we get there; until then video
    stickers stay still in mode 3.
- Weak phone: animate stickers in the chat only while visible, at most a few at once;
  custom emoji inside text stay still even in mode 3 unless that proves cheap enough.
- Alternative kept in mind: Lottie on the phone (lottie-android, about +0.5 MB, heavy CPU).

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
4. **0.12.0 animated mode** (moved after sending at the owner's request; the owner will
   choose the approach then): e.g. server conversion to animated WebP (TGS with
   rlottie-python, a 0.4 MB wheel; video stickers with PyAV, 27 MB), phone playback.
