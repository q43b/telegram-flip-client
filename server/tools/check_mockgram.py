"""Does the made-up Telegram (the mockgram module) still cover what the server asks of Telegram?

Builds a scratch server folder in a temp directory (a dummy .env, one account of kind "mockgram",
the module copied from modules/mockgram), starts the server from source on its own port, runs
tools/check_server.py against that account, checks that every endpoint it covers answered, and
lists any Telegram request the made-up Telegram didn't know. Then stops the server and deletes the
folder. Nothing real is touched: no network, no Telegram session, none of the owner's settings.

    python server/tools/check_mockgram.py [scene]       (default scene: showcase)

Run it after changing a route that asks Telegram for something new, or the backend or a scene.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.dirname(HERE)
REPO = os.path.dirname(SERVER)
PORT = 9446  # not 9436: a real server may be running
TOKEN = "mockgram-check-token"

# Every check line that must answer 200 (or True): what the real account's run prints
EXPECTED = ["chats", "chats by ids", "messages", "profile", "photo", "reactions allowed", "delete options", "group",
            "group messages", "topics", "topic messages", "image", "round", "sticker", "sticker pack of", "video",
            "voice", "sticker packs", "sticker set recent", "sticker set faved", "sticker set local", "sticker set first"]


def main():
    scene = sys.argv[1] if len(sys.argv) > 1 else "showcase"
    work = tempfile.mkdtemp(prefix="tgflip-mockgram-check-")
    try:
        for module in ("mockgram", "logs"):  # logs: the check also looks at the live log
            shutil.copytree(os.path.join(REPO, "modules", module), os.path.join(work, "modules", module),
                            ignore=shutil.ignore_patterns("__pycache__"))
        with open(os.path.join(work, ".env"), "w") as f:
            f.write(f"API_ID=1\nAPI_HASH={'0' * 32}\nAPI_TOKEN=unused\nPROXY_HOST=\nPORT={PORT}\n")
        with open(os.path.join(work, "accounts.json"), "w") as f:
            json.dump([{"name": "demo", "token": TOKEN, "session": "mockgram_1", "local_favorites": "lf.json", "admin": False,
                        "kind": "mockgram", "settings": {"scene": scene}}], f)
        # Settings given in the environment win over any .env the server finds from source
        env = dict(os.environ, PYTHONUNBUFFERED="1", API_ID="1", API_HASH="0" * 32, API_TOKEN="unused", PROXY_HOST="",
                   SERVER_MODE="private", SERVER_CACHE="forever", SERVER_CACHE_MB="", PORT=str(PORT))
        log_path = os.path.join(work, "server.log")
        with open(log_path, "w", encoding="utf-8") as log:
            server = subprocess.Popen([sys.executable, os.path.join(SERVER, "run_server.py"), "--data", work], cwd=SERVER,
                                      stdout=log, stderr=subprocess.STDOUT, env=env, stdin=subprocess.DEVNULL)
        try:
            text = ""
            for _ in range(90):
                time.sleep(1)
                text = open(log_path, encoding="utf-8", errors="replace").read()
                if "started successfully" in text or "Traceback" in text or server.poll() is not None:
                    break
            if "started successfully" not in text:
                print("The server didn't start:\n" + text[-1500:])
                return 1
            check = subprocess.run([sys.executable, os.path.join(HERE, "check_server.py")], capture_output=True, text=True, cwd=SERVER,
                                   env=dict(env, TGFLIP_TOKEN=TOKEN, TGFLIP_URL=f"http://127.0.0.1:{PORT}"), timeout=600,
                                   stdin=subprocess.DEVNULL)
        finally:
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()
        text = open(log_path, encoding="utf-8", errors="replace").read()
        lines = {line[:28].strip(): line[28:].strip() for line in check.stdout.splitlines()}
        missing = [name for name in EXPECTED if not (lines.get(name, "").startswith("200") or lines.get(name) == "True")]
        unknown = sorted(set(re.findall(r"doesn't know the request ([\w.]+)", text)))
        errors = sorted(set(re.findall(r"^(?:[A-Za-z]*Error|[A-Za-z.]*Exception)[^\n]*$", text, re.M)))
        # Windows' event loop sometimes logs these when the checking client closes a socket abruptly
        errors = [e for e in errors if "WinError 10054" not in e and "_call_connection_lost" not in e]
        print(f"scene {scene}: {len(lines)} checks printed")
        print("not answered with 200:", missing or "none")
        print("requests the made-up Telegram doesn't know:", unknown or "none")
        print("errors in the server's log:", errors or "none")
        if check.stderr.strip():
            print("check_server stderr:", check.stderr.strip().splitlines()[-1])
        ok = not missing and not unknown and not errors and not check.stderr.strip()
        if errors:
            context = text.splitlines()
            first = next(i for i, line in enumerate(context) if any(e in line for e in errors))
            print("--- the server's log around the first error:")
            print("\n".join(context[max(0, first - 12):first + 6]))
        print("OK" if ok else "NOT OK")
        return 0 if ok else 1
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
