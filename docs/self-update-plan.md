# Plan: the server updates itself (through maintenance mode)

Status: **parts 1 and 2 done (0.17.0-beta)**: the split into server/tgflip and maintenance running
from a copy of the program; next part 3 together with the admin channel (one version, 0.18.0: they belong together). Owner,
2026-11-07.

## Why

Updating the packaged server is now: download the zip, unpack it over the old one by hand, and
not lose `.env`, `accounts.json`, the sessions, modules and caches on the way. Maintenance mode
should do it: check GitHub, download, verify, swap, and be able to go back.

The hard part is Windows: a running program's exe and DLLs are locked and can't be replaced. The
owner's idea solves it without a second program or a bigger download: **maintenance runs from a
copy of the server program**, so the real one isn't in use while it is replaced.

## Rule for all of it: nothing changes for apps or modules

- Every endpoint, parameter, JSON field, live event, header and close code stays exactly as it is,
  so every app version keeps working (old apps included: what they don't know still goes through
  the fallback channel).
- Module API 1 stays exactly as it is (`docs/modules-plan.md`, rule 2).
- `uvicorn server:app --host 0.0.0.0 --port 9436` and `python run_server.py` keep working.
- Checked after every step: `server/tools/check_server.py` (diffed against a run from before the
  step), the module tests (broken modules, the menu, helloworld, animated), two accounts, the
  live log, and the packaged exe.

## Part 1 (0.17.0): split server.py (refactor)

`server.py` is 2300 lines: settings, the log capture, accounts, Telegram connection handling,
message formatting, caches, every endpoint, live connections and startup, all in one file. It
becomes a package, `server/tgflip/`, where each file has one job:

| file | what moves there |
|---|---|
| `settings.py` | VERSION, CODENAME, the MTProxy settings, SERVER_MODE, SERVER_CACHE(_MB) |
| `log_capture.py` | LogTee, LogRecorder, the kept lines and their listeners |
| `accounts.py` (+ today's `accounts.py`) | Account, `account()`, `use_account`, `client`, ACCOUNTS, `@on` |
| `telegram.py` | `connect_telegram`, `recover`, `@recovering`, the watchdog, `bad_request` |
| `messages.py` | turning Telegram's messages into the apps' JSON (reply, call, voice, photo, video, sticker, reactions, custom emoji, sender, previews) |
| `cache.py` | `cache_path`, `in_cache`, `sent`, `stored`, cleaning, the old-cache move |
| `routes/*.py` | the endpoints, grouped: chats and messages, media (photo, image, video, voice), stickers, reactions, server info and messages, live connections (`/ws`, `/logs`) |
| `live.py` | the Telegram update handlers and `broadcast` |
| `app.py` | the FastAPI app: AccessLog, the token check, lifespan, routers, modules, the web page |

- `server.py` stays as a short file that builds and exposes `app` (so `uvicorn server:app` and
  everything that imports `server` keep working).
- The module loader gets what modules need from one place (the module context reads it there)
  instead of from the whole server module, so module API 1 no longer depends on server.py's
  insides.
- Maintenance (`maintenance.py`, `module_menu.py`) imports only what it uses (settings, accounts,
  the Telegram login, module checks), not the web server.
- Done as a **move, not a rewrite**: code moves file by file with no changes to what it does, the
  check after each move. Then the docs (CLAUDE.md's names and places).

## Part 2 (0.17.0): maintenance runs from a copy of the program

The download stays one program (`tgflip-server.exe` + `_internal`, one Python, one Telethon).

1. `tgflip-server.exe --maintenancemode` (or `Maintenance mode.bat`) copies the program (the exe
   and `_internal`) into `maintenance/` next to it (a second or two; always fresh, so maintenance
   is always the installed version), starts that copy with `--maintenancemode --data <the folder>`,
   and exits.
2. The copy runs the menu, with the data (`.env`, `accounts.json`, sessions, modules, caches) in
   the folder above. Nothing of the real program is in use any more.
3. "s) Start the server" starts the real `tgflip-server.exe` and the copy closes.

- Development (`python run_server.py --maintenancemode`) runs the menu directly, as now: there is
  no exe to lock.
- Every server release also gets a `.sha256` of its zip (`build_server.ps1`), so 0.17.1 has
  something to check.

## Part 3 (0.17.1): "u) Update"

- **Check:** GitHub's releases list for the repository (`api.github.com`; module releases,
  `module-*`, are skipped), compared with `app_version_key`. Shows the newer version and its notes.
- **Download and verify:** the server zip and its `.sha256` (the module installer's code), into
  `update/`; refused if they don't match. Without internet: "the path of a downloaded zip".
- **Swap** (only possible while the server isn't running; Windows refusing the rename is the
  check, then "close the server first"): the current `tgflip-server.exe` + `_internal` move into
  `previous/`, the new ones into place. Renaming whole folders is close to instant, so a power cut
  leaves either the old version or the new one.
- **Roll back:** swaps `previous/` back.
- **Modules:** the same menu checks the installed modules for newer `module-<id>-v*` releases.
- Leftovers of an interrupted update (`update/`, a half swap) are cleaned up on the next run.
- The real test: 0.17.1 updating itself to the release after it.

## Announcing and pre-releases (owner, 2026-11-07)

- **The server announces a newer version:** at startup (and once a day while running) it checks
  GitHub in the background, best effort; when there is one, it prints one line in its log
  ("A newer server version is out: 0.17.2-beta, see Maintenance mode") and the notice
  `server_update` says so to **admin accounts** only (their apps show it in Under the hood >
  Messages from the server, highlighted; through the fallback channel, so today's apps get it too).
- **Pre-releases only when asked for:** the check and the announcement look at stable releases
  only; "u) Update" asks "Include pre-releases? (y/N)" and only then offers them. Until a release
  that isn't a pre-release exists (all are so far), the announcement stays quiet.

## The admin channel (owner, 2026-11-07; in the same version as part 3)

Admin-only news (first of all "the server can be updated") should reach the admins without them
having to open Under the hood:

- **Server:** notices can be marked admin-only (only admin accounts get them). Apps that know the
  kind `admin_messages` get them from their own endpoint (e.g. `GET /admin_messages`, the same
  shape as /server_messages) and the server leaves them out of /server_messages for those apps;
  every other app gets them in /server_messages as before. So: **admin channel, falling back to
  the fallback channel** for older apps.
- **App:** knows `admin_messages`; for admin accounts it checks them (on start, and when the live
  connection comes back) and shows a **notification right away**, without opening Under the hood:
  a dialog or a line on the chat list the first time each message is seen (remembered, so it
  isn't shown again and again), which can lead to the message itself.
- First admin message: `server_update` ("A newer server version is out: 0.18.1-beta. Update it
  in Maintenance mode on the PC").
