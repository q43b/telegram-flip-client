# Plan: server modules

Status: **0.15.0-beta, 0.15.1-beta and 0.16.0-beta done** (the loader, module API 1, `GET /modules`, `capabilities`, the
`modules` notice, maintenance "x) Modules", `server/tools/build_module.py`, the demo module
**helloworld**; 0.15.1: the app's Modules row; 0.16.0: the animated module and the first hook, `sticker.animate`). Approach chosen
by the owner: **in-process Python modules** ("approach 1"), behind a small versioned module API.
How it is built now: CLAUDE.md, "Modules".

Later ideas (2026-11-08): modules that see and change traffic (a lite/legacy client module, a placeholder-Telegram module for
screenshots): `traffic-plan.md`; the conclusion there is that API 1 grows, no API 2 needed.

## Why

Some features are big or not for everyone: animated stickers need PyAV and rlottie (~28 MB).
"I don't need it, I don't install it": the server stays small, and whoever wants a feature
adds its module. Modules built for 0.15.x must keep working on later servers.

## The rules that keep modules working on later servers

1. **Modules only use the module API.** The server hands each module one object, the module
   context (below). Importing `server` and using its insides is not supported: those may change
   in any release. The context is the whole contract.
2. **The module API only grows.** API 1 is fixed once 0.15.0 is out: later servers may add to it
   (new methods, new optional arguments), never remove or change what's there. A breaking change
   would be API 2, and the server would keep loading API 1 modules (through an adapter if needed).
3. **Capabilities, not module names.** Modules say what they *provide*, e.g. `animated_stickers`
   (with its own version number, 1). Apps and server code ask "is `animated_stickers` 1 there?",
   never "is module X installed?". So two modules could provide the same thing, or a later server
   could build it in, without any app noticing.
4. **Every capability also has a notice** (the fallback channel from 0.14.0): apps that don't know
   it yet still get a line of text in Messages from the server.
5. **Stable ABI where possible.** A module with compiled libraries declares which Python it needs:
   `"abi3"` (any Python from the one it was built for; PyAV and rlottie-python are built that way)
   or one exact version (`"cp315"`). Pure-Python modules say `"any"`.
6. **A module never takes the server down.** Anything wrong with a module (bad manifest, wrong API,
   too old server, wrong Python, an exception while loading or starting) disables just that module,
   with one clear line in the log; the server starts anyway.

## What a module looks like

Source in the repo: `modules/<id>/`; packed by a build script into
`tgflip-module-<id>-<version>.zip` plus `<zip>.sha256`. Installed on a server PC into
`modules/<id>/` next to the exe (git-ignored), like this:

```
modules/
  helloworld/
    module.json        the manifest
    __init__.py        the code: setup(context)
    lib/               its own libraries, if any (added to Python's path after the server's own)
```

`module.json`:

```json
{
  "id": "animated",
  "name": "Animated stickers",
  "version": "1.0.0",
  "api": 1,
  "min_server": "0.16.0-beta",
  "python": "abi3",
  "python_min": "3.12",
  "provides": {"animated_stickers": 1},
  "description": "Plays animated and video stickers (PyAV + rlottie)"
}
```

- `id`: lowercase letters, digits and `_`; the folder's name; never changes.
- `api`: the module API it was written for. The server loads it if it supports that API.
- `min_server`: the oldest server it works on (compared like app versions: `app_version_key`).
- `python` / `python_min`: see rule 5.
- A module's `lib/` must not bring its own copies of the server's libraries (Telethon, FastAPI,
  Pillow...): the server's own come first on the path, so they would be ignored anyway.

## Module API 1 (the context)

`__init__.py` has `def setup(context)`: a plain function (modules load while the server starts, before its event loop runs; async work goes into `@context.on_start`). Notices and hooks registered in `setup` count only once it finished without an error. What `context` offers:

| | |
|---|---|
| `context.id`, `context.version`, `context.folder` | the module itself |
| `context.server_version`, `context.api` | the server's version, the API version (1) |
| `context.log(text)` | a log line: `[helloworld] text` |
| `context.router` | a FastAPI router served at `/modules/<id>/...`; token required, current account set, like every other endpoint |
| `context.provide(capability, version=1)` | announces a capability |
| `@context.notice(kind)` | the fallback text for a kind (as `@notice` in server.py) |
| `@context.hook(name)` | a function the server calls at a named point (below) |
| `context.account()`, `context.client` | the current account and its Telegram client, as server code uses them |
| `context.cache_path(name)` | a cached file: `module_cache/<id>/<session>/name`, counted by the server cache's days and size limits |
| `context.data_folder` | the module's own lasting files: `module_data/<id>/` (not cleaned) |
| `@context.on_start`, `@context.on_stop` | async functions run after the server connected / before it stops |
| `@context.filter(name)` (server 1.4.0) | a filter: every module's function for a name runs in load order, each getting the previous one's result (see `traffic-plan.md`); needs `"uses"` in module.json |
| `context.DROP`, `context.version_key(text)` (1.4.0) | what a `live.event` filter returns to drop an event; compares app versions (`"0.11.2-alpha" < "0.1.0-beta"`) |
| `@context.hook("account.make")` (1.4.0) | answer-style: `function(info) -> client or None`, asked at startup for every account whose `kind` isn't `telegram` (`info`: name, kind, settings, session); a module answers only for the kinds it owns |
| `"uses"` in module.json (1.4.0) | what the module touches (`traffic.http`, `traffic.live`): shown to the owner when it is installed, and again when an update adds one |

**Hooks** are how a module changes an existing feature instead of only adding new endpoints. The
server calls, at fixed points, the functions modules registered under that name; the first one
that answers wins, and if none does the server does what it always did. API 1 starts with very
few; each new one is added (rule 2) when a module needs it. The animated module will need
something like `sticker.animation(document, size) -> (bytes, mime) | None`.

## Server side

- **Loading** (at startup, before the accounts connect): read `modules.json` (next to .env,
  git-ignored: which installed modules are switched on), then for each installed module folder:
  check the manifest and rules 2 and 5, add `lib/` to the path, import it, call `setup(context)`.
  Each step in a `try`: any failure → the module is "failed: <reason>" and skipped.
- **Status** of each module: `on`, `off` (switched off), `failed: <reason>`.
- **What apps get:** `/server_info` gains `capabilities: {"animated_stickers": 1}` (only from
  modules that are on); a new `GET /modules` -> `[{id, name, version, provides, status}]`; and a
  `modules` notice: "Modules: Animated stickers 1.0.0 (on), Hello world 1.0.0 (failed: needs a newer
  server)" for apps that don't know the `modules` kind.
