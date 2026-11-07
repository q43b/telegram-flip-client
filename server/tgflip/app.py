"""The web server: the FastAPI app, its access log, startup and shutdown, and all routes.
"""
import os
import asyncio
from contextlib import asynccontextmanager
from http import HTTPStatus
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
import module_loader

from tgflip.account import ACCOUNTS, register_handlers, use_account
from tgflip.api import api, live_router
from tgflip.cache import clean_caches, describe_cache_setting, keep_caches_clean, move_old_caches
from tgflip.log_capture import start_log_capture
from tgflip.settings import CODENAME, VERSION, shared_server
from tgflip.telegram import connect_telegram, watchdog
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
        register_handlers(acc)
        if await connect_telegram() is None:
            raise RuntimeError(f"Account \"{acc.name}\" is not logged in to Telegram. "
                               "Log it in with tgflip-server --maintenancemode.")
        print(f"Account \"{acc.name}\" connected to Telegram")
        acc.watchdog = asyncio.create_task(watchdog())
    if shared_server():
        print("Shared server: only admin accounts' apps may see this log")
    else:
        print("Private server: every account's app may see this log")
    await module_loader.start_all()
    print(f"TG Flip server {VERSION} \"{CODENAME}\" started successfully")
    yield
    await module_loader.stop_all()
    cleaner.cancel()
    update_checker.cancel()
    for acc in ACCOUNTS:
        acc.watchdog.cancel()
        await acc.client.disconnect()


class AccessLog:
    """One line per request, like uvicorn's own access log (turned off at startup:
    start_log_capture), plus the account it was for. uvicorn's runs before the token is read,
    so it can't say whose request it was; this one prints when the answer has gone."""

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
            host, port = scope.get("client") or ("?", 0)
            name = scope.get("tgflip_account")
            whose = f" [{name}]" if name else ""
            path = scope["path"] + (f"?{scope['query_string'].decode()}" if scope["query_string"] else "")
            try:
                phrase = HTTPStatus(status).phrase
            except ValueError:
                phrase = ""
            print(f'INFO:     {host}:{port}{whose} - "{scope["method"]} {path} '
                  f'HTTP/{scope["http_version"]}" {status} {phrase}')


app = FastAPI(lifespan=lifespan)


app.add_middleware(AccessLog)


app.include_router(api)
app.include_router(live_router)


app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
