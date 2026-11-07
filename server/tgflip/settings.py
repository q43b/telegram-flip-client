"""The server's settings: its version, the MTProxy, private or shared, the cache settings
(all from .env, read each time where maintenance mode can change them), and comparing versions.
"""
import os
import re
from dotenv import load_dotenv
from telethon import connection


load_dotenv()


# Printed at startup; build_server.ps1 also names the zip after it
VERSION = "1.2.0"


CODENAME = "Melted"  # the era's name, printed after the version (from 0.13.0-beta; Melted from 1.0.0)


def shared_server():
    """SERVER_MODE in .env: "private" (the default: one person's server, every account's app
    may watch the live log) or "shared" (for several people: only admin accounts may, since
    the log shows everyone's addresses and chats). Read each time: maintenance mode can change it."""
    mode = (os.environ.get("SERVER_MODE") or "private").strip().lower()
    if mode not in ("private", "shared"):
        raise RuntimeError(f"SERVER_MODE in .env must be private or shared, not {mode!r}")
    return mode == "shared"


# The MTProxy is needed where Telegram is blocked; leave PROXY_HOST empty to connect directly
if os.environ.get("PROXY_HOST"):
    proxy_args = dict(
        connection=connection.ConnectionTcpMTProxyRandomizedIntermediate,
        proxy=(
            os.environ["PROXY_HOST"],
            int(os.environ["PROXY_PORT"]),
            os.environ["PROXY_SECRET"],
        ),
    )
else:
    proxy_args = {}


def app_version_key(version):
    """For comparing app versions: "0.12.1-beta" -> (1, 0, 12, 1). Every beta comes after every
    alpha (they started over at 0.1.0-beta), a release without a tag after both; "-dev" (debug
    builds) is ignored. None for a missing or unreadable version."""
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta))?(?:-dev)?", version or "")
    if match is None:
        return None
    stage = {"alpha": 0, "beta": 1, None: 2}[match.group(4)]
    return (stage, *map(int, match.group(1, 2, 3)))
