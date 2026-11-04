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
- Android project created here (Kotlin, Empty Views Activity; build.gradle.kts has minSdk 24).
- The WebView proof of concept (commit a685194) was replaced by native screens. Do not bring it back.
- Native chat list works on the phone: `ListView` + OkHttp (`ApiClient.kt`), address and token in SharedPreferences (`ServerSettings.kt`). No d-pad double-move in the native list.
- Live connection: `LiveConnection.kt` keeps the WebSocket open (reconnect with backoff), drives a status bar at the top, and refetches chats on events and after reconnects.
- Chat screen (`ChatActivity.kt`): OK on a chat opens its last 20 messages, new ones arrive live. Opening does not mark as read.
- Sending: a text box under the messages; OK sends via POST /send. The sent message shows at once (deduplicated by id against the live event).
- Key plan: OK opens chats and sends. Right soft key (F2) = mark as read, as in `../server/static/index.html`. Left soft key (F1) is reserved for sticker/media sending, much later.
- Mark as read: F2 / SOFT_RIGHT in the chat screen posts to /read. A soft key label "Mark as read (N)" shows only while the chat has unread messages; F2 does nothing otherwise. The count comes from the list, +1 per live incoming message, and is refetched from /chats on open, reconnect and "read" events.

## Target device
- Android 10 flip phone, small screen, d-pad. Keys seen in its browser: ArrowUp/Down/Left/Right, Enter (OK), soft keys F1 (keyCode 112) and F2 (113). Back is handled by the system.
- Known issue in the phone's browser: d-pad Up/Down moves focus two items at once. The reason to build a native app is to handle key events directly (onKeyDown) and fix this.
- 112/113 are the browser's (JavaScript) codes for F1/F2. Native Android numbers keys differently: F1/F2 are `KEYCODE_F1`/`KEYCODE_F2` (131/132), but some phones send soft keys as `KEYCODE_SOFT_LEFT`/`KEYCODE_SOFT_RIGHT` (1/2) and the browser just labels them F1/F2. Accept both pairs.

## Server API (plain HTTP on the home network; the PC has a fixed LAN IP; port 8000)
- Auth: every HTTP request needs the header `x-token: <API_TOKEN>`. The token is never hardcoded in the repo.
- `GET /chats?limit=20` -> `[{id, name, unread}]`
- `GET /messages/{chat_id}?limit=20` -> `[{id, text, out, date}]`, oldest first
- `POST /send` with JSON `{chat_id, text}` -> `{id}`
- `POST /read/{chat_id}` -> `{ok: true}` (this sends a read receipt, so it must stay a deliberate user action)
- `WebSocket /ws`: the first text message must be the token (the server closes the connection after 5 seconds without it). The server then pushes JSON:
  - `{type: "message", id, chat_id, text, out, date}`
  - `{type: "read", chat_id}` (read state changed in another client; refetch `/chats`)
- After a WebSocket reconnect, refetch current state (chats and the open chat's messages).

## Constraints and decisions
- The app and server share only a base URL and a token. How the phone reaches the server is a separate, swappable layer. Keep the protocol plain HTTP + WebSocket.
- Home Wi-Fi only for now (the home ISP uses CGNAT, so no direct remote access).
- Plain HTTP means Android's cleartext-traffic block must be addressed explicitly (network security config), limited to the home network.
- Message text must never be rendered as HTML.
- Keep the app light: it runs on a weak phone.
