"""Telling the owner when a newer server version is out (docs/self-update-plan.md): at startup and
then once a day, in the background and best effort, the server asks GitHub (tgflip.updates). A
newer stable release (pre-releases are only offered in maintenance mode, when asked for) gives
one log line and the admin notice server_update, which admin apps show right away.
"""
import asyncio

from tgflip import updates
from tgflip.notices import notice
from tgflip.settings import VERSION

CHECK_EVERY = 24 * 3600  # seconds
newer = None  # the newest stable release found, or None


@notice("server_update", admin=True)
def server_update_text():
    if newer is None:
        return None
    return (f"A newer TG Flip server is out: {newer.version} (this one is {VERSION}). Update it on "
            "the PC: Maintenance mode, u) Update.")


async def check_once():
    global newer
    try:
        found = await asyncio.to_thread(updates.newest_server, VERSION, False)
    except updates.UpdateError as e:
        print(f"Couldn't check for a newer server version: {e}")
        return
    if found is not None and (newer is None or found.version != newer.version):
        print(f"A newer server version is out: {found.version}. Update it in Maintenance mode (u).")
    newer = found


async def keep_checking():
    while True:
        await check_once()
        await asyncio.sleep(CHECK_EVERY)
