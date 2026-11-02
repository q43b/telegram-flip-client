# Project Brief: Telegram Client for a Flip Phone

_Keep this file updated. Re-upload it to the project knowledge after each milestone._

## Goal

Use Telegram on a retro-style Android 10 flip phone with a d-pad, in a region where Telegram is blocked. Also the main learning project for a freshman CS student: Git/GitHub, Python, async programming, APIs, auth, security, and UI under constraints.

## About the learner

- Freshman CS student; university program is math-heavy and outdated on programming
- Comfortable with math; new to repos, GitHub, and vibe coding
- Wants real, demonstrable experience for the job market

## Working rules

- Work in small milestones; run and test each before moving on
- Explain every piece of generated code; the learner should be able to explain it in an interview
- Commit after every working step with a clear message
- Never commit secrets: `api_id`, `api_hash`, `.env`, `*.session`
- On errors: read the message first, then share the code, the error, and the expected behavior

## Architecture

```
Flip phone (browser) <--HTTPS/WebSocket--> PC (FastAPI + Telethon) <--MTProto--> Telegram
```

- **Backend:** Python, Telethon (MTProto), FastAPI with WebSockets, optional SQLite cache
- **Frontend (phase 1):** plain HTML/CSS/JS served by the backend, opened in the phone's browser; d-pad maps to arrow keys + Enter
- **Frontend (phase 2, optional):** native Android app (Kotlin) wrapping or replacing the web UI
- **Hosting:** the user's PC for now; an old laptop running 24/7 later (not yet)

## Key decisions and risks

- The PC itself must reach Telegram: may need a proxy (MTProxy/SOCKS5 supported by Telethon) or VPN. **Test this first.**
- Phone-to-PC: same Wi-Fi/local IP at first; remote access (e.g. Tailscale/WireGuard) later
- The Telethon session file gives full account access: protect it, add auth on the backend, use HTTPS before any exposure beyond the home network
- Use the learner's own `api_id`/`api_hash` from my.telegram.org

## Milestones

- [x] 1. Setup: Python, Git, VS Code; GitHub repo; `.gitignore` before anything else
- [x] 2. Telethon script: log in, list last 10 chats, send a message to Saved Messages
- [x] 3. Live receive: event handler printing incoming messages
- [x] 4. FastAPI wrapper: `GET /chats`, `GET /messages/{chat}`, `POST /send`
- [x] 5. Minimal web UI: chat list, message view, input (test on PC browser)
- [x] 6. D-pad navigation: focus handling, large text, small-screen layout (test on phone) - mostly done (d-pad double jump deferred)
- [ ] 7. WebSocket push for real-time messages; simple auth
- [ ] 8. Polish: unread counts, error handling, README with diagram and screenshots
- [ ] 9. Later: old-laptop server, remote access, native Android app

## Current status



## Notes / decisions log

_(Add dated entries here as the project evolves.)_
Note 1:
- Using flowseals tg-ws-proxy for backends connection to tg servers
Note 2, 3:
- Known issue: on the flip phone's built-in browser, d-pad Up/Down moves focus two items at once (key tester shows one event per press; happens even with the page's key handler removed, so likely browser-native). Deferred to the native Android app phase, where key events can be handled directly.
- Key codes on the phone: arrows = ArrowUp/Down/Left/Right, OK = Enter, F1 (112) and F2 (113) = soft keys, back key handled by the browser.
