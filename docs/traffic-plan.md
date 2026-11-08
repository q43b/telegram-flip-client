# Plan: modules that see and change traffic

Status: **groundwork built in server 1.4.0** (see "Built in 1.4.0" below); the rest is ideas (2026-11-08, at v1.3.0). Owner's request: modules
should be able to intercept incoming and outgoing traffic, if they can't yet; for now note
everything down and decide whether the module API needs a version 2 or whether API 1 can grow.

**Answer: API 1 grows. No API 2, and no "1.5" either.** Reasons and the exact additions are below;
the short version is at the end of "The API question".

## Why (the two modules behind this)

1. **A compatibility module for legacy / lite clients** (owner: maybe later a client for Java
   phones): serves old or tiny clients something they can handle: a lighter protocol (plain
   text instead of JSON, smaller pictures, long polling instead of a WebSocket, maybe plain HTTP
   on another port).
2. **A placeholder-Telegram module** (working name "mockgram"): the account talks to a made-up
   Telegram, written later, so screenshots (README, release pages) can show invented chats and
   people instead of the owner's real ones. The app doesn't change: it connects to the same
   server, with the token of the placeholder account.

## Where traffic flows

```
   phone app / lite client
        |   A: requests in           B: responses and live events out
        v                            ^
   +--------------------------------------------------+
   |  the server's API  (tgflip/routes, api, live)    |
   +--------------------------------------------------+
        |   C: requests to Telegram      D: results and updates in
        v                                ^
   Telethon's TelegramClient  <---- MTProto ---->  Telegram
```

- **A** and **B** are HTTP requests and responses, and the WebSocket `/ws` (live events are sent
  by `live.broadcast`) and `/logs`.
- **C** and **D**: every Telegram call goes through `client` (the current account's
  `TelegramClient`, see `account.py`). The server makes about 40 kinds of Telethon calls
  (`get_messages`, `iter_dialogs`, `download_media`, `send_message`, plus about 20 raw
  requests such as `GetStickerSetRequest`). Updates come in through `@on(events...)` handlers
  (`register_handlers`), which turn them into the live events of B.

## What a module can do today (API 1)

- Its own routes at `/modules/<id>/...`, with the app token checked and the account set.
- `context.client`: the raw Telethon client, to *make* calls. It can't see or change the server's
  own calls.
- Hooks: `@context.hook(name)`, **answer style** (`call_hook`: the first module that returns
  something other than None wins). Only one hook point exists, `sticker.animate`.
- Notices, `on_start` / `on_stop` (a module may start its own background work there, for
  example another listener), a cache folder per account, a data folder.

Can't do:
1. See or change a request or response of the server's existing endpoints (A, B).
2. See or change live events (B), or send one of its own.
3. Mount routes outside `/modules/<id>/`, or choose its own auth.
4. Call the server's own endpoints in-process as an account (a background listener has no
   "current account", because it's set per request by `current_account`).
5. See, change or answer the server's Telegram calls (C, D).
6. Provide an account that doesn't log in to Telegram (accounts are `accounts.json` entries,
   each made into a real `TelegramClient`; connect, watchdog and recovery assume the real one).

## What each module needs

### Lite / legacy compatibility module

Its clients speak a different protocol, so it mostly **adds** things, not changes the app's
traffic. It needs:

| Need | Why |
|---|---|
| **In-process calls to the API as an account** | Its own listener (another port, or raw sockets for a Java phone) answers a client by asking the server's real endpoints and reshaping the answer. A `context.api_call(token, method, path, json=None)` (through ASGI, no network) gives (status, data). |
| **Run as an account** | Background code needs the account set: `context.as_account(token)` (a `with`, or an async variant). |
| **Live events as a stream** | To push to clients without a WebSocket: `context.live_events(token)`, an async iterator of the events `broadcast` sends; read only is enough. |
| **Own listener, own config** | Possible now through `on_start` and `data_folder`; a module settings menu in Maintenance mode (planned for themes_host 1.1.0) is where the port etc. would be set. |
| Optional: change the real app's answers | For example smaller pictures for a slow phone: the filter hooks below (`http.json`, `live.event`). Not needed for a client with its own protocol. |

### Placeholder-Telegram module

This one **replaces** C and D. It never talks to the network.

