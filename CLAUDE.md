# TG Flip: Android app (read this first)

## What this project is
A personal, study-oriented project: use Telegram on an Android 10 flip phone with a d-pad, in a region where Telegram is blocked. A Python backend (FastAPI + Telethon) runs on the owner's Windows PC in `../server/`. This folder is the native Android app that will talk to it. The repo-level goals, decisions, and milestones are in `../PROJECT_BRIEF.md`.

## How to work with me (the owner)
- I am a freshman CS student, new to Android, Kotlin, and Git workflows.
- Work in small steps. After each step, tell me what to run and what I should see.
- Explain changes at the level of the overall workflow: what was added, how the pieces fit, and why. No line-by-line walkthroughs unless I ask.
- Prefer showing me the change before making large or multi-file edits. Ask before deleting anything.
- Commit only when I ask. Never commit secrets.
- Do NOT read, print, or modify `../server/.env` or any `*.session` file. They contain secrets.

## Current state
- Backend milestones 1-8 done: chats, messages, send, mark-as-read, live updates, error handling.
- Packaged server: `server/run_server.py` (first-run setup writes `.env`, Telegram login via `client.start()`, then uvicorn from code, all in one event loop) is the entry point of `tgflip-server.exe`. `server/build_server.ps1` builds `dist/tgflip-server-<version>-win64.zip` with PyInstaller (one-folder; `requirements-build.txt`) and refuses to pack `.env`/`*.session`. `.env` and the session live next to the exe. The MTProxy is optional (empty PROXY_HOST = direct). `uvicorn server:app --host 0.0.0.0 --port 9436` still works for development (uvicorn itself defaults to 8000, so pass the port).
- Server resilience: the MTProxy sometimes leaves Telethon connected but answering MTPROTO_CLUSTER_INVALID to everything. `connect_telegram()` retries from a clean disconnect at startup; at runtime, endpoints wrapped in `@recovering` reconnect and retry once, and a watchdog checks every 2 minutes.
- Android project created here (Kotlin, Empty Views Activity; build.gradle.kts has minSdk 24).
- Builds: dev builds (`installDebug`, what Claude installs for testing) are a separate app, `com.q43b.tgflip.debug`, named "TG Flip (dev)", so they sit next to the release. Release builds (`assembleRelease`) are signed with the owner's key via `android/keystore.properties` (git-ignored; template `keystore.properties.example`); without that file they come out unsigned. Never read, print or commit the keystore or its passwords.
- The WebView proof of concept (commit a685194) was replaced by native screens. Do not bring it back.
- Native chat list works on the phone: `ListView` + OkHttp (`ApiClient.kt`), address and token in SharedPreferences (`ServerSettings.kt`). No d-pad double-move in the native list. Each row has a smaller gray preview line ("14:05 Anna: hi…") from `/chats` `last`; time and snippet formatting is shared in `MessageFormat.kt`.
- Live connection: `LiveConnection.kt` keeps the WebSocket open (reconnect with backoff), drives a status bar at the top, and refetches chats on events and after reconnects.
- Chat screen (`ChatActivity.kt`): OK on a chat opens its last 20 messages, new ones arrive live. Opening does not mark as read. A "Load older messages…" row at the top raises the `/messages` limit by 20 and keeps the previous top message highlighted (the row shifts message positions by one: use `messageAt`). For General, the server scans `max(200, limit × 10)` messages.
- Sending: a text box under the messages; OK sends via POST /send. The sent message shows at once (deduplicated by id against the live event).
- Key plan: OK opens chats and sends. In a chat, left soft key (F1) = mark as read, right soft key (F2) = soft key menu, in this order: Quick phrases (inserts a saved phrase into the message box at the cursor), Stickers and Media (placeholders for version 2). Swapped at the owner's request: the phone's keyboard app uses F1 while typing, and the menu is needed more often. (Before that, F2 was mark as read, as in `../server/static/index.html`.) Phrases are edited in Settings (`QuickPhrases.kt`; JSON list in the settings prefs; three example phrases until first edited).
- Soft key bar (`soft_key_bar.xml` + `SoftKeyBar` in `SoftKeys.kt`): left label above F1, right above F2, each shown only while its key does something; the bar hides when both are empty.
- Mark as read: F1 / SOFT_LEFT in the chat screen posts to /read. A soft key label "Mark as read (N)" (left) shows only while the chat has unread messages; F1 does nothing otherwise. The count comes from the list, +1 per live incoming message, and is refetched from /chats (or /topics) on open, reconnect and "read" events.
- Message menu (`MessageMenu.kt`): OK on a message opens a list of `MessageAction`s (label, isAvailable, perform), declared in `ChatActivity.messageActions`. Now: Reply, Go to original (replies only: highlights the replied-to message, loading older pages until found, at most 10 pages), Edit (own text messages), Forward (pick a chat), Delete (asks the server which of "for me" / "for everyone" Telegram allows).
- Version 1 is feature-complete for text. Reactions, stickers and media (in the F2 soft key menu) are planned for version 2; media transfer needs its own design (size, weak phone, slow proxy).
- Calls show as their own italic line, "☎ Missed call" in red (someone's call I didn't take) or "☎ Incoming/Outgoing call · 2:31" in blue (`MessageFormat.call`); the chat list preview uses the same text. Their message menu only offers Delete.
- Compose bar above the box: "Replying to" or "Editing"; Back cancels it. Replies show a "↪ quote" first line when the original is loaded; edited messages show "(edited)" after the time.
- Settings (`SettingsActivity.kt`): d-pad Right in the chat list opens it (slides in from the left, like the official app's side menu); Left or Back closes it. A bottom tab bar (`tab_bar.xml`, `TabBar.kt`) shows "Chats | Settings" with the active one highlighted. Rows: Server (IP, port, token) via `ServerSettingsDialog.kt`, the same dialog the chat list shows on first launch and connection errors.
- Settings also has Theme (Follow phone / Light / Dark; `ThemeSetting.kt`, applied at start by `TGFlipApp.kt`).
- Settings shows a small gray "TG Flip <versionName>" line above the tab bar (`BuildConfig.VERSION_NAME`; dev builds end in "-dev").
- Versions: the app's is `versionName` in `build.gradle.kts`, the server's is `VERSION` in `server.py` (printed at startup; `build_server.ps1` names the zip after it). The app sends `x-app-version` when it opens `/ws`, and the server prints "App connected from <ip>: TG Flip <version>" once per address and version (apps before 0.7.1 show as older).
- Connection modes (`ConnectionMode.kt`): Local network (http, LAN IP), VPN (http inside Tailscale or another VPN), Public internet (https only). Any IP or name entered for Local or VPN works without a rebuild. Each mode has its own address, the token is shared, `ServerSettings.getAddress()` returns the active mode's. Settings: "Connection" row switches mode, one row per mode's server. The connection bar shows the mode ("Connected · VPN"). VPN and Public are not tested yet.
- A mode without an address: screens still open from the saved copy, the bar says "No server · <mode>", and `ApiClient` (built with a null address) fails every request with `NoServerException`. The server dialog in the chat list can always be closed; it opens by itself only on first launch (no token).
- Offline cache (`LocalCache.kt`): the last chats, topics and messages (newest 100 per chat/topic) are saved as JSON files in `noBackupFilesDir` (kept out of phone backups). Screens show the saved copy first, then replace it with fresh data; offline they say "Offline: showing saved copy".
- Folders (the app's own, not Telegram's; owner's choice): `Folders.kt` keeps them as JSON in the settings prefs, with chat ids and names. Row 0 of the chat list is "📁 Folders…" (inside a folder "📁 Name · Folders…"; positions shift by one, use `chatAt`). Its menu lists All chats, the folders with their unread counts, and "⚙ Folder settings…": Create new folder, and Edit a folder (pick one) → Rename, Add chats, Remove chats (checklists; Add has a "Load more chats" button that loads another page and reopens with the ticks kept), "Hide its chats from All chats" (archive alternative), "Count its unread in the total", Delete. Each refresh fetches the chats of all folders with one `GET /chats?ids=…` (inside a folder its chats come from that); the folders row shows the total unread of the counting folders, each chat counted once. Back returns to all chats. Offline, a folder shows those of the saved chats that are in it.
- Older chats: a "Show more chats…" row at the end of the list raises the `/chats` limit by 20 each time (live refreshes keep the bigger limit).
- Forum groups (groups split into topics): marked "›" in the list; OK opens `TopicsActivity.kt`, OK on a topic opens `ChatActivity` for that topic. In the list, F2 on a forum opens its General topic directly (soft key label "Open General").
- A forum group's own unread counter is stale in Telegram (it never clears when topics are read one by one), so `/chats` reports forums as the sum of their topics' unread counts, cached per group until activity there or 60 s.
- Forum caveats to watch: General (topic id 1) is loaded by scanning the group's last 200 messages for ones with no topic mark; topic reads on other devices may not send a live "read" event.

## Target device
- Android 10 flip phone, small screen, d-pad. Keys seen in its browser: ArrowUp/Down/Left/Right, Enter (OK), soft keys F1 (keyCode 112) and F2 (113). Back is handled by the system.
- Known issue in the phone's browser: d-pad Up/Down moves focus two items at once. The reason to build a native app is to handle key events directly (onKeyDown) and fix this.
- 112/113 are the browser's (JavaScript) codes for F1/F2. Native Android numbers keys differently: F1/F2 are `KEYCODE_F1`/`KEYCODE_F2` (131/132), but some phones send soft keys as `KEYCODE_SOFT_LEFT`/`KEYCODE_SOFT_RIGHT` (1/2) and the browser just labels them F1/F2. Accept both pairs.
- Confirmed from the phone's key layout (`/system/usr/keylayout/matrix_keypad.kl`, a Kyocera): OK = `DPAD_CENTER`, soft keys = `F1`/`F2`, plus `BACK`, `CALL`, digits, `STAR`, `POUND`. Helpers live in `SoftKeys.kt`.
- On a screen with a text box, keys go to the keyboard app (IME) first, and the phone's IME swallows OK (F2 gets through; F1 is used by the IME while typing). So the chat screen's message list is `OkListView`, which takes OK in `onKeyPreIme` (runs before the IME). Activity `dispatchKeyEvent` runs after the IME and never sees OK there. The phone is reachable over USB with adb for reading logs. It hides debug log lines (`log.tag=I`), so log with `Log.i`.
- Opening/closing the flip switches the keyboard config; activities declare `configChanges="keyboard|keyboardHidden|navigation"` so screens are not rebuilt.

## Server API (plain HTTP on the home network; the PC has a fixed LAN IP; port 9436, "q43b" in leet; was 8000 until 0.4.0)
- Auth: every HTTP request needs the header `x-token: <API_TOKEN>`. The token is never hardcoded in the repo.
- `GET /chats?limit=20` -> `[{id, name, unread, forum, last}]` (`forum`: group split into topics; `last`: `{text, out, date, sender, kind}` or null, the dialog's newest message, in a forum of any topic; `kind` text/media/event; `sender` only for others' messages in groups; comes with the dialog list, no extra requests)
- `GET /chats?ids=1,2,3` -> the same objects for exactly those chats (an app folder), newest activity first; built from one GetPeerDialogs request like iter_dialogs builds its own (`dialogs_by_ids`). Chats the account can't resolve are skipped.
- `GET /topics/{chat_id}?limit=50` -> `[{id, title, unread}]` for a forum group; General is id 1
- `GET /messages/{chat_id}?limit=20[&topic_id=N]` -> `[{id, text, out, date, reply_to_id}]`, oldest first (`reply_to_id` null if not a reply)
- `POST /send` with JSON `{chat_id, text, topic_id?, reply_to_id?}` -> `{id}`
- `POST /read/{chat_id}[?topic_id=N]` -> `{ok: true}` (this sends a read receipt, so it must stay a deliberate user action)
- Without `topic_id`, every endpoint works on the whole chat, as before.
- Messages (and live message events) also carry `sender`: the writer's first name (or a channel's title) for other people's messages in groups, null for own messages, private chats and channel posts (`sender_name`; comes with the messages, live events use `event.get_sender()`). The app shows it in bold: "14:05 **Anna:** hi".
- Messages also carry `edited` (true = show "(edited)"; from Telegram's edit_date, unless edit_hide) and `call` (null, or `{video, reason, duration}` for call records: reason missed/busy/hangup/disconnect/null, duration seconds or null). Calls are service messages; Telethon's NewMessage skips those, so `on_call_record` (events.Raw UpdateNewMessage) broadcasts them live. `/chats` `last` has `kind: "call"` plus `call` for them.
- `POST /edit` `{chat_id, message_id, text}` -> `{ok}` (400 when too old: Telegram's edit_time_limit)
- `GET /delete_options/{chat_id}/{message_id}` -> `{for_me, for_everyone}`, worked out from chat type, message age and owner, admin rights, and Telegram's config (revoke_pm_time_limit, revoke_pm_inbox, revoke_time_limit). Supergroups/channels have no "for me".
- `POST /delete` `{chat_id, message_id, revoke}` -> `{ok}` (`revoke` = for everyone)
- `POST /forward` `{from_chat_id, message_id, to_chat_id}` -> `{id}` (into a forum: lands in General)
- `WebSocket /ws`: the first text message must be the token (the server closes the connection after 5 seconds without it). The server then pushes JSON:
  - `{type: "message", id, chat_id, topic_id, reply_to_id, text, out, date}` (`topic_id` is null in ordinary chats and in General)
  - `{type: "read", chat_id}` (read state changed in another client; refetch `/chats`)
  - `{type: "edited", id, chat_id, text, edited}`
  - `{type: "deleted", chat_id, ids}` (`chat_id` is null outside channels/supergroups; there ids are unique per account)
  - `{type: "call_ringing", call_id, chat_id, name, video}` (someone is calling now; from UpdatePhoneCall/PhoneCallRequested) and `{type: "call_ended", call_id}` (it stopped ringing: accepted, answered elsewhere or discarded). The app's `IncomingCallAlert` shows a dialog (no vibration: the owner doesn't want it) on the open screen (not Settings, which has no live connection) and closes it on call_ended. Only while the app is open.
- After a WebSocket reconnect, refetch current state (chats and the open chat's messages).

## Constraints and decisions
- The app and server share only a base URL and a token. How the phone reaches the server is a separate, swappable layer. Keep the protocol plain HTTP + WebSocket.
- The home ISP uses CGNAT and the PC has no real public IPv6 (only Teredo), so there is no direct remote access. Remote access is shelved (2026-11-05), all options ruled out:
  - Tailscale/TailSocks: the provider's DPI disrupts it; the PC only gets through thanks to Zapret, the phone never went online (TailSocks's built-in DPI bypass did not help), and an earlier try failed on mobile data.
  - No public IPv4 from the ISP; Cloudflare is blocked; a foreign VPS risks its IP being blocked; Russian VPSs may not be used for bypassing by law.
  - The connection modes (VPN, Public) stay in the app for later.
- Plain HTTP: `network_security_config.xml` allows it in general (it is fixed at build time and cannot follow Settings). The app limits it itself: it only connects to the addresses entered in Settings; Local network and VPN modes use http, Public internet always https (`ConnectionMode.scheme`).
- Message text must never be rendered as HTML.
- Keep the app light: it runs on a weak phone.
