"""The live connections: /ws (live events for the apps) and /logs (the live log).
"""
import asyncio
from fastapi import WebSocket, WebSocketDisconnect

from tgflip.account import _current_account, account, account_with_token, use_account
from tgflip.api import live_router
from tgflip.log_capture import log_lines, log_listeners
from tgflip.settings import app_version_key, shared_server


seen_apps = set()  # (address [account], app version) already printed, so reconnects don't repeat it


def log_app(ws: WebSocket):
    # Apps from 0.7.1 on send x-app-version when they open the live connection
    version = ws.headers.get("x-app-version")
    host = client_host(ws)  # with its account
    if (host, version) in seen_apps:
        return
    seen_apps.add((host, version))
    name = f"TG Flip {version}" if version else "an app older than 0.7.1"
    print(f"App connected from {host}: {name}")


@live_router.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    try:
        if not await ws_token_ok(ws):
            return
        log_app(ws)
        account().ws_clients.add(ws)
        while True:
            await ws.receive_text()  # keeps the connection open
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        acc = _current_account.get(None)
        if acc is not None:
            acc.ws_clients.discard(ws)


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
    if acc is not None:
        use_account(acc)
        return True
    await ws.close(code=1008)
    return False


# Apps before this can't tell a private server's log from a shared one's, so they get only this
# line instead of the log
LOGS_MIN_APP = "0.13.0-beta"


LOGS_OLD_APP_LINE = "Client too old to verify whether logs are private or shared"


LOG_PRIVATE = 4003  # /logs close code: a shared server, and this account is not an admin


def app_is_older_than(ws, version):
    key = app_version_key(ws.headers.get("x-app-version"))
    return key is None or key < app_version_key(version)


# The app's "under the hood" screen: first the kept lines (one JSON list), then each new line
# as it is printed (a list of one). Nothing is logged per line sent; opening and closing the
# screen each leave one line ("Started/Stopped live sharing the log"), so every viewing shows
# as a pair in the log.
@live_router.websocket("/logs")
async def logs_endpoint(ws: WebSocket):
    await ws.accept()
    queue = asyncio.Queue(maxsize=1000)
    sender = None
    try:
        if not await ws_token_ok(ws):
            return
        if app_is_older_than(ws, LOGS_MIN_APP):
            # Stays open, so the app doesn't keep reconnecting, but nothing more is sent
            await ws.send_json([LOGS_OLD_APP_LINE])
            print(f"Not sharing the log with {client_host(ws)}: the app is older than {LOGS_MIN_APP}")
            while True:
                await ws.receive_text()
        if shared_server() and not account().admin:
            # 4003 tells the app the log is private here, so it says so and stops trying
            print(f"Not sharing the log with {client_host(ws)}: shared server, not an admin")
            await ws.close(code=LOG_PRIVATE)
            return
        # Printed before this viewer listens, so it arrives once, as the last kept line
        print(f"Started live sharing the log with {client_host(ws)}")
        log_listeners.add(queue)
        await ws.send_json(list(log_lines))

        async def send_new_lines():
            while True:
                await ws.send_json([await queue.get()])

        sender = asyncio.create_task(send_new_lines())
        while True:
            await ws.receive_text()  # only to notice when the app closes the screen
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        if queue in log_listeners:
            log_listeners.discard(queue)
            print(f"Stopped live sharing the log with {client_host(ws)}")
        if sender is not None:
            sender.cancel()
