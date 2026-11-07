"""Starts the TG Flip server: first-run setup, Telegram login, then the web server.
With --maintenancemode, first a menu for the accounts (maintenance.py).

Packaged, maintenance mode runs from a copy of the program (docs/self-update-plan.md), so the
program itself isn't in use and can be replaced: "Maintenance mode.bat" first runs
`tgflip-server.exe --prepare-maintenance` (copies the exe and _internal into maintenance/ and
exits), then `maintenance\tgflip-server.exe --maintenancemode --data <the folder>`. --data says
where .env, the accounts, sessions, modules and caches are (default: next to the program).

This is what the packaged tgflip-server.exe runs. For development, plain
`uvicorn server:app --host 0.0.0.0` keeps working as before.
"""
import asyncio
import os
import shutil
import subprocess
import sys

from maintenance import ask_cache, ask_cache_size, generate_token

# Settings (.env) and the Telegram sessions live next to the program (or in --data), not in
# whatever folder it happened to be started from
FROZEN = getattr(sys, "frozen", False)  # True inside the packaged exe
BASE_DIR = os.path.dirname(sys.executable if FROZEN else os.path.abspath(__file__))


def argument(name):
    """The value after name on the command line, or None."""
    args = sys.argv[1:]
    return args[args.index(name) + 1] if name in args and args.index(name) + 1 < len(args) else None


DATA_DIR = os.path.abspath(argument("--data") or BASE_DIR)
FROM_COPY = DATA_DIR != BASE_DIR  # this is maintenance mode's copy (the real program is in DATA_DIR)
ENV_FILE = os.path.join(DATA_DIR, ".env")
MAINTENANCE_COPY = "maintenance"
DEFAULT_PORT = 9436  # "q43b" in leet; 8000 is often taken by other programs


def ask(prompt, required=True):
    while True:
        answer = input(f"{prompt}: ").strip()
        if answer or not required:
            return answer
        print("  This one is needed.")


def ask_number(prompt):
    while True:
        answer = ask(prompt)
        if answer.isdigit():
            return answer
        print("  Digits only, please.")


def first_run_setup():
    """Asks for the settings once and writes them to .env next to the program."""
    print("TG Flip server: first-run setup")
    print("Get the API ID and hash at https://my.telegram.org (API development tools).\n")
    api_id = ask_number("Telegram API ID")
    api_hash = ask("Telegram API hash")

    print("\nMTProxy: needed only where Telegram is blocked. Press Enter to skip.")
    proxy_host = ask("Proxy host", required=False)
    proxy_port = ask_number("Proxy port") if proxy_host else ""
    proxy_secret = ask("Proxy secret") if proxy_host else ""

    print("\nThe app token is the password the phone app uses. Press Enter to generate one.")
    token = ask("App token", required=False)
    generated = not token
    if generated:
        token = generate_token()

    print("\nWho will use this server? Only you (private: your apps may see its live log), or")
    print("several people, each with their own Telegram account (shared: the log stays on this PC,")
    print("except for accounts you make admins). Change it later in Maintenance mode.")
    shared = ask("Shared server? (y/N)", required=False).lower() in ("y", "yes")
    cache = ask_cache()
    cache_mb = ask_cache_size(cache)

    with open(ENV_FILE, "w", encoding="utf-8") as f:
        f.write(f"API_ID={api_id}\n")
        f.write(f"API_HASH={api_hash}\n")
        f.write(f"API_TOKEN={token}\n")
        f.write(f"PROXY_HOST={proxy_host}\n")
        f.write(f"PROXY_PORT={proxy_port}\n")
        f.write(f"PROXY_SECRET={proxy_secret}\n")
        f.write(f"PORT={DEFAULT_PORT}\n")
        f.write(f"SERVER_MODE={'shared' if shared else 'private'}\n")
        f.write(f"SERVER_CACHE={cache}\n")
        f.write(f"SERVER_CACHE_MB={cache_mb}\n")

    print(f"\nSaved to {ENV_FILE}. Edit that file to change these later.")
    if generated:
        print(f"Your app token: {token}")
        print("Enter it in the phone app (Settings > server). It is also in the .env file.")
    print()