Where to cut: **not** the MTProto wire (a Python MTProto server means the key exchange,
encryption, sequence numbers and so on: weeks of work for nothing). Instead at Telethon's single
choke point: every high-level call ends in `TelegramClient._call(sender, request)`. A
`TgClient(TelegramClient)` subclass overrides it: a module may *answer* the raw request with
the Telegram objects Telethon would have parsed from the wire (`types.messages.DialogsSlice`,
`types.messages.Messages`, `types.Updates`, ...); Telethon's high-level methods and the server's
own code then work unchanged. Only about 35 request types need answers (the ones the routes
use: `GetDialogs`, `GetHistory`, `GetPeerDialogs`, `SendMessage`, `GetAllStickers`,
`GetStickerSet`, `GetFullChannel`, `GetForumTopics`, `GetTopReactions`, `SendReaction`, `GetConfig`,
file downloads...). Content comes from scripted data ("scenes": chats, people, messages, photos
and stickers made for screenshots).

Needs:

| Need | Why |
|---|---|
| **An account that isn't real** | `accounts.json` gets `"kind"` (default `"telegram"`); a module that answers `account.make` supplies the client for other kinds. Maintenance mode's "a) add" asks the kind: no phone login for a placeholder account. Watchdog, recovery and the proxy are skipped for it. |
| **Answer a raw request** | Answer-style hook `telegram.request` (request -> result or None; None = go on to the network, or to the next module). |
| **Connect without a network** | `Account.connect` asks the hook `telegram.connect` first; a module that owns the account says "connected" and gives `get_me()` its made-up person. |
| **Inject updates** | `context.push_update(update)`: hands Telethon update objects to the same handlers real ones reach (`events.NewMessage` and the rest), so live events work in the screenshots (a typing message, a new chat). |
| **Know the account is not real** | `context.account().kind`, so modules that need real Telegram (animated stickers do not: they only convert files) can say so. |

(Refined 2026-11-08, see "Placeholder Telegram: the design" below: the better seam is Telethon's
*sender*, the object `_call` hands every request to, not `_call` itself.)

Telethon's `_call` is private API. It is contained: the one subclass lives in the server, Telethon
is pinned in `requirements.txt`, and a startup check refuses to start with a changed signature.
(The public way to watch outgoing calls doesn't exist; incoming updates already have a public
one, `add_event_handler`.)

## Mechanisms (server side)

1. **Filter hooks**, a second hook kind next to the answer-style one: `@context.filter(name)`.
   *Every* module's function runs, in order, each getting the previous one's result and returning
   a new value (or None = unchanged). A failing filter is skipped with one log line (as hooks
   are). Order: load order (alphabetical by id) unless the manifest says
   `"after": ["id", ...]`. Used for B and D observation and rewriting:
   - `http.json` (path, method, status, data) -> data: JSON answers of the existing endpoints.
   - `live.event` (event) -> event; returning `DROP` (a constant the context provides) removes it.
   - `telegram.result` (request, result) -> result.
2. **Answer hooks** (the existing kind) for the "somebody has to do it" points:
   `telegram.connect`, `telegram.request`, `account.make`, `http.request` (answers a request
   before the server sees it: a `Response`, or None).
3. **One server-owned ASGI middleware** runs `http.request` and `http.json`. It does nothing
   (and costs nothing) while no module has those hooks (`has_hook`). It only touches JSON
   answers under a size limit; pictures, videos and other streamed files pass untouched.
4. **`broadcast` runs `live.event`** before sending, and `context.broadcast(data)` lets a
   module send an event of its own to the account's apps.
5. **`ModuleContext` additions**: `api_call`, `as_account`, `live_events`, `push_update`,
   `broadcast`, the `filter` decorator, `DROP`.
6. **`Account.kind`** and an `accounts.json` field `kind` (missing = `"telegram"`, so every
   existing file keeps working; `accounts.check()` accepts it).
7. **Permissions are shown, not enforced.** A module runs inside the server, so it can do
   anything anyway; installing one is trusting it. But a module that sees traffic sees every
   message, so `module.json` gets an optional `"uses": ["traffic.http", "telegram.transport"]`
   (names for what it touches). Install (zip or GitHub) prints it in the question
   ("This module can read and change messages passing through the server. Install?"), and Under
   the hood > Modules shows it. Unknown names are shown too.

