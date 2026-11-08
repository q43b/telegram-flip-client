"""The accounts the server works for (accounts.json, docs/multi-account-plan.md): Account,
the current account (a context variable), `client`, the Telegram update handlers' registry
(@on), and the token check every request goes through.
"""
import os
import secrets
import asyncio
import copy
from contextvars import ContextVar
from fastapi import Header, HTTPException, Request
from telethon import TelegramClient
import accounts
import module_loader

from tgflip.settings import proxy_args


class Account:
    """One Telegram account the server works for: its Telegram connection and everything that
    belongs to that account alone, made from its entry in accounts.json (accounts.py). Code
    working for an account gets it with account(), and `client` below is always the current
    account's TelegramClient.

    Since 1.5.0 an account has a kind: "telegram" (a real one, the only kind before) or another
    kind (like "mockgram"), whose client a module makes at startup (make_client, the
    account.make hook): such an account is not real (`real` False), so the server doesn't
    reconnect it, watch it, or use the proxy for it, and the app is told it's a demo account."""

    def __init__(self, name, token, session, local_favorites, admin=False, kind="telegram", settings=None):
        self.name = name
        self.token = token  # the app token that means this account
        self.admin = admin  # may see the live log on a shared server
        self.kind = kind
        self.settings = settings or {}  # for the module that owns a non-telegram kind
        self.real = kind == "telegram"
        self.unavailable = None  # why a non-real account has no client (no module, or it failed)
        # None until make_client for a non-real account: then its apps get 503
        self.client = (TelegramClient(session, int(os.environ["API_ID"]), os.environ["API_HASH"], **proxy_args)
                       if self.real else None)
        self.ws_clients = set()  # its apps' live connections (several phones can share one account)
        self.telegram_config = None  # Telegram's settings as this account gets them
        self.forum_unread_cache = {}  # chat_id -> (time counted, total unread), see forum_unread
        self.sticker_documents = {}  # sticker id -> document (file references are per account)
        self.sticker_sets = {}  # pack id -> InputStickerSetID
        self.video_downloads = {}  # file path -> running download, see get_video
        self.local_favorites = local_favorites  # its local favourite stickers' file
        # Its own subfolder in every media cache (cache_path): named after its session, which,
        # unlike the account's name, never changes
        self.cache_folder = session
        self.reconnect_lock = asyncio.Lock()
        self.last_recovery = 0.0
        self.watchdog = None  # its watchdog task, while running


# The account the code is running for right now: set for each request by its token
# (current_account), for each live connection, and for each Telegram update (on). A context
# variable, so every request and task keeps its own, like a web framework's current request.
_current_account = ContextVar("account")


def account():
    return _current_account.get()


def use_account(acc):
    _current_account.set(acc)


class CurrentClient:
    """`client` everywhere: the current account's TelegramClient."""

    def __getattr__(self, name):
        return getattr(account().client, name)

    def __call__(self, *args, **kwargs):
        return account().client(*args, **kwargs)


client = CurrentClient()


# Every account in accounts.json (made from .env the first time: the account the server always had)
ACCOUNTS = [Account(**entry) for entry in accounts.load()]


# Telegram update handlers (@on below), registered on every account's client at startup;
# each runs as that account
event_handlers = []


def on(event):
    def register(handler):
        event_handlers.append((event, handler))
        return handler
    return register


async def make_client(acc):
    """A non-real account's client, asked of the modules (the account.make hook) at startup:
    function(info) -> client or None, info = {name, kind, settings, session} (never the token).
    A module answers only for the kinds it owns. True when the account got its client; else it
    stays unavailable (its apps get 503) and the server starts anyway."""
    info = {"name": acc.name, "kind": acc.kind, "settings": copy.deepcopy(acc.settings), "session": acc.cache_folder}
    made = await module_loader.call_hook("account.make", info)
    if made is None:
        acc.unavailable = f'no module that is on makes accounts of the kind "{acc.kind}"'
        print(f'Account "{acc.name}" is unavailable: {acc.unavailable}')
        return False
    acc.client = made
    return True


def register_handlers(acc):
    for event, handler in event_handlers:
        async def run_as_account(update, handler=handler):
            use_account(acc)
            await handler(update)
        acc.client.add_event_handler(run_as_account, copy.copy(event))  # a builder per client


def account_with_token(token):
    """The account this app token means, or None. Compared with every account's token."""
    found = None
    for acc in ACCOUNTS:
        if secrets.compare_digest(token.encode(), acc.token.encode()):
            found = acc
    return found


async def current_account(request: Request, x_token: str = Header(default="")):
    """Every request's first step: the token says which account it is for (401 if none), which
    is then the current account for the rest of the request. async, so it runs in the
    request's own context (a plain def would run in another thread and the choice would be lost)."""
    acc = account_with_token(x_token)
    if acc is None:
        raise HTTPException(status_code=401, detail="Bad token")
    if acc.client is None:  # a non-real account whose module isn't there (make_client)
        raise HTTPException(status_code=503, detail=f"Account unavailable: {acc.unavailable or 'not started yet'}")
    use_account(acc)
    request.scope["tgflip_account"] = acc.name  # for its line in the log (AccessLog)
    return acc
