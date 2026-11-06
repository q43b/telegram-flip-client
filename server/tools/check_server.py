"""Read-only check of the server: every read-only endpoint and both live connections.

Prints one line per check (name, HTTP status, the answer's shape: counts and key names),
never message text, names, usernames, phone numbers or tokens. Run it before and after a
server change and compare the output (e.g. with `diff`). It also opens two /ws connections at
once (two apps on the same account) and checks both stay open independently.

    python server/tools/check_server.py                  # the token from server/.env
    TGFLIP_TOKEN=<another account's token> python server/tools/check_server.py
    TGFLIP_URL=http://192.168.31.24:9436 python server/tools/check_server.py

Needs the server running, and the `websockets` package (in the server's venv).
"""
import asyncio, json, os, time, urllib.request, urllib.error
import websockets
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env"))
TOKEN = os.environ.get("TGFLIP_TOKEN") or os.environ["API_TOKEN"]
B = os.environ.get("TGFLIP_URL", "http://127.0.0.1:9436")
WS = B.replace("http", "ws", 1)
H = {"x-token": TOKEN}


def call(path, method="GET", body=None, headers=H):
    data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
    hdr = dict(headers)
    if body is not None:
        hdr["content-type"] = "application/json"
    req = urllib.request.Request(B + path, data=data, headers=hdr, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            raw = r.read()
            ctype = r.headers.get("content-type", "")
            return r.status, (json.loads(raw) if "json" in ctype else raw)
    except urllib.error.HTTPError as e:
        return e.code, None


def shape(v):
    if isinstance(v, list):
        return f"list[{len(v)}]" + (f" keys={sorted(v[0])}" if v and isinstance(v[0], dict) else "")
    if isinstance(v, dict):
        return "keys=" + ",".join(sorted(v))
    if isinstance(v, (bytes, bytearray)):
        return f"bytes>0={len(v) > 0}"
    return type(v).__name__


def check(name, path, **kw):
    st, v = call(path, **kw)
    print(f"{name:28} {st} {shape(v) if v is not None else ''}")
    return v


for _ in range(60):
    try:
        call("/chats?limit=1"); break
    except Exception:
        time.sleep(2)

print(f"{'no token':28} {call('/chats', headers={})[0]}")
print(f"{'wrong token':28} {call('/chats', headers={'x-token': 'wrong'})[0]}")
chats = check("chats", "/chats?limit=30")
private = next(c for c in chats if c["id"] > 0 and not c["forum"])
group = next((c for c in chats if c["id"] < 0 and not c["forum"]), None)
forum = next((c for c in chats if c["forum"]), None)
ids = ",".join(str(c["id"]) for c in chats[:3])
check("chats by ids", f"/chats?ids={ids}")
msgs = check("messages", f"/messages/{private['id']}?limit=30")
check("profile", f"/profile/{private['id']}")
check("photo", f"/photo/{private['id']}?size=48&photo_id={private.get('photo') or ''}")
check("reactions allowed", f"/reactions/{private['id']}")
if msgs:
    check("delete options", f"/delete_options/{private['id']}/{msgs[-1]['id']}")
if group:
    check("group", f"/group/{group['id']}?members=10")
    check("group messages", f"/messages/{group['id']}?limit=20")
if forum:
    topics = check("topics", f"/topics/{forum['id']}")
    if topics:
        check("topic messages", f"/messages/{forum['id']}?limit=10&topic_id={topics[0]['id']}")

found = {}
for c in chats:
    if c["forum"]:
        continue
    st, ms = call(f"/messages/{c['id']}?limit=50")
    for m in ms or []:
        for kind in ("image", "video", "voice", "sticker", "round"):
            # Small videos only: a big one would take minutes through the proxy
            if kind in ("video", "round") and m.get(kind) and m[kind]["size"] > 5_000_000:
                continue
            if m.get(kind) and kind not in found:
                found[kind] = (c["id"], m)
    if len(found) == 5:
        break
for kind, (cid, m) in sorted(found.items()):
    if kind == "image":
        check("image", f"/image/{cid}/{m['id']}")
    elif kind in ("video", "round"):
        check(kind, f"/video/{cid}/{m['id']}")
    elif kind == "voice":
        check("voice", f"/voice/{cid}/{m['id']}")
    elif kind == "sticker":
        check("sticker", f"/sticker/{m['sticker']['id']}?size=96&chat_id={cid}&message_id={m['id']}")
        check("sticker pack of", f"/sticker_set_of/{m['sticker']['id']}?chat_id={cid}&message_id={m['id']}")
print(f"{'media kinds found':28} {sorted(found)}")
packs = check("sticker packs", "/sticker_sets")
for p in ("recent", "faved", "local"):
    check(f"sticker set {p}", f"/sticker_set/{p}")
if packs and packs.get("packs"):
    check("sticker set first", f"/sticker_set/{packs['packs'][0]['id']}")
check("unknown sticker", "/sticker/1?size=96")


async def live():
    a = await websockets.connect(WS + "/ws")
    b = await websockets.connect(WS + "/ws")
    await a.send(TOKEN)
    await b.send(TOKEN)
    await asyncio.sleep(1.5)
    both = a.state.name == "OPEN" and b.state.name == "OPEN"
    print(f"{'two apps live at once':28} {both}")
    await a.close()
    await asyncio.sleep(0.5)
    await b.ping()
    print(f"{'second stays after first':28} {b.state.name == 'OPEN'}")
    await b.close()
    bad = await websockets.connect(WS + "/ws")
    await bad.send("wrong")
    try:
        await asyncio.wait_for(bad.recv(), 5)
        print(f"{'ws wrong token':28} not refused")
    except websockets.ConnectionClosed as e:
        print(f"{'ws wrong token':28} closed {e.rcvd.code if e.rcvd else None}")
    async with websockets.connect(WS + "/logs") as lg:
        await lg.send(TOKEN)
        kept = json.loads(await asyncio.wait_for(lg.recv(), 10))
        print(f"{'logs':28} kept lines>0={len(kept) > 0}")


asyncio.run(live())
