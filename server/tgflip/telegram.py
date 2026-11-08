"""Keeping the Telegram connection working (MTPROTO_CLUSTER_INVALID through the proxy):
connecting, recovering, @recovering, the watchdog; and small Telegram helpers (config, forum
topics and their unread counts).
"""
import asyncio
import functools
import time
from fastapi import HTTPException
from telethon import errors, functions

from tgflip.account import account, client


# Telegram's server settings, including how long messages can be edited and deleted
# for everyone. Asked once per account; they rarely change.
async def get_telegram_config():
    acc = account()
    if acc.telegram_config is None:
        acc.telegram_config = await client(functions.help.GetConfigRequest())
    return acc.telegram_config


def can_delete_others(entity):
    """Creator, or an admin allowed to delete other people's messages."""
    rights = getattr(entity, "admin_rights", None)
    return bool(getattr(entity, "creator", False) or (rights and rights.delete_messages))


async def connect_telegram():
    """Connects from scratch; returns get_me() (None = not logged in).

    Through the proxy, Telegram sometimes answers with MTPROTO_CLUSTER_INVALID.
    A failed connect() leaves the client half-connected, and a second connect()
    would then skip setup, so retry from a clean disconnect.
    get_me() returns None only when really logged out; other errors raise and retry.
    (is_user_authorized() would report "not logged in" for any error, and cache it.)
    """
    for attempt in range(1, 6):
        try:
            await client.connect()
            return await client.get_me()
        except Exception as e:
            print(f"Connect attempt {attempt} failed: {e}")
            await client.disconnect()
            await asyncio.sleep(3)
    raise RuntimeError("Could not connect to Telegram after 5 attempts")


# The same error also strikes while running: when the proxy drops the connection,
# Telethon reconnects by itself, but then every request can fail with
# MTPROTO_CLUSTER_INVALID until we reconnect from scratch.
def is_cluster_error(e):
    return "MTPROTO_CLUSTER_INVALID" in str(e)


async def recover():
    """Reconnects the current account from scratch. Several requests failing at once trigger
    one reconnect."""
    acc = account()
    if not acc.real:
        return  # a demo account has no Telegram connection to repair
    async with acc.reconnect_lock:
        if time.monotonic() - acc.last_recovery < 10:
            return  # another request just did it
        print("Telegram connection is broken (MTPROTO_CLUSTER_INVALID); reconnecting")
        await client.disconnect()
        await connect_telegram()
        acc.last_recovery = time.monotonic()
        print("Reconnected to Telegram")


def recovering(endpoint):
    """Wraps an endpoint: on MTPROTO_CLUSTER_INVALID, reconnect and try once more.

    Requests still waiting on Telegram when another request's recover() disconnects get
    cancelled by Telethon; those wait for that recovery and retry too.
    """
    @functools.wraps(endpoint)  # keeps the signature, which FastAPI reads
    async def wrapper(*args, **kwargs):
        try:
            return await endpoint(*args, **kwargs)
        except errors.RPCError as e:
            if not is_cluster_error(e):
                raise
        except asyncio.CancelledError:
            task = asyncio.current_task()
            if task is not None and task.cancelling():
                raise  # this request itself was cancelled (e.g. the phone hung up)
            # Otherwise only Telethon's pending request was cancelled, by a reconnect
        await recover()  # waits for a recovery already under way, then skips its own
        return await endpoint(*args, **kwargs)
    return wrapper


def bad_request(e):
    """Turns a Telegram error into HTTP 400, except the broken-connection one,
    which must reach @recovering."""
    if is_cluster_error(e):
        raise e
    raise HTTPException(status_code=400, detail=str(e))


WATCHDOG_INTERVAL = 120


async def watchdog():
    """Live updates stop silently while the connection is broken, so check it
    now and then even when no requests come in."""
    while True:
        await asyncio.sleep(WATCHDOG_INTERVAL)
        try:
            await client.get_me()
        except errors.RPCError as e:
            if is_cluster_error(e):
                try:
                    await recover()
                except Exception as e2:
                    print(f"Reconnect failed: {e2}")
        except Exception:
            pass  # network trouble: Telethon keeps retrying on its own
