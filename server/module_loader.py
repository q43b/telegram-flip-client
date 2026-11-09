"""Server modules (docs/modules-plan.md): optional features, each in its own folder in modules/
next to .env, loaded at startup behind module API 1 (ModuleContext).

A module only uses its context, never the server's insides, and the context only grows in later
servers, so modules written for 0.15.x keep working. Anything wrong with one module (manifest,
API, server or Python version, an error in its code) disables only that module, with one log line.
"""
import asyncio
import importlib.util
import inspect
import json
import os
import re
import sys
import time

from fastapi import APIRouter, Depends, HTTPException

API_VERSIONS = (1,)  # the module APIs this server can load
MODULES_FOLDER = "modules"  # installed modules, next to .env (the working folder)
MODULES_STATE = "modules.json"  # which installed modules are switched on: {id: {"enabled": bool}}
MODULE_CACHE = "module_cache"  # module_cache/<id>/<session>/..., cleaned like the server's caches
MODULE_DATA = "module_data"  # module_data/<id>/..., the modules' own lasting files
ID = re.compile(r"[a-z0-9_]{1,40}")

modules = []  # every installed module, as Module, in the order loaded
hooks = {}  # hook name -> [functions], in the order registered
filters = {}  # filter name -> [(module, function)], in load order (since 1.4.0, see ModuleContext.filter)

# What a module's "uses" may name (module.json), shown when it is installed or updated: a module
# runs inside the server, so this is for the owner's eyes, but a module can only register what it
# declared (FILTER_USES). Unknown names are shown as they are.
KNOWN_USES = {
    "traffic.http": "read and change the answers the server gives to apps",
    "traffic.live": "read and change the live events the server sends to apps",
    "telegram.transport": "act as the Telegram connection of accounts of its own kind (a made-up Telegram instead of the real one)",
    "server.log": "see the server's console output and every request made to it, with the addresses and accounts they come from, and keep and share them",
}
# filter name -> the use it needs
FILTER_USES = {"http.json": "traffic.http", "live.event": "traffic.live"}
# Since 1.8.0: observers (see ModuleContext.observe) and the hooks that hand a connection over, and the use each needs
OBSERVER_USES = {"request.done": "server.log", "app.connected": "server.log"}
HOOK_USES = {"logs.serve": "server.log"}
observers = {}  # observer name -> [(module, function)], in load order (since 1.8.0)


class _Drop:
    """DROP: what a live.event filter returns to keep that event from being sent."""

    def __repr__(self):
        return "DROP"


DROP = _Drop()


class Traffic:
    """What a filter is told about what it's looking at (module API 1, since server 1.4.0). New
    attributes may be added later, never removed. Not every attribute fits every filter (None):
    - path, method, status: the request and its answer (http.json)
    - account: the name of the account (accounts.json)
    - host: the app's address
    - client_version: the app's version ("0.11.2-alpha"), or None when unknown (an app that
      doesn't tell: before 0.7.1, or one that hasn't opened a live connection yet)
    """

    def __init__(self, path=None, method=None, status=None, account=None, host=None, client_version=None):
        self.path = path
        self.method = method
        self.status = status
        self.account = account
        self.host = host
        self.client_version = client_version


class ModuleError(Exception):
    """Why a module can't be used, in words for the log and the menu."""


class Module:
    def __init__(self, folder):
        self.folder = folder
        self.id = os.path.basename(folder)
        self.manifest = {}
        self.status = "off"  # "on", "off" or "failed: <reason>"
        self.provides = {}
        self.on_start = []
        self.on_stop = []

    @property
    def on(self):
        return self.status == "on"

    @property
    def name(self):
        return self.manifest.get("name") or self.id

    @property
    def version(self):
        return self.manifest.get("version", "?")

    @property
    def uses(self):
        return self.manifest.get("uses", [])

    def info(self):
        """As GET /modules shows it."""
        return {"id": self.id, "name": self.name, "version": self.version,
                "provides": self.provides or self.manifest.get("provides", {}), "status": self.status,
                "uses": self.uses}


def python_tag():
    return f"cp{sys.version_info.major}{sys.version_info.minor}"


def python_tuple(text):
    return tuple(int(part) for part in str(text).split("."))


def read_manifest(folder, server_version, version_key):
    """The module's module.json, checked against this server. Raises ModuleError."""
    try:
        with open(os.path.join(folder, "module.json"), encoding="utf-8") as f:
            text = f.read()
    except FileNotFoundError:
        raise ModuleError("no module.json")
    return check_manifest(text, os.path.basename(folder), server_version, version_key)