def prepare_maintenance():
    """The packaged program copies itself (exe and _internal) into maintenance/, fresh each time,
    so maintenance mode is always this version, and exits. Refused while the old copy is still
    running (it waits for a server it started)."""
    if not FROZEN:
        sys.exit("--prepare-maintenance is for the packaged server; here, run_server.py --maintenancemode")
    target = os.path.join(BASE_DIR, MAINTENANCE_COPY)
    print("Preparing maintenance mode...")
    try:
        if os.path.exists(target):
            shutil.rmtree(target)  # last time's copy: this version replaces it
        os.makedirs(target)
        shutil.copy2(sys.executable, target)
        contents = os.path.join(BASE_DIR, "_internal")
        shutil.copytree(contents, os.path.join(target, os.path.basename(contents)))
    except OSError:
        print("\nThe maintenance copy is still in use: close the running server (or the other\n"
              "Maintenance mode window) first, then open Maintenance mode again.")
        sys.exit(1)


def start_real_server():
    """From maintenance mode's copy: "s) Start the server" runs the real program, in this window,
    and waits for it (so this copy's files stay in use, never the real program's)."""
    program = os.path.join(DATA_DIR, os.path.basename(sys.executable))
    print("Starting the server...\n")
    return subprocess.call([program], cwd=DATA_DIR)


async def serve(server, port, maintenance_mode):
    import uvicorn

    if maintenance_mode:
        import accounts
        import maintenance
        # Loading server.py opened every account's session file; close them, so the menu can
        # log an account out (which deletes its file: Windows refuses while it is open)
        for acc in server.ACCOUNTS:
            acc.client.session.close()
        if not await maintenance.menu(server):
            return
        if FROM_COPY:
            # Not from this copy: the real program (maybe just updated) runs the server
            for acc in server.ACCOUNTS:
                await acc.client.disconnect()
            sys.exit(start_real_server())
        # The list may have changed in the menu
        server.ACCOUNTS[:] = [server.Account(**entry) for entry in accounts.load()]
        # Not loaded at import in this mode, so the menu could replace their files
        server.load_modules()
    # The first account may still need its first login (first-run setup); later accounts are
    # logged in when added, in --maintenancemode
    server.use_account(server.ACCOUNTS[0])
    me = await server.connect_telegram()
    if me is None:
        # Only on the first run, or after logging out: asks for phone, code, 2FA password
        print("Logging in to Telegram (only needed once).")
        await server.client.start()
    # Same event loop as the login above; the server's own startup reuses this connection
    config = uvicorn.Config(server.app, host="0.0.0.0", port=port)
    await uvicorn.Server(config).serve()


def main():
    if "--prepare-maintenance" in sys.argv[1:]:
        return prepare_maintenance()
    os.chdir(DATA_DIR)  # .env, accounts, the Telegram sessions, modules and caches are here
    if not os.path.exists(ENV_FILE):
        first_run_setup()

    from dotenv import load_dotenv
    load_dotenv(ENV_FILE)

    maintenance_mode = "--maintenancemode" in sys.argv[1:]
    if maintenance_mode:
        # Modules are loaded only when the server starts: a loaded module's libraries are
        # locked by Windows, so the menu couldn't replace or remove them
        os.environ["TGFLIP_LOAD_MODULES_LATER"] = "1"
    import server  # reads the settings from the environment when imported

    port = int(os.environ.get("PORT") or DEFAULT_PORT)
    if not maintenance_mode:
        print(f"Starting on port {port}. Press Ctrl+C to stop.")
    asyncio.run(serve(server, port, maintenance_mode))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception as e:
        print(f"\nError: {e}")
        if FROZEN:
            # A double-clicked console window closes at once; keep the error readable
            input("Press Enter to close.")
        raise