- **Shared servers:** modules are server-wide (installed for everyone); per-account data goes
  through `context.account()` and `context.cache_path`, like the server's own code.

## Maintenance mode: "x) Modules"

- List the installed modules with their status and what they provide.
- **Install from a zip**: type (or drag into the window) the zip's path. The server checks the
  `.sha256` next to it (missing: says so and asks), the manifest (and rules 2 and 5 right there,
  so an unusable module is refused before it's unpacked), then unpacks into `modules/<id>/`
  (an older version of it is replaced, after asking) and switches it on.
- Switch a module on or off; remove one (asks; its `module_data` folder only if you say so).
- Back to the main menu.
- Only the PC's owner installs modules, here: a module is code with full access to every
  account's Telegram session. Never from an app, never by itself from the internet.

## App side

- 0.15.0: nothing to change. The `modules` notice already reaches every app from 0.14.0 on.
- 0.15.1: the app learns the `modules` kind (adds it to `ServerMessages.KNOWN_KINDS`): Under the
  hood gets a "Modules" row listing `/modules`, and the notice disappears from Messages from the
  server for this app.
- Later: features a capability brings (animated stickers playing) check `capabilities` in
  `/server_info`, never a module's name.

## Releases

1. **0.15.0-beta**: Python 3.15 (done, see CLAUDE.md); the module loader and API 1; `modules.json`;
   `GET /modules`, `capabilities`, the `modules` notice; maintenance "x) Modules"; a module build
   script; a tiny demo module, **helloworld** (provides `helloworld` 1: an endpoint
   `/modules/helloworld/ping`, a notice, a log line at start), published as its own release
   (see below), not part of the server zip. Tests: the check script unchanged
   without modules; helloworld installed, on, off, removed; broken modules (bad JSON, `api` 2,
   `min_server` too high, wrong `python`, an exception in `setup`) each fail alone while the
   server starts.
2. **0.15.1-beta**: the app's Modules row (above).
3. **0.16.0-beta**: the animated stickers module, the first real one, with its hook.

## Publishing modules (owner, 2026-11-07)

- **Each module has its own GitHub releases**, apart from the server's: tag
  `module-<id>-v<version>` (e.g. `module-helloworld-v1.0.0`), title "<Name> module <version>",
  pre-release like the server's, with the module zip and its `.sha256`. So a module updates without
  a new server, and a new server doesn't force new modules. The notes say which `min_server` (and
  `api`) it needs.
- The demo module **helloworld** is published that way too, so installing a module from a real
  downloaded zip can be tried.
