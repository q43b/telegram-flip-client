"""About the server itself: /server_info, /modules, the fallback channel /server_messages and
the admin channel /admin_messages.
"""
from fastapi import Header
import module_loader

from tgflip.account import account
from tgflip.api import api
from tgflip.cache import describe_cache_setting
from tgflip.notices import admin_kinds, notice, notices
from tgflip.settings import CODENAME, VERSION, shared_server


# About the server itself, for the app: its version and whether this account's app may see
# the live log (private server, or an admin on a shared one)
@api.get("/server_info")
async def server_info():
    shared = shared_server()
    return {"version": VERSION, "codename": CODENAME, "shared": shared,
            # (since 1.8.0 also: a logging module is installed, see the "logs.serve" hook)
            "log": module_loader.has_hook("logs.serve") and (not shared or account().admin),
            # What installed modules add, e.g. {"animated_stickers": 1} (since 0.15.0-beta).
            # Apps ask for these, never for a module's name (docs/modules-plan.md)
            "capabilities": module_loader.capabilities(),
            # Since 1.5.0: the account's kind ("telegram", or a module's, like "mockgram") and
            # whether it's a demo account (not Telegram): the app says so in Under the hood
            "kind": account().kind, "demo": not account().real}


# The installed modules (since 0.15.0-beta): status "on", "off" or "failed: <reason>"
@api.get("/modules")
async def installed_modules():
    return {"modules": [m.info() for m in module_loader.modules]}


@notice("server_info")
def server_info_text():
    acc = account()
    who = f'your account is "{acc.name}"' + (", an admin" if acc.admin else "")
    mode = "Shared server" if shared_server() else "Private server"
    demo = f' It is a demo account ({acc.kind}), not Telegram.' if not acc.real else ""
    return (f'TG Flip server {VERSION} "{CODENAME}". {mode}, {who}.{demo} '
            f"Server cache: {describe_cache_setting()}.")


# For apps that don't know the logging module (docs/logging-module-plan.md): without it there is no
# live log to see. Apps that know it say so in Under the hood themselves (capability live_logs).
@notice("logging")
def logging_text():
    if module_loader.has_hook("logs.serve"):
        return None
    return ("This server has no logging module, so the app's Live logs aren't available. "
            "The server's owner can install it in Maintenance mode (x, Modules).")


@notice("modules")
def modules_text():
    if not module_loader.modules:
        return None
    listed = ", ".join(f"{m.name} {m.version} ({m.status})" for m in module_loader.modules)
    return f"Modules on this server: {listed}."


def notice_texts(known, admin):
    """The texts of the notices whose kind the app doesn't know: the ordinary ones (admin=False)
    or the admin ones (admin=True). Admin notices only for admin accounts."""
    if admin and not account().admin:
        return []
    messages = []
    for kind, text_of in notices:
        if kind not in known and (kind in admin_kinds) == admin:
            text = text_of()
            if text:
                messages.append({"kind": kind, "text": text})
    return messages


def known_kinds(x_app_knows):
    return {kind.strip() for kind in x_app_knows.split(",") if kind.strip()}


@api.get("/server_messages")
async def server_messages(x_app_knows: str = Header(default="")):
    known = known_kinds(x_app_knows)
    messages = notice_texts(known, admin=False)
    # Apps that don't know the admin channel get its messages here, like any other notice
    if "admin_messages" not in known:
        messages += notice_texts(known, admin=True)
    return {"messages": messages}


# The admin channel (since 1.0.0): admin-only news, which apps that know "admin_messages"
# show right away (a notification), not only in Under the hood. Empty for other accounts.
@api.get("/admin_messages")
async def admin_messages(x_app_knows: str = Header(default="")):
    return {"messages": notice_texts(known_kinds(x_app_knows), admin=True)}
