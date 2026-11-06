# Plan: one server, several Telegram accounts

Status: step 1 done in v0.12.0-beta (the Account object, still one account), plus 0.12.1-beta
(Android 17's local network permission, found while testing two phones on one account).
Steps 2-5 are **0.13.0-beta "Almost Liquid"**, the first release of that era (owner,
2026-11-06). To pick it up, ask: "let's do the multi-account plan from
docs/multi-account-plan.md". Read "Handoff for 0.13.0" below first.

## Why

The "under the hood" log (WebSocket `/logs`) shows every request the server sees: IP
addresses, chat ids. Fine for a private server, not for one that several people share. And a
server for several people needs several Telegram accounts.

## Handoff for 0.13.0 (how step 1 is built, written at 0.12.1-beta)

### The current account

- `server/server.py`, right after `proxy_args`: `class Account` holds everything that belongs
  to one Telegram account:
  - `name`, `token` (the app token that means this account), `client` (its own
    `TelegramClient(session, API_ID, API_HASH, **proxy_args)`);
  - `ws_clients` (its apps' live connections), `telegram_config`, `forum_unread_cache`,
    `sticker_documents` (file references are per account), `sticker_sets`,
    `video_downloads`, `local_favorites` (its file path), `reconnect_lock`,
    `last_recovery`, `watchdog` (its task).
- The current account lives in a context variable, `_current_account`:
  - `account()` returns it, `use_account(acc)` sets it.
  - `client` is a `CurrentClient` proxy: every `client.x` / `await client(...)` goes to the
    current account's client. That's why no Telegram call had to change.
  - Who sets it:
    - `current_account`, the dependency of the `api` router: picks the account by the
      request's `x-token` (`account_with_token`, compares with every account's token).
      It's **async on purpose**: a plain `def` dependency runs in another thread and the
      choice would be lost.
    - `ws_token_ok(ws)`: for `/ws` and `/logs`, by the first message.
    - `register_handlers(acc)`: Telegram update handlers are declared with `@on(event)`
      (collected in `event_handlers`) and registered per account at startup; each wrapper
      calls `use_account(acc)` first. It registers a `copy.copy` of each event builder per
      client.
    - `lifespan`: for each account, `use_account`, `register_handlers`, `connect_telegram`,
      then starts its `watchdog` task (tasks copy the context, so it keeps its account).
    - `run_server.py` `serve()`: `server.use_account(server.ACCOUNTS[0])` before the login
      (`connect_telegram`, `client.start()`), because that runs before the web server.
- `ACCOUNTS = [Account("main", os.environ["API_TOKEN"], "tg_session", "local_favorites.json")]`:
  today's only account. **0.13.0 replaces this line** (see below).

### Still server-wide (decide per item in 0.13.0)

- Deliberately shared (fine for every account): the log (`log_lines`, `log_listeners`),
  Telegram's `standard_reactions`, `custom_emoji_alts` (an id's plain emoji is the same for
  everyone), `seen_apps`, `GENERAL_*` constants.
- **Must become per account (step 3)**: the media caches on disk, all built by
  `cache_path(folder, name)`: `photo_cache/`, `image_cache/`, `video_cache/`,
  `sticker_cache/` (stickers are the same for everyone, but file references aren't; keep it
  simple and make them all per account), plus `PHOTO_CACHE` in `get_photo`, which builds its
  paths without `cache_path` (convert it). Simplest: `cache_path` puts files under
  `<folder>/<account name>/`.
- `/logs` is open to every token: step 4 makes it owner-only or private-mode-only.

### What 0.13.0 has to do, concretely

1. **accounts.json** next to `.env` (git-ignored, refused by `build_server.ps1` like
   `local_favorites.json`): `[{name, token, session, local_favorites}]`. If it's missing,
   build it from today's setup: `{"name": "main", "token": <API_TOKEN from .env>,
   "session": "tg_session", "local_favorites": "local_favorites.json"}`, so nothing moves
   and nobody logs in again. Build `ACCOUNTS` from it.
2. **Unique tokens**: refuse to start when two accounts share a token.
3. **Per-account caches**: `cache_path` + `get_photo` as above.
4. **`--maintenancemode`** in `run_server.py`: a small menu (the same place the planned
   animated-stickers add-on will plug into later): list accounts, **add an account** (asks
   a name, logs the phone number in with code/2FA into a new session file, generates a
   token and prints it once for that person's app), remove an account, rename, then start
   the server or quit. Recommended and agreed in principle: adding accounts on the PC
   (login codes never cross the network); confirm with the owner when starting.
5. **Private or shared mode**: first-run setup (and maintenance mode) asks; stored in
   `.env` (e.g. `SERVER_MODE=private|shared`). Shared: `/logs` refused (close code 4003)
   except maybe for the first account (the owner); `GET /server_info` -> `{version, shared}`;
   the app's log screen says "This server is shared: its log is private".
6. **Per-account `seen_apps` printing**: "App connected from <ip>: TG Flip <ver> (account
   <name>)" so the owner sees whose app it is (never print tokens).
7. **Tests**: `server/tools/check_server.py` (in the repo) checks every read-only endpoint
   and two live connections; run it per token before and after. Then: two accounts at once
   (different chats, no mixing), two phones on one account, a wrong token, FLOOD_WAIT on one
   account not holding up another.
8. Docs: CLAUDE.md "Accounts" and "Server API" lines, README (setup, maintenance mode), the
   server zip's README.txt, this plan.

### Phones for testing

- Kyocera A202KC, Android 10 (the main phone; USB `5899040449`).
- Pixel 8 Pro, Android 17 (USB `3C091FDJG001PH`; release app 0.12.1-beta installed): the
  second account's phone. It needs the "Nearby devices" (local network) permission, which
  0.12.1 asks for, and the PC's 5 GHz Wi-Fi ("Xiaomi_Tapok_5G"; the router also has a
  2.4 GHz network, which can't reach the PC).
- The owner only wants new versions on the Pixel once multiple accounts are being tried.

## Steps (original plan, still valid)

### 1. Account object, still one account (pure refactor) - done in 0.12.0-beta

Checked endpoint by endpoint against the old server (identical answers, except a fixed 500 for
unknown stickers), with both start paths (`uvicorn server:app` and `run_server.py`), and with
two apps on one account (both get every live event).

### 2. Several accounts

- `accounts.json` (see the handoff). The token says whose request it is; one account's token
  never reaches another account's data.
- Migration: today's `.env` `API_TOKEN` + `tg_session.session` become account #1
  automatically. No new login, no app change.
- One `API_ID`/`API_HASH` and the MTProxy serve all accounts.

### 3. Per-account caches (privacy)

- Otherwise one account could get another's cached picture by guessing ids, and message ids in
  private chats are per account anyway, so shared files would also mix up photos.

### 4. Private or shared mode

- First-run setup asks: private server, or for several people.
- Private: the log works as now. Shared: `/logs` refused; the app says so.

### 5. Testing

- Two accounts at once; two phones on the same account at once.
- Telegram's per-account limits (FLOOD_WAIT): one busy account must not hold up others.

## After 0.13.0

- The optional animated-stickers add-on (docs/stickers-plan.md, "Animated mode"), as another
  item in the same maintenance menu; server-wide (one ffmpeg serves every account).
- Version eras: 0.13.0-beta on is "Almost Liquid"; "Melted" is reserved for v1.0.0.
