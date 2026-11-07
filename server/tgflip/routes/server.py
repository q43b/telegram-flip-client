"""About the server itself: /server_info, /modules and the fallback channel /server_messages.
"""
from fastapi import Header
import module_loader

from tgflip.account import account
from tgflip.api import api
from tgflip.cache import describe_cache_setting
from tgflip.notices import notice, notices
from tgflip.settings import CODENAME, VERSION, shared_server


# About the server itself, for the app: its version and whether this account's app may see
# the live log (private server, or an admin on a shared one)
@api.get("/server_info")
async def server_info():
    shared = shared_server()
    return {"version": VERSION, "codename": CODENAME, "shared": shared,
            "log": not shared or account().admin,
            # What installed modules add, e.g. {"animated_stickers": 1} (since 0.15.0-beta).
            # Apps ask for these, never for a module's name (docs/modules-plan.md)
            "capabilities": module_loader.capabilities()}


# The installed modules (since 0.15.0-beta): status "on", "off" or "failed: <reason>"
@api.get("/modules")
async def installed_modules():
    return {"modules": [m.info() for m in module_loader.modules]}


@notice("server_info")
def server_info_text():
    acc = account()
    who = f'your account is "{acc.name}"' + (", an admin" if acc.admin else "")
    mode = "Shared server" if shared_server() else "Private server"
    return (f'TG Flip server {VERSION} "{CODENAME}". {mode}, {who}. '
            f"Server cache: {describe_cache_setting()}.")


@notice("modules")
def modules_text():
    if not module_loader.modules:
        return None
    listed = ", ".join(f"{m.name} {m.version} ({m.status})" for m in module_loader.modules)
    return f"Modules on this server: {listed}."


@api.get("/server_messages")
async def server_messages(x_app_knows: str = Header(default="")):
    known = {kind.strip() for kind in x_app_knows.split(",") if kind.strip()}
    messages = []
    for kind, text_of in notices:
        if kind not in known:
            text = text_of()
            if text:
                messages.append({"kind": kind, "text": text})
    return {"messages": messages}
