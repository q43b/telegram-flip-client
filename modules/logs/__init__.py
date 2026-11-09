"""Logging: everything about the server's log that the server itself no longer does (since 1.8.0).

The server prints its own messages to its console like any program and keeps nothing. With this module:
- every line printed to the console is also kept (the last KEEP, with the time), and shown live in the app's
  Under the hood > Live logs (WebSocket /logs, handed over by the server through the "logs.serve" hook, after
  it decided who may see it: private servers every account, shared servers admins only);
- each request gets a line, like uvicorn's own access log but with the account ("request.done" observer);
- each app that connects gets a line, once per address, account and version ("app.connected" observer);
- opening and closing the Live logs screen leaves a "Started/Stopped live sharing the log" line each.
Sending lines to the app prints nothing, so watching can't make the log grow.

It declares "uses": ["server.log"], which Maintenance mode shows when the module is installed.
"""
import asyncio
import logging
import re
import sys
import time
from collections import deque
from http import HTTPStatus

KEEP = 500
ANSI_COLORS = re.compile(r"\x1b\[[0-9;]*m")  # uvicorn colors its words in a terminal

lines = deque(maxlen=KEEP)
listeners = set()  # one asyncio.Queue per open /logs connection
loop = None  # the server's event loop, set at start


def record(text):
    line = f"{time.strftime('%H:%M:%S')} {ANSI_COLORS.sub('', text).rstrip()}"
    lines.append(line)
    if loop is not None and listeners:
        loop.call_soon_threadsafe(notify_listeners, line)  # safe from any thread


def notify_listeners(line):
    for queue in list(listeners):
        try:
            queue.put_nowait(line)
        except asyncio.QueueFull:
            pass  # that viewer is far behind; it misses a line rather than holding up the server


class Tee:
    """Passes console output on unchanged and records each finished line."""

    def __init__(self, stream):
        self.stream = stream
        self.partial = ""

    def write(self, text):
        self.stream.write(text)
        self.partial += text
        while "\n" in self.partial:
            line, self.partial = self.partial.split("\n", 1)
            if line.strip():
                record(line)
        return len(text)

    def flush(self):
        self.stream.flush()

    def __getattr__(self, name):  # encoding, isatty, ... as the real console
        return getattr(self.stream, name)


class Recorder(logging.Handler):
    """For uvicorn's loggers when they write to the console directly (see start)."""

    def emit(self, record_):
        record(f"{record_.levelname}: {record_.getMessage()}")


def setup(context):
    if sys.stdout is not None and not isinstance(sys.stdout, Tee):
        sys.stdout = Tee(sys.stdout)
    if sys.stderr is not None and not isinstance(sys.stderr, Tee):
        sys.stderr = Tee(sys.stderr)
    seen_apps = set()  # (address [account], app version) already printed, so reconnects don't repeat it

    @context.on_start
    async def start():
        """uvicorn is set up after the server is imported when run_server.py starts it, so it already
        writes through Tee; `uvicorn server:app` sets its logging up first, with the real console, so
        its loggers get a Recorder instead (never both: no doubled lines)."""
        global loop
        loop = asyncio.get_running_loop()
        logger = logging.getLogger("uvicorn")
        if not any(isinstance(getattr(h, "stream", None), Tee) for h in logger.handlers):
            logger.addHandler(Recorder())
        print("Logging module: the log is kept and may be shown in the app's Live logs "
              "(private server: every account; shared server: admins)")

    @context.observe("request.done")
    def request_done(info):
        name = info["account"]
        whose = f" [{name}]" if name else ""
        path = info["path"] + (f"?{info['query']}" if info["query"] else "")
        try:
            phrase = HTTPStatus(info["status"]).phrase
        except ValueError:
            phrase = ""
        print(f'INFO:     {info["host"]}:{info["port"]}{whose} - "{info["method"]} {path} '
              f'HTTP/{info["http_version"]}" {info["status"]} {phrase}')

    @context.observe("app.connected")
    def app_connected(info):
        host = f'{info["host"]} [{info["account"]}]' if info["account"] else info["host"]
        key = (host, info["version"])
        if key in seen_apps:
            return
        seen_apps.add(key)
        name = f'TG Flip {info["version"]}' if info["version"] else "an app older than 0.7.1"
        print(f"App connected from {host}: {name}")

    @context.hook("logs.serve")
    async def serve(ws):
        """First the kept lines (one JSON list), then each new line as it is printed (a list of one)."""
        host = ws.client.host if ws.client else "an app"
        acc = context.account()
        who = f"{host} [{acc.name}]" if acc is not None else host
        queue = asyncio.Queue(maxsize=1000)
        sender = None
        try:
            # Printed before this viewer listens, so it arrives once, as the last kept line
            print(f"Started live sharing the log with {who}")
            listeners.add(queue)
            await ws.send_json(list(lines))

            async def send_new_lines():
                while True:
                    await ws.send_json([await queue.get()])

            sender = asyncio.create_task(send_new_lines())
            while True:
                await ws.receive_text()  # only to notice when the app closes the screen
        except Exception:
            pass  # the app went away (a WebSocketDisconnect, or the connection dropped)
        finally:
            if queue in listeners:
                listeners.discard(queue)
                print(f"Stopped live sharing the log with {who}")
            if sender is not None:
                sender.cancel()
        return True

    context.provide("live_logs", 1)
