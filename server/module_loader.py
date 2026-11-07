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

from fastapi import APIRouter, Depends, HTTPException

API_VERSIONS = (1,)  # the module APIs this server can load
MODULES_FOLDER = "modules"  # installed modules, next to .env (the working folder)
MODULES_STATE = "modules.json"  # which installed modules are switched on: {id: {"enabled": bool}}
MODULE_CACHE = "module_cache"  # module_cache/<id>/<session>/..., cleaned like the server's caches
MODULE_DATA = "module_data"  # module_data/<id>/..., the modules' own lasting files
ID = re.compile(r"[a-z0-9_]{1,40}")

modules = []  # every installed module, as Module, in the order loaded
hooks = {}  # hook name -> [functions], in the order registered


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

    def info(self):
        """As GET /modules shows it."""
        return {"id": self.id, "name": self.name, "version": self.version,
                "provides": self.provides or self.manifest.get("provides", {}), "status": self.status}


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
        # Served at /modules/<id>/..., token required and the current account set
        self.router = APIRouter()
        # Notices and hooks take effect only once setup succeeded (commit), so a module that
        # fails halfway leaves nothing behind
        self._notices = []
        self._hooks = []

    def commit(self):
        """Makes the notices and hooks count. They stay silent whenever the module isn't on
        (say it failed while starting), like its endpoints (module_is_on)."""
        module = self._module
        for kind, text_of in self._notices:
            self._server.notice(kind)(lambda text_of=text_of: text_of() if module.on else None)
        for name, function in self._hooks:
            hooks.setdefault(name, []).append((module, function))

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
        def register(function):
            self._hooks.append((name, function))
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
            print(f"Module {module.name} {module.version} is on")
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
