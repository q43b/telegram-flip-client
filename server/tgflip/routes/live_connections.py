"""The live connections: /ws (live events for the apps) and /logs (the live log).
"""
import asyncio
from fastapi import WebSocket, WebSocketDisconnect

from tgflip import clients
from tgflip.account import _current_account, account, account_with_token, use_account
from tgflip.api import live_router
import module_loader
from tgflip.settings import app_version_key, shared_server


def log_app(ws: WebSocket):
    """An app opened a live connection: tells the modules that observe "app.connected" (the logging
    module prints a line once per address, account and version). Apps from 0.7.1 on send
    x-app-version when they open the live connection."""
    acc = _current_account.get(None)
    module_loader.notify("app.connected", {
        "host": ws.client.host if ws.client else "an app", "account": acc.name if acc is not None else None,
        "version": ws.headers.get("x-app-version")})


@live_router.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    try:
        if not await ws_token_ok(ws):
            return
        log_app(ws)
        remember_app(ws)
        account().ws_clients.add(ws)
        while True:
            await ws.receive_text()  # keeps the connection open
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        acc = _current_account.get(None)
        if acc is not None:
            acc.ws_clients.discard(ws)


def remember_app(ws):
    """The app's version, for modules' filters: on the connection itself (live.event), and per
    address for the plain requests that follow from it (clients.py)."""
    version = ws.headers.get("x-app-version")
    ws.state.app_version = version
    clients.note(account().name, ws.client.host if ws.client else "?", version)


def client_host(ws):
    """The app's address and, once its token is known, its account: "192.168.1.5 [main]"."""
    host = ws.client.host if ws.client else "an app"
    acc = _current_account.get(None)
    return f"{host} [{acc.name}]" if acc is not None else host


async def ws_token_ok(ws):
    """The first message must be the token, within 5 seconds (it is not sent in the URL, so it
    never ends up in logs); its account becomes the connection's current account. Closes the
    connection when it is wrong."""
    first = await asyncio.wait_for(ws.receive_text(), timeout=5)
    acc = account_with_token(first)
    if acc is not None and acc.client is not None:  # (not an unavailable demo account)
        use_account(acc)
        return True
    await ws.close(code=1008)
    return False


# Apps before this can't tell a private server's log from a shared one's, so they get only this
# line instead of the log
LOGS_MIN_APP = "0.13.0-beta"


LOGS_OLD_APP_LINE = "Client too old to verify whether logs are private or shared"


# What an app gets as the "log" when the server has no logging module (since 1.8.0): apps that don't know
# about the module show it like any log line, and stay quiet instead of reconnecting
LOGS_MODULE_LINE = ("This server version needs the logging module for its live log, and it isn't installed. "
                    "The server's owner can install it in Maintenance mode (x, Modules).")


LOG_PRIVATE = 4003  # /logs close code: a shared server, and this account is not an admin


def app_is_older_than(ws, version):
    key = app_version_key(ws.headers.get("x-app-version"))
    return key is None or key < app_version_key(version)


# The app's "under the hood" screen. The server decides who may see a log (apps too old to tell a
# private server from a shared one, and non-admins on a shared one, don't), but has none of its own
# (since 1.8.0): the logging module answers the "logs.serve" hook and sends the kept lines and then each
# new one. Without it the app gets one line saying so (LOGS_MODULE_LINE), which apps from before this
# change show like any log line, and the connection stays open so they don't keep reconnecting.
@live_router.websocket("/logs")
async def logs_endpoint(ws: WebSocket):
    await ws.accept()
    try:
        if not await ws_token_ok(ws):
            return
        remember_app(ws)
        if not module_loader.has_hook("logs.serve"):
            await ws.send_json([LOGS_MODULE_LINE])
            while True:
                await ws.receive_text()
        if app_is_older_than(ws, LOGS_MIN_APP):
            await ws.send_json([LOGS_OLD_APP_LINE])
            while True:
                await ws.receive_text()
        if shared_server() and not account().admin:
            # 4003 tells the app the log is private here, so it says so and stops trying
            await ws.close(code=LOG_PRIVATE)
            return
        await module_loader.call_hook("logs.serve", ws)
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
