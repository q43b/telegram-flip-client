# Plan: one server, several Telegram accounts

Status: step 1 done in v0.12.0-beta (the Account object, still one account; checked endpoint by endpoint against the old server, and with two apps on one account). Steps 2-5 are 0.13.0-beta "Almost Liquid". (Planned 2026-11-06 at v0.4.0-beta.) The owner wants it
after video, stickers and reactions. To pick it up, ask: "let's do the multi-account plan
from docs/multi-account-plan.md".

## Why

The "under the hood" log (WebSocket `/logs`, 0.4.0-beta) shows every request the server
sees: IP addresses, chat ids. Fine for a private server, not for one that several people
share. And a server for several people needs several Telegram accounts, which the server
can't do yet.

## Where the server stands (0.4.0-beta)

- One global `TelegramClient` (`client` in `server/server.py`), one session file
  (`tg_session.session`), one `API_TOKEN` in `.env`. About 48 places use `client`, across 16
  HTTP endpoints, 6 `@client.on` event handlers and the WebSockets.
- Server-wide state that really belongs to an account: `ws_clients`, `telegram_config`,
  `forum_unread_cache`, `seen_apps`, the watchdog, `@recovering` reconnects.
- Server-wide data on disk: `local_favorites.json` (the local favourite stickers, 0.11.3) must become per account too.
- Server-wide caches on disk: `photo_cache/` (`<chat>_<photo>_<size>.jpg`) and
  `image_cache/` (`<chat>_<message>.jpg`). Both answer from the file before asking Telegram
  whether the requester may see that chat.
- Several apps on the same account already work in principle (live events go to every
  socket in `ws_clients`), but it was never tested.

## Steps

### 1. Account object, still one account (pure refactor) - done in 0.12.0-beta

Built with a "current account" context variable instead of an `acc` parameter everywhere: see
CLAUDE.md (Accounts). Adding accounts = more entries in `ACCOUNTS`.


- An `Account` class holding everything per account: its `TelegramClient`, `ws_clients`,
  caches, Telegram config, watchdog, reconnect logic.
- Every endpoint gets its account from the request (`account = Depends(current_account)`)
  and uses `account.client` instead of the global `client`. Event handlers are registered
  per client and know their account.
- Ends with exactly today's behavior, with the one existing account. Biggest and riskiest
  step: full test round on its own (chats, messages, send, read, edit, delete, forward,
  topics, photos, voice, images, profiles, group info, live events, calls, logs).

### 2. Several accounts

- `accounts.json` next to the exe (git-ignored, never packed into the zip, like `.env`):
  per account a name, its session file, its app token.
- The token says whose request it is (compare against every token with
  `secrets.compare_digest`). One account's token never reaches another account's data.
- Migration: today's `.env` `API_TOKEN` + `tg_session.session` become account #1
  automatically. No new login, no app change.
- One `API_ID`/`API_HASH` and the MTProxy serve all accounts.

### 3. Per-account caches (privacy)

- `photo_cache/<account>/…` and `image_cache/<account>/…`. Otherwise one account could get
  another's cached picture by guessing ids, and message ids in private chats are per
  account anyway, so shared files would also mix up photos.

### 4. Private or shared mode

- First-run setup asks: private server, or for several people.
- Private: the log works as now.
- Shared: `/logs` is refused (close code, e.g. 4003); the app shows "This server is
  shared: its log is private". Optional: an owner token still may see it.
- The app might ask a small `GET /server_info` → `{version, shared}` to know in advance.

### 5. Testing

- Two accounts at once; two phones on the same account at once.
- Telegram's per-account limits (FLOOD_WAIT): one busy account must not hold up others.

## Open question: how accounts are added (owner decides when this starts)

- On the PC (recommended): `tgflip-server.exe --add-account` asks for a name, logs in a
  phone number (code, 2FA), prints the new token for that person's app. Simple and safe:
  login codes never cross the network. The PC owner sets each person up.
- From the app: phone number and code typed on the phone. Convenient, but needs a login flow
  in the app, a guard against strangers creating accounts (e.g. invite codes), and login
  codes would cross the home network over plain HTTP.

## Cost

- Steps 1-3: a large server change, mostly the same edit repeated. Step 4: small.
- The app barely changes (the token already identifies the user); only the log message.
- Each account keeps its own Telegram connection through the proxy; a handful is fine.
- Suggested version: 0.5.0, possibly split (step 1, then steps 2-4).
- Every feature added before this (video, stickers, reactions) adds endpoints and caches
  that step 1 has to convert too: write them so the client and caches are easy to swap
  (one place that gets the client, cache paths built by one helper).