## The API question

**Do we need API 2, or will something like "API 1.5" do?** API 1.x, additions only: no API 2.

How the rules already decide it (`modules-plan.md`, rules 1 and 2):
- API 1 **only ever grows**: new context members, new hook names, new optional manifest keys.
  A module written for 0.15.0 never calls any of them, so nothing it uses changes. Every
  addition above is of that kind: new decorators, new hook names, new methods, new optional
  keys (`uses`, `after`). `accounts.json`'s `kind` has a default. Existing hooks keep their
  semantics (answer style); the chain semantics are a *new* decorator, not a change of the old.
- "Minor" versions aren't needed: `module.json` already has `min_server`. A module that uses
  `context.filter` says `"min_server": "1.4.0"` (whatever release adds it), and an older server
  refuses to load it with one clear line. A module that can do without can test for the member
  with `hasattr(context, "filter")` (the context is a plain object). Putting "1.5" in `api`
  would add a second number that says the same thing less exactly, and `API_VERSIONS` compares
  whole numbers.
- `API_VERSIONS` is already a tuple (`(1,)`): a future API 2 can sit next to API 1 (the loader
  would build the right context for each module) without touching any API 1 module.

What *would* force API 2 (so we know the line, and avoid crossing it by accident):
1. Changing what an existing member returns or means: for example making `context.client` a
   wrapper that is no longer a `TelegramClient`, or `context.account()` returning something else.
   (With placeholder accounts, `context.client` is still a `TelegramClient` subclass, so API 1
   code keeps working; it just isn't talking to Telegram.)
2. Changing the order in which existing hooks run, or making an existing hook a chain.
3. Making `setup` async, changing the thread model, or running modules in separate processes
   (isolation): that changes what a module may assume.
4. Removing or renaming a hook point (`sticker.animate`).
5. Letting two modules own one resource that a module used to own alone.

The one place to watch: **placeholder accounts change what "the account" is for modules written
earlier.** A module that, say, downloads media through `context.client` on a placeholder account
gets the made-up answers (fine) or an error for something the fake doesn't know (the module
fails for that request, like for any Telegram error). To be explicit, `Account.kind` and a
`provides`-style flag tell modules; no API change.

Two modules at the same hook: answer-style hooks stay "first answer wins" (load order);
filters chain; the *transport* of one account is owned by one module only (its `kind`), so a
lite module and a placeholder module don't fight: lite sits on A/B, the placeholder on C/D.

## Steps (when we build it)

1. **Groundwork (server 1.4.0, no module yet):** `filter`, `DROP`, the middleware and
   `live.event`, `api_call`, `as_account`, `live_events`, `broadcast`; `uses` in manifests and
   install questions; tests with a throwaway module that logs traffic (read only), checked
   against the baseline snapshot like the refactor was (nothing changes without a module).
2. **Telegram transport:** `TgClient` with `_call`, the hooks `telegram.request`,
   `telegram.connect`, `account.make`, `push_update`, `Account.kind`, `accounts.json` and the
   Maintenance menu (add an account of another kind). A throwaway module answering `GetConfig`
   and `GetDialogs` proves the cut before any real content is written.
3. **Module "mockgram"** (own plan: the scenes format, the list of requests, a few chats for
   the README), released apart from the server.
