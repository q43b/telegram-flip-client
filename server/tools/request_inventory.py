"""Which Telegram requests the server makes, and for what (the mockgram inventory, docs/traffic-plan.md).

Starts the real server from source with one patch: every request that goes through Telethon's
_call is noted as (server function that caused it | Telethon method the server called | request
type names | result type name) with a count. Names only: no content, no ids, no text. Run the
read-only flows against it (tools/check_server.py as each account, the app), stop it, read the
JSON. Writes (send, edit, delete...) are not exercised by those flows: their requests are read
from Telethon's source, listed in the plan.

    python server/tools/request_inventory.py [output.json]      (default: request_inventory.json)

Needs the server's own .env and accounts, like run_server.py; connects to Telegram for real.
"""
import json
import os
import sys

SERVER = os.path.dirname(os.path.dirname(os.path.abspath(__file__))).replace("\\", "/")
OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "request_inventory.json")
os.chdir(SERVER)
sys.path.insert(0, SERVER)
sys.argv = ["run_server.py"]

from telethon.client import users

original = users.UserMethods._call
seen = {}  # "server function | telethon method | requests | result" -> count
calls = 0


def where():
    """The server function and the Telethon method (the outermost Telethon frame under it)."""
    frame = sys._getframe(2)
    telethon_method = None
    while frame is not None:
        path = frame.f_code.co_filename.replace("\\", "/")
        name = frame.f_code.co_name
        if "/telethon/" in path:
            if name not in ("__call__", "_call", "wrapper"):
                telethon_method = name
        elif path.startswith(SERVER) and "/venv/" not in path:
            rel = path[len(SERVER) + 1:]
            return f"{rel}:{name}", telethon_method or "(raw request)"
        frame = frame.f_back
    return "(telethon internal)", telethon_method or "(raw request)"


def save():
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(seen, f, indent=1, sort_keys=True)


async def logged(self, sender, request, *args, **kwargs):
    global calls
    names = [type(r).__name__ for r in (request if isinstance(request, (list, tuple)) else [request])]
    server_function, method = where()
    key_start = f"{server_function} | {method} | {'+'.join(names)}"
    try:
        result = await original(self, sender, request, *args, **kwargs)
    except Exception as e:
        key = f"{key_start} | ERROR {type(e).__name__}"
        new = key not in seen
        seen[key] = seen.get(key, 0) + 1
        if new:
            save()
        raise
    kind = type(result).__name__
    if isinstance(result, (list, tuple)):
        kind = "list[" + ",".join(sorted({type(r).__name__ for r in result})) + "]"
    key = f"{key_start} | {kind}"
    new = key not in seen
    seen[key] = seen.get(key, 0) + 1
    calls += 1
    if new or calls % 10 == 0:
        save()
    return result


users.UserMethods._call = logged

import run_server  # noqa: E402

run_server.main()
