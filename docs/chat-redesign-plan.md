# Chat rows with media, and the sticker picker for touch phones (plan, 2026-11-08)

Nothing here is built. Two separate pieces, one decision list at the end.

## Part 1: separating media from text in the chat

### How a message looks now

One `ListView` row per message (`message_row.xml`: a text and, for stickers only, a picture on
the right). Everything about a message is one text built by `ChatActivity.formatMessage`:

    14:05 ✓✓ Anna: 🖼 Photo: here is the whiteboard
    14:06 You: 🎬 Video 0:45 · 12 MB: look at this
    14:07 Anna: 📄 File: report.docx · 1.2 MB     (since 1.5.0 the file part is on its own line)
    14:08 Anna: 🎤 Voice message 0:12

Problems: the media label sits in the same line as time, sender and caption; OK on any message
opens one menu that mixes message actions (Reply, Edit, Delete, React, Forward, Request reads)
with media actions (View photo, Play video, Download file, View sticker, View sticker pack).
The menu keeps growing, so its redesign belongs here too.

### Three directions (can be combined)

**A. A media block inside the row.** The header line stays ("14:05 ✓✓ Anna:"), the media gets
its own lines below, set apart by a theme colour (a new theme role, `media`, in `Themer`: themes
are data, so they can restyle it), a marker character at the start of each line and the caption
in normal text under it:

    14:05 ✓✓ Anna:
    ▌📄 report.docx
    ▌1.2 MB · .docx
    Lab report draft, comments welcome

Cheapest: only `formatMessage` and one theme role. Doesn't change how keys work.

**B. Media as its own row.** A message with media becomes two rows in the list: the message row
(header + caption) and a media row under it (icon, name or label, size). The d-pad then lands
on the media row separately: OK there does the media's own action at once (photo opens, video
plays, voice plays, file downloads, sticker views), F2 on it offers the media extras (Download
photo, View sticker pack...). OK on the message row opens a menu with message actions only.
Also frees the menu: media actions leave it, which is most of the redesign of the menu.
More work: the adapter, positions (`messageAt` and every place that uses list positions: scroll,
"Go to original", highlight after sending, saved selection), reactions and the "read" mark
stay on the message row.

**C. Stickers and photos inline later.** Stickers already show as a picture next to the text.
With B, a sticker's media row can simply be the picture (the text row then holds only the
header). Photos stay on demand (owner's retro direction, no inline photos).

My suggestion: B, in steps: (1) rows split in two with the media row styled by a theme role
(A's look), media actions still reachable from both rows; (2) OK on the media row does the
primary action, the message menu loses the media entries; (3) the F2 menu on the media row.

### Questions for the owner

1. A (restyle inside the row, small) or B (separate media row, bigger, and it solves the menu
   too)?
2. With B: should the message menu still list the media actions as well (safe for the first
   version), or move them out at once?
3. A theme colour for media rows (new `media` role, Pixel/Terminal/Classic/Sketchbook get a
   value; missing in installed themes = the text colour)?

## Part 2: "always animate stickers in the sticker tab"

Today (since 0.16.1) only the highlighted sticker of a pack plays, because a whole playing
pack is heavy on the weak phone, and because on a d-pad phone there is always one highlight.
On a touchscreen phone nothing is "highlighted" while looking at the pack, so people see all
stickers still. The setting is for them.

Settings > Media and Cache gets, under "Stickers: ...", a row **"Sticker pack screen: Only the
highlighted plays / All visible play"** (`sticker_picker_all`, default: only the highlighted;
shown only while Stickers is Animated, as the setting needs it; `StickerSetting.pickerAll`).

How the picker would work with "All visible play":
- Cells use a `StickerLoader(plays = true)` for every position, not only the selected one (the
  existing `mover` for all cells), paused in `onStop`, resumed in `onStart`, as now.
- Only visible cells play: the grid already recycles views; a cell that scrolls away drops its
  drawable (`getView` is called again) and the loader's keep-limit of 12 animations stays the
  upper bound of work; the limit is raised to the number of visible cells + a row (a 104 dp
  cell on a 480 px screen is about 12-16 cells; a smartphone shows more, so the limit follows the
  grid's visible count).
- Server load: opening a pack asks the server for every sticker's animation at 144 px (the
  animated module converts at most 2 at once, 0.1-1 s each). A pack of 60 starts as a queue:
  the visible ones first. `StickerLoader` already has at most 2 downloads at a time, in
  request order, so asking in visible order is enough; the cells show their still pictures until
  their animation arrives.
- Data: an animated sticker is 20-185 KB (slimmed Lotties 94-165 KB); a screen of 15 is about
  1.5 MB the first time, then cached on the phone.

Not decided: whether the same should apply to the chat itself (stickers in messages already
play when the chat is open, every one that is on screen, so nothing to do), and to the
"Recent"/"Favorites" screens (yes, the same grid).

Estimate: small (one setting, one loader change in `StickerPickerActivity`, the cap). Can be
1.6.1 or 1.7.0, together with or before the media rows.

## Decisions so far

- 2026-11-08 (owner): both parts go into **1.7.0**, together.
- Still open: A or B for the media rows (the owner asked for a visual first; mockups shown in the
  conversation: today / A media block inside the row / B media as its own selectable row), and
  whether the message menu keeps the media actions in the first version.
- 2026-11-08 (owner): direction **B** (media as its own row). Requirement: the two rows must still
  clearly belong to one message. Planned cues (`docs/chat-redesign-mockups.html`, "B with the pieces
  tied together"): no divider between the message row and its media row (a divider only after the
  pair); the media row is indented, starts with a connector glyph and carries the accent bar of its
  theme role; the focused row gets the full focus highlight with its frame, the other row of the same
  message only a faint tint of the focus colour (owner: both in the same colour made it hard to
  see which one OK acts on); in themes this is the focus colour at about 15% alpha, a derived
  value, not a new key; reactions, the read mark and "(edited)" stay on the message row.
  Default for the open points (change if the owner says otherwise): the message menu keeps the media
  actions in the first version (they leave it in a later step); a new theme role `media` for the
  bar and text of media rows, missing in installed themes = the normal text colour.
