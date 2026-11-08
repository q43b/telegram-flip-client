"""Maintenance mode (tgflip-server --maintenancemode, or "Maintenance mode.bat"): a small menu
in the server window for the accounts list, before the server starts.

Accounts are added here, on the PC, so Telegram's login codes never cross the network. The
planned animated-stickers add-on will be installed from this menu too.
"""
import json
import os
import secrets
import string

from dotenv import set_key

import accounts
import module_menu
import update_menu

ENV_FILE = ".env"  # run_server.py works in the program's folder


def ask(prompt):
    return input(f"{prompt}: ").strip()


def yes(prompt):
    return ask(f"{prompt} (y/N)").lower() in ("y", "yes")


def generate_token():
    # Lowercase letters and digits only: easy to type on a phone keypad
    alphabet = string.ascii_lowercase + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(12))


def show(entries):
    print("\nAccounts:")
    for number, e in enumerate(entries, 1):
        kind = e.get("kind", "telegram")
        print(f"  {number}. {e['name']}" + ("" if kind == "telegram" else f"  [demo: {kind}]")
              + ("  (admin)" if e["admin"] else ""))


def pick(entries, what):
    """An account chosen by its number, or None (Enter cancels)."""
    show(entries)
    answer = ask(f"Number of the account to {what} (Enter to cancel)")
    if answer.isdigit() and 1 <= int(answer) <= len(entries):
        return entries[int(answer) - 1]
    if answer:
        print("  No account with that number.")
    return None


def new_name(entries, prompt):
    name = ask(prompt)
    if not name:
        return None
    if any(e["name"].lower() == name.lower() for e in entries):
        print("  There is already an account with that name.")
        return None
    return name


def free_files(entries, kind="telegram"):
    """A session and a local favourites file name no account uses and no file has yet. A demo
    account (another kind) has no session file: its session name only names its cache folders."""
    taken = {e["session"] for e in entries} | {e["local_favorites"] for e in entries}
    for n in range(2, 1000):
        if kind == "telegram":
            session, favorites = f"tg_session_{n}", f"local_favorites_{n}.json"
        else:
            session, favorites = f"{kind}_{n}", f"local_favorites_{kind}_{n}.json"
        if (session not in taken and favorites not in taken
                and not os.path.exists(session + ".session") and not os.path.exists(favorites)):
            return session, favorites
    raise RuntimeError("No free session file name")


def remove_session_file(session):
    for suffix in (".session", ".session-journal"):
        if os.path.exists(session + suffix):
            os.remove(session + suffix)


async def log_in(server, entry):
    """Logs the new account in to Telegram in this window (phone, code, 2FA password).
    True when it worked; otherwise its half-made session file is removed."""
    acc = server.Account(**entry)
    server.use_account(acc)
    try:
        me = await server.connect_telegram()
        if me is None:
            print("Logging in to Telegram. Use the phone number of the account to add.")
            await acc.client.start(phone=lambda: ask("Phone number, with the country code (+7...)"))
            me = await acc.client.get_me()
        if me.bot:
            print("  That is a bot. TG Flip only works with people's accounts.")
            await acc.client.log_out()
            return False
        return True
    except Exception as e:
        print(f"  Login didn't work: {e}")
        return False
    finally:
        await acc.client.disconnect()


def ask_settings():
    """A demo account's settings for its module: a JSON object, or nothing. (True, settings),
    or (False, None) when what was typed isn't an object."""
    text = ask('Settings for it as JSON, like {"scene": "showcase"} (Enter = none)')
    if not text:
        return True, {}
    try:
        settings = json.loads(text)
    except ValueError as e:
        print(f"  That is not valid JSON ({e}).")
        return False, None
    if not isinstance(settings, dict):
        print("  The settings must be an object, like {\"scene\": \"showcase\"}.")
        return False, None
    return True, settings


async def add(server, entries):
    name = new_name(entries, "Name for the new account (shown only in the server's log)")
    if name is None:
        return
    kind = ask("Kind of account (Enter = a Telegram account; or a kind a module offers, like mockgram)").lower() or "telegram"
    if not accounts.KIND.fullmatch(kind):
        print("  A kind is lowercase letters, digits and _.")
        return
    session, favorites = free_files(entries, kind)
    token = generate_token()
    while any(e["token"] == token for e in entries):
        token = generate_token()
    entry = {"name": name, "token": token, "session": session,
             "local_favorites": favorites, "admin": False}
    if kind != "telegram":
        # A demo account: no phone login, nothing from Telegram. A module of that kind makes
        # its client when the server starts.
        ok, settings = ask_settings()
        if not ok:
            return
        entry.update({"kind": kind, "settings": settings})
        accounts.save(entries + [entry])
        entries.append(entry)
        print(f"\nAdded the demo account \"{name}\" ({kind}). Its app token: {token}")
        print(f"It only works while a module that makes \"{kind}\" accounts is installed and on")
        print("(x) Modules). Enter the token in the app like any account's.")
        return
    if not await log_in(server, entry):
        remove_session_file(session)
        return
    accounts.save(entries + [entry])
    entries.append(entry)
    print(f"\nAdded \"{name}\". Its app token: {token}")
    print("Enter it in that person's TG Flip app: Settings > Network > the server's token.")
    print("It is not shown again here (it is in accounts.json; keep that file private).")


