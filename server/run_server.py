"""Starts the TG Flip server: first-run setup, Telegram login, then the web server.

This is what the packaged tgflip-server.exe runs. For development, plain
`uvicorn server:app --host 0.0.0.0` keeps working as before.
"""
import asyncio
import os
import secrets
import string
import sys

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


def generate_token():
    # Lowercase letters and digits only: easy to type on a phone keypad
    alphabet = string.ascii_lowercase + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(12))


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

    with open(ENV_FILE, "w", encoding="utf-8") as f:
        f.write(f"API_ID={api_id}\n")
        f.write(f"API_HASH={api_hash}\n")
        f.write(f"API_TOKEN={token}\n")
        f.write(f"PROXY_HOST={proxy_host}\n")
        f.write(f"PROXY_PORT={proxy_port}\n")
        f.write(f"PROXY_SECRET={proxy_secret}\n")
        f.write(f"PORT={DEFAULT_PORT}\n")

    print(f"\nSaved to {ENV_FILE}. Edit that file to change these later.")
    if generated:
        print(f"Your app token: {token}")
        print("Enter it in the phone app (Settings > server). It is also in the .env file.")
    print()


async def serve(server, port):
    import uvicorn

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
    print(f"Starting on port {port}. Press Ctrl+C to stop.")
    asyncio.run(serve(server, port))


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
