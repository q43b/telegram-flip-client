"""The web server: the FastAPI app, its access log, startup and shutdown, and all routes.
"""
import os
import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
import module_loader

from tgflip.account import ACCOUNTS, make_client, register_handlers, use_account
from tgflip.api import api, live_router
from tgflip.cache import clean_caches, describe_cache_setting, keep_caches_clean, move_old_caches
from tgflip.log_capture import start_log_capture
from tgflip.settings import CODENAME, VERSION, shared_server
from tgflip.telegram import connect_telegram, watchdog
from tgflip.traffic import TrafficFilter
# Loaded for what they register: the endpoints (on api and live_router) and the Telegram
# update handlers (@on), which lifespan then registers on every account
from tgflip import live, update_check  # noqa: F401
from tgflip.routes import chats, live_connections, media, reactions, server, stickers  # noqa: F401


# The web page lives next to server.py (one folder up from here), also inside the packaged server
STATIC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")


@asynccontextmanager
async def lifespan(app: FastAPI):
    start_log_capture()
    move_old_caches()
    print(f"Server cache: {describe_cache_setting()}")
    clean_caches()
    cleaner = asyncio.create_task(keep_caches_clean())
    update_checker = asyncio.create_task(update_check.keep_checking())  # in the background
    for acc in ACCOUNTS:
        use_account(acc)  # the tasks started here keep it (they copy the context)
        if not acc.real:
            # A module's kind of account (a demo account): its module made the client; the
            # server only connects it. A broken one is left out, it must not stop the others.
            if not await make_client(acc):
                continue
            register_handlers(acc)
            try:
                await acc.client.connect()
            except Exception as e:
                acc.unavailable = f"{type(e).__name__}: {e}"
                acc.client = None
                print(f'Account "{acc.name}" is unavailable: {acc.unavailable}')
                continue
            print(f'Account "{acc.name}" ({acc.kind}) is ready: a demo account, not Telegram')
            continue
        register_handlers(acc)
        if await connect_telegram() is None:
            raise RuntimeError(f"Account \"{acc.name}\" is not logged in to Telegram. "
                               "Log it in with tgflip-server --maintenancemode.")
        print(f"Account \"{acc.name}\" connected to Telegram")
        acc.watchdog = asyncio.create_task(watchdog())
    print("Shared server" if shared_server() else "Private server")
    await module_loader.start_all()
    print(f"TG Flip server {VERSION} \"{CODENAME}\" started successfully")
    yield
    await module_loader.stop_all()
    cleaner.cancel()
    update_checker.cancel()
    for acc in ACCOUNTS:
        if acc.watchdog is not None:
            acc.watchdog.cancel()
        if acc.client is not None:
            await acc.client.disconnect()


class AccessLog:
    """Tells the modules that observe "request.done" (the logging module prints a line per request,
    like uvicorn's own access log, turned off at startup: start_log_capture, plus the account it was
    for) when each request has been answered. The server itself prints and keeps nothing."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        status = 500  # unless an answer starts

        async def send_noting_status(message):
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, send_noting_status)
        finally:
            if module_loader.observers.get("request.done"):
                host, port = scope.get("client") or ("?", 0)
                module_loader.notify("request.done", {
                    "host": host, "port": port, "account": scope.get("tgflip_account"),
                    "method": scope["method"], "path": scope["path"],
                    "query": scope["query_string"].decode(), "http_version": scope["http_version"], "status": status})


app = FastAPI(lifespan=lifespan)


# Added first, so it sits inside the access log: modules' http.json filters change the answer
# before it is logged and sent (traffic.py; nothing happens while no module has one)
app.add_middleware(TrafficFilter)
app.add_middleware(AccessLog)


app.include_router(api)
app.include_router(live_router)


app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
