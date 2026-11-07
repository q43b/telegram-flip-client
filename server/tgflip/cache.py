"""The media the server keeps for the apps (one folder per account and kind): where files go
(cache_path), marking them used, and cleaning by days and size.
"""
import os
import asyncio
import time
import module_loader

from tgflip.account import ACCOUNTS, account


# Profile photos, shrunk to the size the app asked for, saved as
# <chat id>_<photo id>_<size>.jpg so each is fetched from Telegram only once.
# Next to the session and .env (the working folder), also for the packaged server.
PHOTO_CACHE = "photo_cache"


IMAGE_CACHE = "image_cache"


VIDEO_CACHE = "video_cache"


def cache_path(folder, name):
    """Where a cached media file lives: in the current account's own subfolder, so one account
    can never get another's pictures by guessing ids (message ids in private chats are per
    account anyway). The one place that builds these paths."""
    folder = os.path.join(folder, account().cache_folder)
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, name)


def move_old_caches():
    """Before 0.13.0 the caches had no account subfolders, and their files all belonged to the
    first account; they move into its subfolder, so nothing has to be downloaded again."""
    first = ACCOUNTS[0].cache_folder
    for folder in (PHOTO_CACHE, IMAGE_CACHE, VIDEO_CACHE, STICKER_CACHE):
        if not os.path.isdir(folder):
            continue
        files = [f for f in os.listdir(folder) if os.path.isfile(os.path.join(folder, f))]
        if not files:
            continue
        os.makedirs(os.path.join(folder, first), exist_ok=True)
        for f in files:
            os.replace(os.path.join(folder, f), os.path.join(folder, first, f))
        print(f"Moved {len(files)} cached files from {folder}/ to {folder}/{first}/")


# How long the server keeps the media files it fetched for the apps: SERVER_CACHE in .env,
# "forever" (the default), a number of days since a file was last used, or "off" (each file is
# deleted once it has been sent). Set at first-run setup or in maintenance mode. Read each
# time, like SERVER_MODE.
CACHE_DAYS_MAX = 100_000


CACHE_MB_MAX = 10_000_000


MB = 1024 * 1024


CACHE_CLEAN_EVERY = 6 * 3600  # seconds


CACHE_OFF_GRACE = 600  # with the cache off, leftovers this old (seconds) go at the next cleaning


def cache_days():
    """None = forever, 0 = off, otherwise days since last use."""
    value = (os.environ.get("SERVER_CACHE") or "forever").strip().lower()
    if value == "forever":
        return None
    if value == "off":
        return 0
    if value.isdigit() and 1 <= int(value) <= CACHE_DAYS_MAX:
        return int(value)
    raise RuntimeError(f"SERVER_CACHE in .env must be forever, off or 1-{CACHE_DAYS_MAX} (days), not {value!r}")


def cache_mb():
    """SERVER_CACHE_MB in .env: the most the whole cache may take, in MB, or None (empty, the
    default) for no limit. Each account may use an equal share of it (cache_share_mb)."""
    value = (os.environ.get("SERVER_CACHE_MB") or "").strip().lower()
    if value in ("", "none"):
        return None
    if value.isdigit() and 1 <= int(value) <= CACHE_MB_MAX:
        return int(value)
    raise RuntimeError(f"SERVER_CACHE_MB in .env must be empty (no limit) or 1-{CACHE_MB_MAX}, not {value!r}")


def cache_share_mb():
    """Each account's share of the size limit (None = no limit)."""
    total = cache_mb()
    return None if total is None else total / len(ACCOUNTS)


def describe_cache_setting():
    days = cache_days()
    if days == 0:
        return "off (media files are deleted once sent)"
    if days is None:
        text = "media files are kept forever"
    else:
        text = f"media files are kept for {days} day{'s' if days != 1 else ''} since last used"
    total = cache_mb()
    if total is not None:
        text += f", at most {total} MB in all ({cache_share_mb():.0f} MB per account)"
    return text


def in_cache(path):
    """True if the file is cached; marks it as just used (its modified time), which is what
    the cleaning goes by."""
    if not os.path.exists(path):
        return False
    try:
        os.utime(path)
    except OSError:
        pass
    return True


def sent(path):
    """Called once a cached file has been sent: with the cache off, it goes."""
    if cache_days() == 0:
        try:
            os.remove(path)
        except OSError:
            pass  # still in use (another request): the next cleaning takes it


def cached_files():
    """Every cached file as (path, owner, last used, size); owner = the account's cache folder
    (a removed account's folder still counts towards the whole cache)."""
    found = []
    for folder in (PHOTO_CACHE, IMAGE_CACHE, VIDEO_CACHE, STICKER_CACHE, *module_loader.cache_roots()):
        if not os.path.isdir(folder):
            continue
        for owner in os.listdir(folder):
            for parent, _, files in os.walk(os.path.join(folder, owner)):
                for name in files:
                    path = os.path.join(parent, name)
                    try:
                        info = os.stat(path)
                    except OSError:
                        continue
                    found.append((path, owner, info.st_mtime, info.st_size))
    return found


def least_used_over(files, limit):
    """Of these files, the least recently used ones that must go to fit in limit bytes."""
    over = sum(f[3] for f in files) - limit
    going = []
    for f in sorted(files, key=lambda f: f[2]):
        if over <= 0:
            break
        going.append(f)
        over -= f[3]
    return going


def clean_caches():
    """Deletes cached files (every account's) unused for longer than the setting allows, then,
    with a size limit, each account's least recently used files beyond its share, then any
    beyond the limit for the whole cache."""
    days, total = cache_days(), cache_mb()
    if days is None and total is None:
        return
    files = cached_files()
    going = []
    if days is not None:
        oldest = time.time() - (days * 86400 if days else CACHE_OFF_GRACE)
        going = [f for f in files if f[2] < oldest]
    if total is not None and days != 0:
        kept = [f for f in files if f not in going]
        for owner in {f[1] for f in kept}:
            going += least_used_over([f for f in kept if f[1] == owner], cache_share_mb() * MB)
        going += least_used_over([f for f in kept if f not in going], total * MB)
    count = size = 0
    for path, _, _, file_size in going:
        try:
            os.remove(path)
            count += 1
            size += file_size
        except OSError:
            pass  # in use right now: next time
    if count:
        print(f"Server cache: removed {count} files ({size / MB:.1f} MB)")


cleaning_soon = False  # a size check is already waiting


def stored():
    """A new file went into the cache: with a size limit, check the sizes in a moment (one
    check for many new files), so a big video doesn't stay over the limit for hours."""
    global cleaning_soon
    if cache_mb() is None or cache_days() == 0 or cleaning_soon:
        return
    cleaning_soon = True

    def clean():
        global cleaning_soon
        cleaning_soon = False
        clean_caches()

    asyncio.get_running_loop().call_later(30, clean)


async def keep_caches_clean():
    while True:
        await asyncio.sleep(CACHE_CLEAN_EVERY)
        clean_caches()


STICKER_CACHE = "sticker_cache"
