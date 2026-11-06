"""Maintenance mode (tgflip-server --maintenancemode, or "Maintenance mode.bat"): a small menu
in the server window for the accounts list, before the server starts.

Accounts are added here, on the PC, so Telegram's login codes never cross the network. The
planned animated-stickers add-on will be installed from this menu too.
"""
import os
import secrets
import string

from dotenv import set_key

import accounts

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
        print(f"  {number}. {e['name']}" + ("  (admin)" if e["admin"] else ""))


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


def free_files(entries):
    """A session and a local favourites file name no account uses and no file has yet."""
    taken = {e["session"] for e in entries} | {e["local_favorites"] for e in entries}
    for n in range(2, 1000):
        session, favorites = f"tg_session_{n}", f"local_favorites_{n}.json"
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


async def add(server, entries):
    name = new_name(entries, "Name for the new account (shown only in the server's log)")
    if name is None:
        return
    session, favorites = free_files(entries)
    token = generate_token()
    while any(e["token"] == token for e in entries):
        token = generate_token()
    entry = {"name": name, "token": token, "session": session,
             "local_favorites": favorites, "admin": False}
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
    log_out = yes("Also log it out of Telegram (ends this server's session for that account)?")
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
    if not log_out:
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
    while True:
        show(entries)
        print("\n  a) Add an account")
        print("  r) Remove an account")
        print("  n) Rename an account")
        print("  m) Give or take admin rights")
        print(f"  p) Private or shared server (now: {'shared' if server.shared_server() else 'private'})")
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
        elif choice == "s":
            return True
        elif choice == "q":
            return False