def check_manifest(text, folder_name, server_version, version_key):
    """A module.json's text, checked against this server (also before installing a zip)."""
    try:
        manifest = json.loads(text)
    except ValueError as e:
        raise ModuleError(f"module.json is not valid JSON ({e})")
    if not isinstance(manifest, dict):
        raise ModuleError("module.json is not an object")
    module_id = manifest.get("id")
    if not isinstance(module_id, str) or not ID.fullmatch(module_id):
        raise ModuleError("its id must be lowercase letters, digits and _")
    if module_id != folder_name:
        raise ModuleError(f"its id {module_id!r} doesn't match its folder")
    if manifest.get("api") not in API_VERSIONS:
        raise ModuleError(f"made for module API {manifest.get('api')}, this server has "
                          f"{', '.join(map(str, API_VERSIONS))}")
    min_server = manifest.get("min_server")
    if min_server is not None:
        needed = version_key(min_server)
        if needed is None:
            raise ModuleError(f"min_server {min_server!r} is not a version")
        if needed > version_key(server_version):
            raise ModuleError(f"needs server {min_server} or newer")
    python = manifest.get("python", "any")
    if python == "abi3":
        if "python_min" in manifest and python_tuple(manifest["python_min"]) > sys.version_info[:2]:
            raise ModuleError(f"needs Python {manifest['python_min']} or newer")
    elif python != "any" and python != python_tag():
        raise ModuleError(f"built for {python}, this server runs {python_tag()}")
    uses = manifest.get("uses", [])
    if not isinstance(uses, list) or not all(isinstance(u, str) for u in uses):
        raise ModuleError('"uses" must be a list of names, like ["traffic.http"]')
    provides = manifest.get("provides", {})
    if not isinstance(provides, dict) or not all(
            isinstance(k, str) and isinstance(v, int) for k, v in provides.items()):
        raise ModuleError('"provides" must be like {"capability": 1}')
    return manifest


def read_state():
    """modules.json: {id: {"enabled": bool}}. A module missing from it is on."""
    try:
        with open(MODULES_STATE, encoding="utf-8") as f:
            state = json.load(f)
        return state if isinstance(state, dict) else {}
    except (FileNotFoundError, ValueError):
        return {}


def write_state(state):
    temporary = MODULES_STATE + ".new"
    with open(temporary, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)
    os.replace(temporary, MODULES_STATE)


def switched_off_ids():
    return {k for k, v in read_state().items() if isinstance(v, dict) and not v.get("enabled", True)}


