"""The server's accounts list: accounts.json next to .env (the working folder).

Each entry is one Telegram account the server works for:
  name             shown in the server's log and the maintenance menu (may be renamed)
  token            the app token that means this account (unique, never printed after adding)
  session          its Telegram session file (without ".session"); never changes
  local_favorites  its local favourite stickers' file; never changes
  admin            may see the live log ("Under the hood") on a shared server

Kept apart from server.py so the maintenance menu (run_server.py --maintenancemode) can read
and change the list without connecting to Telegram.
"""
import json
import os

ACCOUNTS_FILE = "accounts.json"


class AccountsError(Exception):
    """accounts.json can't be used as it is; the message says why."""


def migrated_from_env():
    """Before 0.13.0 the only account was in .env: its token, tg_session and
    local_favorites.json. Becomes account "main", so nothing moves and nobody logs in again."""
    return [{
        "name": "main",
        "token": os.environ["API_TOKEN"],
        "session": "tg_session",
        "local_favorites": "local_favorites.json",
        "admin": False,
    }]


def check(entries):
    if not entries:
        raise AccountsError(f"{ACCOUNTS_FILE} has no accounts.")
    for field in ("name", "token", "session", "local_favorites"):
        values = [e.get(field) for e in entries]
        if not all(isinstance(v, str) and v for v in values):
            raise AccountsError(f"Every account in {ACCOUNTS_FILE} needs a \"{field}\".")
        if len(set(values)) != len(values):
            # Two accounts with one token: requests couldn't tell whose they are
            raise AccountsError(f"Two accounts in {ACCOUNTS_FILE} have the same {field}.")


def load():
    """The accounts, oldest first. Creates accounts.json from .env the first time."""
    if not os.path.exists(ACCOUNTS_FILE):
        entries = migrated_from_env()
        save(entries)
        print(f"Created {ACCOUNTS_FILE} with this server's account (\"main\").")
        return entries
    with open(ACCOUNTS_FILE, encoding="utf-8") as f:
        entries = json.load(f)
    for e in entries:
        e.setdefault("admin", False)
    check(entries)
    return entries


def save(entries):
    check(entries)
    temporary = ACCOUNTS_FILE + ".new"
    with open(temporary, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=2)
    os.replace(temporary, ACCOUNTS_FILE)  # all or nothing, even if the PC stops halfway
