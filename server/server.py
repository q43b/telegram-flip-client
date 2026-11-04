import os
import secrets
import asyncio
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import (APIRouter, Depends, FastAPI, Header, HTTPException,
                     WebSocket, WebSocketDisconnect)
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from telethon import TelegramClient, connection, events

load_dotenv()

client = TelegramClient(
    "tg_session",
    int(os.environ["API_ID"]),
    os.environ["API_HASH"],
    connection=connection.ConnectionTcpMTProxyRandomizedIntermediate,
    proxy=(
        os.environ["PROXY_HOST"],
        int(os.environ["PROXY_PORT"]),
        os.environ["PROXY_SECRET"],
    ),
)

ws_clients = set()  # currently connected browsers


async def broadcast(data):
    for ws in list(ws_clients):
        try:
            await ws.send_json(data)
        except Exception:
            ws_clients.discard(ws)


@client.on(events.NewMessage)
async def on_new_message(event):
    await broadcast({
        "type": "message",
        "id": event.id,
        "chat_id": event.chat_id,
        "text": event.raw_text or "",
        "out": event.out,
        "date": event.date.isoformat(),
    })


@client.on(events.MessageRead(inbox=True))
async def on_read(event):
    await broadcast({"type": "read", "chat_id": event.chat_id})


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Through the proxy, Telegram sometimes answers with a one-off error such as
    # MTPROTO_CLUSTER_INVALID. A failed connect() leaves the client half-connected,
    # and a second connect() would then skip setup, so retry from a clean disconnect.
    # get_me() returns None only when really logged out; other errors raise and retry.
    # (is_user_authorized() would report "not logged in" for any error, and cache it.)
    for attempt in range(1, 6):
        try:
            await client.connect()
            me = await client.get_me()
            break
        except Exception as e:
            print(f"Connect attempt {attempt} failed: {e}")
            await client.disconnect()
            await asyncio.sleep(3)
    else:
        raise RuntimeError("Could not connect to Telegram after 5 attempts")
    if me is None:
        raise RuntimeError("Not logged in. Run list_chats.py once first.")
    yield
    await client.disconnect()


def check_token(x_token: str = Header(default="")):
    if not secrets.compare_digest(x_token.encode(), os.environ["API_TOKEN"].encode()):
        raise HTTPException(status_code=401, detail="Bad token")


app = FastAPI(lifespan=lifespan)
api = APIRouter(dependencies=[Depends(check_token)])  # token required on these


class SendBody(BaseModel):
    chat_id: int
    text: str


@api.get("/chats")
async def get_chats(limit: int = 20):
    chats = []
    async for d in client.iter_dialogs(limit=limit):
        chats.append({"id": d.id, "name": d.name, "unread": d.unread_count})
    return chats


@api.get("/messages/{chat_id}")
async def get_messages(chat_id: int, limit: int = 20):
    try:
        msgs = []
        async for m in client.iter_messages(chat_id, limit=limit):
            msgs.append({
                "id": m.id,
                "text": m.text or "",
                "out": m.out,
                "date": m.date.isoformat(),
            })
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    return list(reversed(msgs))


@api.post("/send")
async def send(body: SendBody):
    msg = await client.send_message(body.chat_id, body.text)
    return {"id": msg.id}

@api.post("/read/{chat_id}")
async def mark_read(chat_id: int):
    try:
        await client.send_read_acknowledge(chat_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    return {"ok": True}

app.include_router(api)

@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    try:
        # The first message must be the token (see notes below)
        first = await asyncio.wait_for(ws.receive_text(), timeout=5)
        if not secrets.compare_digest(first.encode(), os.environ["API_TOKEN"].encode()):
            await ws.close(code=1008)
            return
        ws_clients.add(ws)
        while True:
            await ws.receive_text()  # keeps the connection open
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        ws_clients.discard(ws)


app.mount("/", StaticFiles(directory="static", html=True), name="static")