"""Starts the TG Flip server: first-run setup, Telegram login, then the web server.
With --maintenancemode, first a menu for the accounts (maintenance.py).

This is what the packaged tgflip-server.exe runs. For development, plain
`uvicorn server:app --host 0.0.0.0` keeps working as before.
"""
import asyncio
import os
import sys

from maintenance import generate_token

# Settings (.env) and the Telegram session live next to the program,
# not in whatever folder it happened to be started from
FROZEN = getattr(sys, "frozen", False)  # True inside the packaged exe
BASE_DIR = os.path.dirname(sys.executable if FROZEN else os.path.abspath(__file__))
ENV_FILE = os.path.join(BASE_DIR, ".env")
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

    with open(ENV_FILE, "w", encoding="utf-8") as f:
        f.write(f"API_ID={api_id}\n")
        f.write(f"API_HASH={api_hash}\n")
        f.write(f"API_TOKEN={token}\n")
        f.write(f"PROXY_HOST={proxy_host}\n")
        f.write(f"PROXY_PORT={proxy_port}\n")
        f.write(f"PROXY_SECRET={proxy_secret}\n")
        f.write(f"PORT={DEFAULT_PORT}\n")
        f.write(f"SERVER_MODE={'shared' if shared else 'private'}\n")

    print(f"\nSaved to {ENV_FILE}. Edit that file to change these later.")
    if generated:
        print(f"Your app token: {token}")
        print("Enter it in the phone app (Settings > server). It is also in the .env file.")
    print()


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
        # The list may have changed in the menu
        server.ACCOUNTS[:] = [server.Account(**entry) for entry in accounts.load()]
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
    os.chdir(BASE_DIR)  # the Telegram session file is created here
    if not os.path.exists(ENV_FILE):
        first_run_setup()

    from dotenv import load_dotenv
    load_dotenv(ENV_FILE)

    import server  # reads the settings from the environment when imported

    port = int(os.environ.get("PORT") or DEFAULT_PORT)
    maintenance_mode = "--maintenancemode" in sys.argv[1:]
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