async def remove(server, entries):
    if len(entries) == 1:
        print("  This is the only account; the server needs at least one.")
        return
    entry = pick(entries, "remove")
    if entry is None:
        return
    if ask(f"Type its name, \"{entry['name']}\", to remove it") != entry["name"]:
        print("  Not removed.")
        return
    real = entry.get("kind", "telegram") == "telegram"  # a demo account has no Telegram login
    log_out = real and yes("Also log it out of Telegram (ends this server's session for that account)?")
    if log_out:
        acc = server.Account(**entry)
        server.use_account(acc)
        try:
            if await server.connect_telegram() is not None:
                await acc.client.log_out()  # also deletes its session file
                print("  Logged out of Telegram.")
        except Exception as e:
            print(f"  Couldn't log out ({e}); its session file stays.")
        finally:
            await acc.client.disconnect()
    entries.remove(entry)
    accounts.save(entries)
    print(f"Removed \"{entry['name']}\". Its app can't connect any more.")
    if real and not log_out:
        print(f"Its Telegram login stays in {entry['session']}.session; delete that file to "
              "forget it (Telegram's own list of sessions still shows it until ended there).")


def rename(entries):
    entry = pick(entries, "rename")
    if entry is None:
        return
    name = new_name(entries, f"New name for \"{entry['name']}\"")
    if name is None:
        return
    entry["name"] = name
    accounts.save(entries)
    print(f"Renamed to \"{name}\".")


def toggle_admin(entries):
    entry = pick(entries, "give or take admin rights")
    if entry is None:
        return
    entry["admin"] = not entry["admin"]
    accounts.save(entries)
    if entry["admin"]:
        print(f"\"{entry['name']}\" is now an admin: its app may see the live log on a shared server.")
    else:
        print(f"\"{entry['name']}\" is no longer an admin.")


def ask_cache():
    """The server cache setting as SERVER_CACHE wants it: "forever", "off" or a number of days.
    Also used by first-run setup."""
    print("\nServer cache: the server keeps the media it fetched for the apps (profile photos,")
    print("photos, videos, stickers), so they load fast next time. It can grow large with videos.")
    print("  f) Keep them forever (default)")
    print("  d) Keep them for some days since last used")
    print("  o) Don't keep them (each file is deleted once sent)")
    while True:
        choice = ask("Choose (Enter = f)").lower()
        if choice in ("", "f"):
            return "forever"
        if choice == "o":
            return "off"
        if choice == "d":
            days = ask("How many days")
            if days.isdigit() and 1 <= int(days) <= 100_000:
                return str(int(days))
            print("  A number of days, 1-100000, please.")


def ask_cache_size(keep):
    """SERVER_CACHE_MB: the most the whole cache may take ("" = no limit), shared equally by
    the accounts. Not asked when nothing is kept. Also used by first-run setup."""
    if keep == "off":
        return ""
    print("\nThe most the server cache may take on this PC, in MB. Each account gets an equal")
    print("share; the least recently used files go first.")
    while True:
        mb = ask("Largest size in MB (Enter = no limit)")
        if not mb or (mb.isdigit() and 1 <= int(mb) <= 10_000_000):
            return str(int(mb)) if mb else ""
        print("  A number of MB, 1-10000000, or Enter for no limit, please.")


def change_cache(server):
    keep = ask_cache()
    mb = ask_cache_size(keep)
    for key, value in (("SERVER_CACHE", keep), ("SERVER_CACHE_MB", mb)):
        set_key(ENV_FILE, key, value, quote_mode="never")
        os.environ[key] = value  # for the server started from this menu
    print(f"Server cache: {server.describe_cache_setting()}. Older files are removed when the server starts.")


def toggle_mode(server):
    shared = not server.shared_server()
    mode = "shared" if shared else "private"
    set_key(ENV_FILE, "SERVER_MODE", mode, quote_mode="never")
    os.environ["SERVER_MODE"] = mode  # for the server started from this menu
    if shared:
        print("This is now a shared server: only admin accounts' apps may see the live log.")
    else:
        print("This is now a private server: every account's app may see the live log.")


async def menu(server):
    """The menu. True = start the server afterwards, False = quit."""
    entries = accounts.load()
    print("\nTG Flip server: maintenance mode")
    if os.environ.get("TGFLIP_MAINTENANCE_COPY"):
        update_menu.repair()  # an update cut off halfway: the version from before it, back in place
    while True:
        show(entries)
        print("\n  a) Add an account")
        print("  r) Remove an account")
        print("  n) Rename an account")
        print("  m) Give or take admin rights")
        print(f"  p) Private or shared server (now: {'shared' if server.shared_server() else 'private'})")
        print(f"  c) Server cache (now: {server.describe_cache_setting()})")
        print("  x) Modules")
        print("  u) Update the server")
        print("  s) Start the server")
        print("  q) Quit")
        choice = ask("Choose").lower()
        if choice == "a":
            await add(server, entries)
        elif choice == "r":
            await remove(server, entries)
        elif choice == "n":
            rename(entries)
        elif choice == "m":
            toggle_admin(entries)
        elif choice == "p":
            toggle_mode(server)
        elif choice == "c":
            change_cache(server)
        elif choice == "u":
            update_menu.menu()
        elif choice == "x":
            module_menu.menu(server)
        elif choice == "s":
            return True
        elif choice == "q":
            return False