class ModuleContext:
    """Module API 1: everything a module may use. Only ever grows (docs/modules-plan.md)."""
    api = 1

    def __init__(self, server, module):
        self._server = server
        self._module = module
        self.id = module.id
        self.version = module.version
        self.folder = os.path.abspath(module.folder)
        self.server_version = server.VERSION
        self.DROP = DROP  # what a live.event filter returns to drop the event
        self.version_key = server.app_version_key  # compares app versions: "0.11.2-alpha" < "0.1.0-beta"
        # Served at /modules/<id>/..., token required and the current account set
        self.router = APIRouter()
        # Notices and hooks take effect only once setup succeeded (commit), so a module that
        # fails halfway leaves nothing behind
        self._notices = []
        self._hooks = []
        self._filters = []
        self._observers = []

    def commit(self):
        """Makes the notices and hooks count. They stay silent whenever the module isn't on
        (say it failed while starting), like its endpoints (module_is_on)."""
        module = self._module
        for kind, text_of in self._notices:
            self._server.notice(kind)(lambda text_of=text_of: text_of() if module.on else None)
        for name, function in self._hooks:
            hooks.setdefault(name, []).append((module, function))
        for name, function in self._filters:
            filters.setdefault(name, []).append((module, function))
        for name, function in self._observers:
            observers.setdefault(name, []).append((module, function))

    def log(self, text):
        print(f"[{self.id}] {text}")

    def provide(self, capability, version=1):
        self._module.provides[capability] = version

    def notice(self, kind):
        """The fallback text for a kind, like @notice in server.py."""
        def register(text_of):
            self._notices.append((kind, text_of))
            return text_of
        return register

    def hook(self, name):
        """A hook: the first module's answer that is not None wins (call_hook). Hooks that hand a
        live connection or console output over need the use named in HOOK_USES.
        - "logs.serve" (server.log, since 1.8.0): async function(ws) -> True. The server's /logs
          connection (a WebSocket whose app is known and may see the log) is handed over; the
          function sends the log until the app goes away. Without a module that answers it, /logs
          only tells apps that the server has no logging module."""
        needed = HOOK_USES.get(name)
        if needed is not None and needed not in self._module.uses:
            raise ModuleError(f'the hook {name} needs "uses": ["{needed}"] in module.json')

        def register(function):
            self._hooks.append((name, function))
            return function
        return register

    def observe(self, name):
        """An observer (since server 1.8.0): the server tells every module that observes this name what
        happened, after the fact; the function returns nothing and can't change anything. A failing one
        is skipped. The module must say it uses server.log in module.json (shown to the owner).
        - "request.done": function(info) after each HTTP request is answered; info has host, port,
          account (name or None), method, path, query, http_version and status.
        - "app.connected": function(info) when an app opens a live connection; info has host, account
          and version (the app's, or None for apps older than 0.7.1)."""
        if name not in OBSERVER_USES:
            raise ModuleError(f"there is nothing to observe called {name!r}")
        needed = OBSERVER_USES[name]
        if needed not in self._module.uses:
            raise ModuleError(f'observing {name} needs "uses": ["{needed}"] in module.json')

        def register(function):
            self._observers.append((name, function))
            return function
        return register

    def filter(self, name):
        """A filter (since server 1.4.0): unlike a hook, whose first answer wins, every module's
        filter for a name runs, one after the other in load order, each getting what the one
        before made. It returns the new value, or None to leave it as it is (changing the value in
        place works too). A filter that fails is skipped. The module's module.json must say it
        uses what the filter touches ("uses", shown to the owner when installing).
        - "http.json" (traffic.http): function(info, data) -> data. Runs on every JSON answer
          of the server's endpoints (not files), after the endpoint; info is a Traffic.
        - "live.event" (traffic.live): function(info, event) -> event, or context.DROP. Runs on
          every live event, once for each app connection it goes to (info.client_version says
          which app), on its own copy."""
        if name not in FILTER_USES:
            raise ModuleError(f"there is no filter {name!r}")
        needed = FILTER_USES[name]
        if needed not in self._module.uses:
            raise ModuleError(f'the filter {name} needs "uses": ["{needed}"] in module.json')

        def register(function):
            self._filters.append((name, function))
            return function
        return register

    def account(self):
        return self._server.account()

    @property
    def client(self):
        return self._server.client

    def cache_path(self, name):
        """A cached file of the current account: module_cache/<id>/<session>/<name>; the
        server cache's days and size limits count it."""
        folder = os.path.join(MODULE_CACHE, self.id, self._server.account().cache_folder)
        os.makedirs(folder, exist_ok=True)
        return os.path.join(folder, name)

    @property
    def data_folder(self):
        folder = os.path.abspath(os.path.join(MODULE_DATA, self.id))
        os.makedirs(folder, exist_ok=True)
        return folder

    def on_start(self, function):
        self._module.on_start.append(function)
        return function

    def on_stop(self, function):
        self._module.on_stop.append(function)
        return function


def load_all(server):
    """Loads every installed module that is switched on (server.load_modules: at import, or in
    maintenance mode only when the server starts, so the menu can replace module files)."""
    try:
        load_folders(server)
    finally:
        # The web page at / catches every path, so it must stay the last route
        routes = server.app.router.routes
        for route in [r for r in routes if getattr(r, "name", None) == "static"]:
            routes.remove(route)
            routes.append(route)


def load_folders(server):
    if not os.path.isdir(MODULES_FOLDER):
        return
    switched_off = switched_off_ids()
    for name in sorted(os.listdir(MODULES_FOLDER)):
        folder = os.path.join(MODULES_FOLDER, name)
        if not os.path.isdir(folder):
            continue
        module = Module(folder)
        modules.append(module)
        try:
            module.manifest = read_manifest(folder, server.VERSION, server.app_version_key)
            if module.id in switched_off:
                continue
            load(server, module)
            module.status = "on"
            uses = f" (uses {', '.join(module.uses)})" if module.uses else ""
            print(f"Module {module.name} {module.version} is on{uses}")
        except Exception as e:  # anything at all: only this module stops
            module.status = f"failed: {e if isinstance(e, ModuleError) else f'{type(e).__name__}: {e}'}"
            print(f"Module {name} {module.status}")