4. **Module "lite"** (own plan; needs the Java client's protocol first).

## Decisions (owner, 2026-11-08)

- The API question: **API 1 grows**, confirmed. Placeholder Telegram = answering on the way to Telethon
  (`_call`), as in "What each module needs".
- **`uses` is warned about, also on updates**: installing shows what the module touches; an update
  that adds a name to `uses` asks again (default no), even though updating was already confirmed.
- **Placeholder accounts show a "demo account" line in Under the hood** (the server line), and the
  server log names the kind. Nothing on the normal screens, so screenshots look real.
- **"Legacy" means our own older alpha and beta apps** (not Java phones: a Java client is much
  later, and its module "lite" waits for it). The legacy module comes first: older apps can be
  downloaded from the releases and tried, so no placeholder server is needed to test it. Building
  it is also how the traffic hooks get built and proven, ready for later modules.
- Still open, later: one token or one per client (shared first), the lite client's transport.
- **The placeholder module is called `mockgram`** (owner, 2026-11-08), and **runs inside the server**: no
  separate process (the owner won't run it so long that a real server must run beside it); the
  backend stays behind a clean Python interface anyway. **The owner takes the screenshots.** Scenes:
  scripted JSON files, English chats plus one Russian chat (for the font), no audio (screenshots
  only need a voice message's duration): assumed defaults, to change on request.

## Built in 1.4.0 (the groundwork)

- `@context.filter(name)` with the two filters **`http.json`** (`function(info, data) -> data`, on every
  successful JSON answer up to 2 MB, after the endpoint; files and errors pass untouched) and
  **`live.event`** (`function(info, event) -> event | context.DROP`, once per app connection, on its
  own copy). Load order; a failing filter is skipped and reported at most once a minute.
- `info` is a `module_loader.Traffic`: `path`, `method`, `status`, `account` (the account's name),
  `host`, `client_version`.
- `context.DROP`, `context.version_key`, `"uses"` in module.json: **enforced as a declaration**
  (registering a filter without its use fails the module with a clear line), warned about at
  install and, with "no" as the default, again when an update adds a use; shown in the
  startup line, in Maintenance mode's list, in `GET /modules` and in Under the hood > Modules.
- `client_version`: the request's own `x-app-version` (apps from 1.4.0 send it on every request),
  else the version noted on a live connection from the same address and account
  (`tgflip/clients.py`; the newest wins, noted in the log when two differ).
- `tgflip/traffic.py` is the middleware: no cost while no module has an `http.json` filter.
- Checked: 34 checks in a scratch folder with throwaway modules (order, failures, DROP,
  declared uses, JSON/list/text/error/big answers, per-connection live filtering, the registry,
  install and update warnings), the API snapshot (routes, OpenAPI, notices identical), and the
  read-only check against the real server (identical but for data that changed in between).
- **Not built yet** (the lite and placeholder modules need them): `api_call`, `as_account`,
  `live_events`, `broadcast`, `push_update`, `Account.kind`, the `telegram.*` and `account.make`
  hooks, `after` (ordering), `http.request`.

## The legacy module first (module id `legacy`)

What the audit found (2026-11-08, `legacy_audit.py`/`legacy_compare.py`, reading each released app's
own source at its tag: 57 apps, 0.1.0-alpha to 1.3.0):
- **Nothing is statically broken.** Every endpoint any old app calls is still routed, every key an
  old app reads with `get*()` (which throws when missing) is still in the answers, and every
  request body field the server requires was already sent by the first apps. The core server has
  kept its promise to old clients.
- So the module's job isn't repairs but **making old apps better**: they only show what they were
  built to show. The first alpha reads just `id`, `text`, `out`, `date`, `reply_to_id` (no sender
  names in groups, so a group chat is unreadable); 0.11.2-alpha adds sender, calls and voice; the first
  beta adds photos; stickers, reactions and custom emoji come later still. An old app gets a
  sticker, a photo or a reaction as an empty line or "[media]".
- **Apps only send their version when they open the live connection** (`x-app-version` on `/ws` and
  `/logs`, since 0.7.1), never on ordinary requests. So to adapt an answer to the app that asked,
  the server must know which app that is.

How the module works:
1. **Who is asking.** The live connection tells the server (account, address, app version); the
   server keeps the latest version per (account, address) and gives it to filters as
   `client_version` (None = unknown: alphas before 0.7.1 and any app that hasn't connected live;
   treated as the oldest era). New apps (1.4.0 on) send the header on every request, so it's exact for them.
   Ambiguity (two phones with different versions behind one address) picks the newest and logs it.
2. **What it changes**: `eras.json` in the module, a table from "first version that shows X" to a
   text rule, data, not code, so the owner can adjust it. For an app older than that version:
   - a message with media gets the label the newer app would build itself ("🖼 Photo: caption",
     "🏷 Sticker 😂", "🎤 Voice message 0:12", "☎ Missed call");
   - group messages get the sender in front of the text ("Anna: hi") for apps that don't show it;
   - reactions are added as a line of text ("👍 3  ❤ 1") for apps that don't show them;
   - the same in `/chats` (`last.text`) and in live events (`message`, `edited`).
3. **Hooks it proves**: `http.json` on `/messages`, `/chats`, `/topics`; `live.event` on `message`
   and `edited`; `client_version`. All additions to API 1.

Steps:
1. **Server 1.4.0 groundwork**: `filter`, `http.json` (middleware, only while a module has it), `live.event`
   and `DROP`, `client_version`, `uses` with install and update warnings, a throwaway read-only
   logging module for tests; checked against the baseline snapshot (nothing changes without a module).
2. **Try the old apps for real**: build chosen old tags as debug builds in git worktrees
   (`com.q43b.tgflip.debug`, so the owner's release app and data stay), install one at a time, and
   write down what each shows wrongly or not at all. This decides the rules in `eras.json`.
3. **The module** with those rules, tested by replaying every old app's requests (and the real apps).
4. **Release**: server 1.4.0 (+ app 1.4.0 sending the version on every request) and
   `module-legacy-v1.0.0`.

## Placeholder Telegram: the design (2026-11-08)

Owner's picture: **a placeholder for the Telegram server**, and **a module that intercepts the
server's traffic and sends it there**. Two parts, with one clean boundary between them.

```
 routes (unchanged)  ->  client  ->  FakeClient (module)  ->  backend (the placeholder)
                                          ^                        |
                                          '---- updates (live) ----'
```

- **The backend** (the placeholder Telegram): a Python class that knows nothing about TG Flip. It
  answers Telegram requests (`handle(request) -> result`, as Telethon's own request and result
  objects) from a *scene*, and can push updates (new messages, reactions...) on its own.
- **The bridge** (the module's interceptor): provides an account kind `mockgram`. Its client is a
  `TelegramClient` subclass whose connection is replaced: every request Telethon would put on the
  wire goes to the backend instead, and the answer returns through Telethon's own code.

### Why this cut (checked in Telethon 1.45.0, the pinned version)

- Every Telegram call, high-level (`iter_dialogs`, `get_messages`, `send_message`,
  `download_media`...) or raw, ends in `_call(sender, request)`, which hands the request to
  `client._sender.send(request)`, a future that resolves to the result. A **fake sender** with
  `send`, `is_connected`, `connect`, `disconnect` is all the cut needs.
- `_call` then runs Telethon's own bookkeeping on the result (`session.process_entities`, the
  entity cache), so peers, names and access hashes in the backend's answers work like real ones,
  and the server's routes don't change at all: `client.iter_dialogs()` etc. just work.
- Downloads use `client._sender` when the file's `dc_id` equals `session.dc_id`
  (`downloads.py`: `_exported = dc_id and session.dc_id != dc_id`): fake files carry that DC and
  never trigger Telethon's network export.
- Live updates enter through the client's `_updates_queue` (what the real sender fills): the
  bridge puts the backend's `types.Updates` there, so the server's own `@on(events...)` handlers
  turn them into the app's live events.
- Private parts touched: `client._sender`, `_updates_queue`, the update loop's start, and
  `session.dc_id`. All inside the one `FakeClient`; Telethon is pinned in `requirements.txt` and
  the module checks its Telethon version when it starts.
- The session is a `MemorySession`: **no file, no login, no network.** A placeholder account can't
  hold a real Telegram session, which is also what keeps real data out of the screenshots by
  construction.

### What the server needs (built 2026-11-08 as part of server 1.4.0, which is not released yet)

1. `accounts.json`: `"kind"` (missing = `"telegram"`) and, for other kinds, a free `"settings"`
   object for the module (for mockgram: which scene). `accounts.check()` accepts them.
2. An answer-style hook **`account.make`** (`function(entry) -> client or None`): asked when an account
   is made; a module that owns the kind returns its client. Nothing else is asked for a
   `telegram` account.
3. `Account.kind`, and the machinery that only makes sense for real Telegram skipped for the other
   kinds: the proxy, `connect_telegram` retries, the watchdog and `@recovering`'s reconnect.
4. Maintenance mode "a) add" asks the kind (no phone login for a placeholder) and the scene.
5. **The demo line**: `/server_info` gets `kind`; Under the hood's server line says "demo account"
   for non-real kinds (nothing on normal screens), and the log names the kind.
6. Modules may import the libraries the server ships (Telethon, Pillow); documented, with the
   version check as the module's job.

`telegram.request`, `telegram.connect`, `push_update` from the first draft aren't needed: the
module owns the whole client, so the server only has to ask for it.

### The backend

- **Scenes**: JSON in the module (and the owner's own in `module_data`): *people*, *chats* of every
  kind the app knows (private, group, forum with topics, channel), *messages* with relative times
  ("-5m", never a date, so a screenshot always looks fresh), replies, edits, reactions, and every media
  kind (photo, sticker, voice, video, round video, call record), sticker packs, custom emoji,
  profile photos, the account's own Favorites.
- **Assets are generated, never real**: photos and profile pictures drawn with Pillow (and the
  owner's pixel art), stickers from the owner's drawings. Nothing from real Telegram goes in.
- **Scripted live events**: a scene can say "after 3 s, Anna writes...", so a screenshot of a message
  arriving can be made.
- **Unknown requests** answer with an error Telegram itself uses (so the endpoint says 400 as it
  would for a real refusal) and one log line naming the request: the list of what's missing writes
  itself.
- **Writes work** (send, edit, delete, react, read, forward) against the scene in memory: sending a
  message in a screenshot session behaves like the real app. The scene resets on restart.

### How we know it's right

- **Inventory first**: a scratch tool logs the name of every Telegram request (and the result type,
  never content) while the existing read-only check and the app's flows run against the real
  server. That is the exact list the backend must answer; I estimated about 35.
- **Shape parity**: `tools/check_server.py` already checks every read-only endpoint and prints
  answer shapes. Run it for the real account and for the placeholder account (`TGFLIP_TOKEN`) and
  the *shapes* (keys, types) must match line for line.
- Per request, a test feeds the backend's answer through Telethon's own parsing (`to_dict` round
  trip and the high-level call) so a malformed object fails in the test, not in a screenshot.

### The request inventory (step 1, done 2026-11-08)

Measured with `server/tools/request_inventory.py` (a patch on Telethon's `_call`, names only) while
`tools/check_server.py` ran as both accounts plus extra read-only calls (a sticker's pack, a custom
emoji's picture, a full-size photo, an animated sticker): 44 distinct (server function, Telethon
method, request, result) combinations, from **21 distinct read-side request types**. The write
side was *not* run (that would send things to real people); its requests are read from Telethon's
source. **About 32 request types in all** (the first estimate was 35).

**Connecting** (the fake's `connect()` can skip all of it; the backend answers them anyway for the
tests): `updates.GetState` -> `State`, `updates.GetDifference` -> `DifferenceEmpty`,
`users.GetUsers([InputUserSelf])` -> `[User]` (`get_me`), `help.GetConfig` -> `Config` (the server's
`get_telegram_config`: edit and delete time limits).

**Chats and messages**

| Request | Used by | Answer |
|---|---|---|
| `messages.GetDialogs` | `/chats` (`iter_dialogs`) | `Dialogs` / `DialogsSlice` |
| `messages.GetPeerDialogs` | `/chats?ids=` | `PeerDialogs` |
| `messages.GetForumTopics` | `/topics`, the forum's unread count | `ForumTopics` |
| `messages.GetHistory` | `/messages` (`iter_messages`, 63 of the 100 calls in the run) | `Messages`, `MessagesSlice` or `ChannelMessages`, **by chat kind** |
| `messages.GetMessages`, `channels.GetMessages` | single messages (media routes, delete options) | `Messages` / `ChannelMessages` |
| `users.GetUsers`, `channels.GetChannels`, `messages.GetChats` | `get_entity` (profile, group info, delete options, reactions) | `[User]`, `Chats` |
| `messages.GetFullChat`, `channels.GetFullChannel` | `/group`, allowed reactions | `ChatFull` |
| `channels.GetParticipants` | group members | `ChannelParticipants` |

**Media**: `upload.GetFile` (photos, voice, video, stickers; 40 of the run's calls) -> `upload.File`;
`messages.GetCustomEmojiDocuments` -> `[Document]` (or `DocumentEmpty` for an unknown id).
Profile photos come from `download_profile_photo`, which for channels first asks
`channels.GetFullChannel`; the server caches them, so the run didn't see them.

**Stickers and reactions**: `messages.GetAllStickers` -> `AllStickers`, `GetStickerSet` ->
`StickerSet`, `GetFavedStickers` -> `FavedStickers`, `GetRecentStickers` -> `RecentStickers`,
`GetAvailableReactions` -> `AvailableReactions`, `GetTopReactions` -> `Reactions`.

**Writes** (from Telethon's source, not run): `messages.SendMessage`, `messages.SendMedia` with
`upload.SaveFilePart` / `SaveBigFilePart` for files, `messages.EditMessage`,
`messages.DeleteMessages` / `channels.DeleteMessages`, `messages.ForwardMessages`,
`messages.SendReaction`, `messages.ReadHistory` / `channels.ReadHistory` (also read mentions and
reactions), `messages.ReadDiscussion` (a forum topic), `messages.FaveSticker`,
`messages.InstallStickerSet`.

What this means for the backend:
- **Reads are 21 types and most of the traffic is two of them** (`GetHistory`, `GetFile`): the first
  scene needs these, `GetDialogs`, the entity lookups and the sticker set requests to work; the
  rest can follow.
- **Result flavor depends on the chat's kind**: history of a private chat is `Messages` /
  `MessagesSlice`, of a channel or supergroup `ChannelMessages`; the dialog list is `DialogsSlice`
  when paged. The backend must give each kind its own flavor, since Telethon and the server check.
- **Writes return `Updates`** that Telethon turns into the message just sent (`UpdateMessageID` +
  `UpdateNewMessage`), so the backend builds those too, and pushes the same update to the live
  stream.
- The fake `connect()` needs none of the connecting requests; but `get_me` is asked once by the
  server, so `GetUsers(InputUserSelf)` must answer with the account's made-up person.

### Alternatives considered

1. **A separate process with a wire protocol** (the bridge sends serialized requests to a
   standalone placeholder server over HTTP or a socket): the boundary is the same Python
   interface (`handle`, updates), so this can be added later without touching the backend, if a second
   backend or another machine ever calls for it. Not now: it adds a protocol and a process to
   manage for no gain yet.
2. **Anonymizing the real traffic** with the `http.json` and `live.event` filters (already built in 1.4.0):
   a quick way to blur names and text. Not for public screenshots: pictures and profile photos
   are files, which the filters don't touch, names show up in many places (profiles, group
   members, forwards, senders), and one miss shows a real person. It could still be a private
   "privacy mode" one day.
3. **A real MTProto server** (so the official clients could connect): weeks of work, no use here.

### Steps

1. **Inventory** of Telegram requests: done (see above; the tool is `server/tools/request_inventory.py`).
2. **Account kinds and the demo line: done** (part of server 1.4.0, with the traffic groundwork: nothing was
   released between them). Tested with a fake Telethon client (a real `TelegramClient`, `MemorySession`, a fake
   sender) whose `/chats` runs through the real route; 27 checks, the API snapshot identical, the
   read-only check against the real server identical to before.
3. **Backend, reads: done** (`modules/mockgram` 0.1.0): scene loader, the showcase scene, handlers for every read
   request the routes make (and `GetReplies` for forum topics), generated pictures; the read-only check's output
   is identical to the real account's, line for line; `server/tools/check_mockgram.py` keeps it that way.
4. **Backend, writes and live events: done** (`writes.py`, `script.py`; tested: send, reply, edit, delete, forward, react, read, auto replies, scripted events), scripted events.
5. **Assets and the showcase scene: done** (heart and cloud redrawn; `modules/mockgram/README.md` says how to use it); the owner takes the README screenshots.
6. **Release**: `module-mockgram-v1.0.0` with the server that has the account kinds (1.4.0).

## Questions asked 2026-11-08 (answers above where decided)

1. **Mockgram data:** scripted files (JSON "scenes", written by hand or generated), or a small
   editor on the PC? (Scripted files first would be my suggestion.)
2. **Several clients on a lite account:** one account may have phones and a lite client at once;
   the lite client sees the same chats. OK?
3. **Lite transport:** plain HTTP on a second port (Java phones often can't do TLS or
   WebSockets), long polling for events? The module's own plan decides this once the client
   exists.
4. **Where "uses" questions are asked:** also on updates of an installed module (when its
   `uses` grows), not only on first install. I'd say yes.
5. Should placeholder accounts be marked in the app (a "demo" flag in `/server_info`), so a
   screenshot phone can't be mistaken for a real account? Probably only for the admin.
