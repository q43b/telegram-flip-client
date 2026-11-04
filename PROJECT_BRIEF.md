# Project Brief: Telegram Client for a Flip Phone

_Keep this file short and current. Update it after each milestone, then commit and sync._

## Goal

Use Telegram on a retro Android 10 flip phone with a d-pad, in a region where Telegram is blocked and mobile internet is restricted. Also the learner's main study project: Git/GitHub, Python, async programming, APIs, auth, security, UI under constraints.

## Learner

- Freshman CS student; university program is math-heavy and weak on programming
- Strong in math; new to repos, GitHub, and vibe coding
- Wants real, demonstrable experience for the job market

## Working rules

- Small milestones; run and test each before moving on
- Explain every generated piece; the learner must be able to explain it in an interview
- Commit after every working step with a clear message
- Never commit secrets: `API_ID`, `API_HASH`, `PROXY_SECRET`, `API_TOKEN`, `.env`, `*.session`
- On errors: read the message first, then share the code, the error, and the expected behavior
- Check for `(venv)` in the PowerShell prompt before running pip

## Architecture

```
Flip phone (browser, later native app)
        |  HTTP + WebSocket, base URL + token   <- "connection layer", swappable
        v
Home PC: FastAPI + Telethon
        |  MTProto via local proxy (tg-ws-proxy, 127.0.0.1:1443)
        v
Telegram
```

- **Backend:** Python 3.13, Telethon, FastAPI + uvicorn, python-socks. Files: `server.py`, `list_chats.py`, `listen.py`
- **Frontend (now):** plain HTML/CSS/JS in `static/`, served by the backend
- **Frontend (later):** native Android app (Kotlin), where key events can be handled directly
- **Hosting:** the learner's Windows 11 PC; an old laptop running 24/7 later

## Connection layer (phone to PC)

- Principle: app and server know only a **base URL** and a **token**. How the URL is reached is a separate layer that can be swapped without touching app logic. Keep the protocol plain HTTP + one WebSocket.
- PC to Telegram is solved by tg-ws-proxy and is out of scope here.
- Now: home Wi-Fi only (`uvicorn server:app --host 0.0.0.0`, plain HTTP, trusted network only).
- Required: use from mobile data, with **no rented relay** (foreign IPs risk being unreachable under mobile allowlists; a Russian server rented for bypass is reportedly illegal).
- Candidate: direct connection to the home PC. Needs a public IP (not CGNAT), a mobile network that lets it through, and hardening first (HTTPS, rate limiting, strong auth). Test with the empty-folder `http.server` experiment before building.
- Fallback candidate: self-hosted obfuscated VPN on the home PC (unverified).
- Known limit: during mobile allowlist periods, nothing custom will connect.

## Phone browser constraints

- User agent starts with "Mozilla/5.0"; no optional chaining (`?.`)
- Works: arrow functions, async/await, object spread, fetch, localStorage
- Write new JS in conservative style (`var`, `function`); test on `static/debug.html` first
- Keys: arrows = ArrowUp/Down/Left/Right, OK = Enter, soft keys F1 (112) and F2 (113); back is handled by the browser (F1 also opens the browser menu)
- Known issue: d-pad Up/Down moves focus two items at once in the phone browser (key tester shows one event; happens with our handler removed, so likely browser-native). Deferred to the native app.

## Milestones

- [x] 1. Setup: Python, Git, VS Code, GitHub repo, `.gitignore` first
- [x] 2. Telethon login, list chats, message to Saved Messages
- [x] 3. Live receive: event handler printing messages
- [x] 4. FastAPI wrapper: `GET /chats`, `GET /messages/{chat}`, `POST /send`, token auth
- [x] 5. Minimal web UI: chat list, messages, input
- [x] 6. Phone UI: reachable on LAN, d-pad focus navigation (double-jump deferred)
- [x] 7. WebSocket push for new messages, with token auth
- [ ] 8. Polish: unread counts, error handling, README with diagram and screenshots (unread counts done)
- [ ] 9. Remote access from mobile data: experiment, then HTTPS and hardening
- [ ] 10. Native Android app
- [ ] 11. Move server to the old laptop (24/7)

## Current status

Milestone 6 done; chat list, reading, and sending work on the flip phone over home Wi-Fi. Next: milestone 7.

## Decisions log

- 2026-11-02: Use tg-ws-proxy (flowseal) as the PC's route to Telegram
- 2026-11-02: No rented servers for the phone-to-PC link; connection layer kept swappable
- 2026-11-02: Defer the d-pad double jump to the native app phase

## Notes / decisions log

_(Add dated entries here as the project evolves.)_
Note 1:
- Using flowseals tg-ws-proxy for backends connection to tg servers
Note 2, 3:
- Known issue: on the flip phone's built-in browser, d-pad Up/Down moves focus two items at once (key tester shows one event per press; happens even with the page's key handler removed, so likely browser-native). Deferred to the native Android app phase, where key events can be handled directly.
- Key codes on the phone: arrows = ArrowUp/Down/Left/Right, OK = Enter, F1 (112) and F2 (113) = soft keys, back key handled by the browser.
Note 4:
Home ISP uses CGNAT (confirmed), so no direct inbound access. Remote access deferred; home Wi-Fi only for now. Options for later: ask ISP for a public IP, check IPv6, self-hosted obfuscated VPN.