def load(server, module):
    lib = os.path.join(os.path.abspath(module.folder), "lib")
    if os.path.isdir(lib) and lib not in sys.path:
        sys.path.append(lib)  # after the server's own libraries, so those always win
    code_file = os.path.join(module.folder, "__init__.py")
    if not os.path.isfile(code_file):
        raise ModuleError("no __init__.py")
    spec = importlib.util.spec_from_file_location(
        f"tgflip_module_{module.id}", code_file, submodule_search_locations=[module.folder])
    code = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = code
    spec.loader.exec_module(code)
    setup = getattr(code, "setup", None)
    if setup is None:
        raise ModuleError("its __init__.py has no setup(context)")
    context = ModuleContext(server, module)
    result = setup(context)
    if hasattr(result, "__await__"):
        result.close()
        raise ModuleError("setup must not be async (use @context.on_start)")
    context.commit()

    def module_is_on():
        if not module.on:
            raise HTTPException(status_code=503, detail=f"Module {module.id} {module.status}")

    server.app.include_router(context.router, prefix=f"/modules/{module.id}",
                              dependencies=[Depends(server.current_account), Depends(module_is_on)])


def capabilities():
    """capability -> version, from the modules that are on right now."""
    found = {}
    for module in modules:
        if module.on:
            found.update(module.provides)
    return found


async def start_all():
    """After the accounts connected: each module's on_start functions."""
    for module in modules:
        if module.status != "on":
            continue
        for function in module.on_start:
            try:
                await function()
            except Exception as e:
                module.status = f"failed: on start: {type(e).__name__}: {e}"
                print(f"Module {module.id} {module.status}")
                break


async def stop_all():
    for module in modules:
        for function in module.on_stop:
            try:
                await function()
            except Exception as e:
                print(f"Module {module.id}: on stop: {type(e).__name__}: {e}")


def has_filter(name):
    """True if a module that is on has a filter of this name."""
    return any(module.on for module, _ in filters.get(name, []))


_filter_failed = {}  # (module id, filter name) -> when it was last reported


async def call_filters(name, value, info):
    """value through every filter of this name that's on, in order (see ModuleContext.filter);
    DROP as soon as one answers DROP. A failing filter is skipped and reported at most once a
    minute, so a filter that fails on every request doesn't fill the log."""
    for module, function in filters.get(name, []):
        if not module.on:
            continue
        try:
            answer = function(info, value)
            if hasattr(answer, "__await__"):
                answer = await answer
        except Exception as e:
            key = (module.id, name)
            if time.monotonic() - _filter_failed.get(key, -60) >= 60:
                _filter_failed[key] = time.monotonic()
                print(f"Filter {name} of module {module.id} failed: {type(e).__name__}: {e}")
            continue
        if answer is DROP:
            return DROP
        if answer is not None:
            value = answer
    return value


_observer_failed = {}  # (module id, observer name) -> when it was last reported


def notify(name, info):
    """Tells every module that is on and observes this name (ModuleContext.observe). Cheap and
    silent while none does; a failing observer is reported at most once a minute."""
    for module, function in observers.get(name, []):
        if not module.on:
            continue
        try:
            function(info)
        except Exception as e:
            key = (module.id, name)
            if time.monotonic() - _observer_failed.get(key, -60) >= 60:
                _observer_failed[key] = time.monotonic()
                print(f"Observer {name} of module {module.id} failed: {type(e).__name__}: {e}")


def has_hook(name):
    """True if a module that is on answers this hook."""
    return any(module.on for module, _ in hooks.get(name, []))


def hook_maker(name):
    """"<id>-<version>" of the first module that is on and answers this hook, or None. For
    naming what the server caches from a hook, so a module update makes it again."""
    for module, _ in hooks.get(name, []):
        if module.on:
            return f"{module.id}-{module.version}"
    return None


async def call_hook(name, *args, thread=False):
    """The first answer (not None) of the functions registered for this hook, or None: then the
    server does what it always did. A failing hook is skipped. thread=True (for hooks that do
    heavy work, like converting a sticker) runs plain functions in a worker thread, so the
    server keeps answering meanwhile; async ones are awaited as they are."""
    for module, function in hooks.get(name, []):
        if not module.on:
            continue
        try:
            if thread and not inspect.iscoroutinefunction(function):
                answer = await asyncio.to_thread(function, *args)
            else:
                answer = function(*args)
            if hasattr(answer, "__await__"):
                answer = await answer
        except Exception as e:
            print(f"Hook {name} of module {module.id} failed: {type(e).__name__}: {e}")
            continue
        if answer is not None:
            return answer
    return None


def cache_roots():
    """module_cache/<id>: one cache root per module, with a folder per account inside."""
    if not os.path.isdir(MODULE_CACHE):
        return []
    return [os.path.join(MODULE_CACHE, name) for name in os.listdir(MODULE_CACHE)]
