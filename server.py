import os
import secrets
import asyncio
from contextlib import asynccontextmanager
from fastapi.staticfiles import StaticFiles
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel
from telethon import TelegramClient, connection

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


@asynccontextmanager
async def lifespan(app: FastAPI):
    for attempt in range(1, 6):
        try:
            await client.connect()
            break
        except Exception as e:
            print(f"Connect attempt {attempt} failed: {e}")
            await asyncio.sleep(3)
    else:
        raise RuntimeError("Could not connect to Telegram after 5 attempts")
    if not await client.is_user_authorized():
        raise RuntimeError("Not logged in. Run list_chats.py once first.")
    yield
    await client.disconnect()


def check_token(x_token: str = Header(default="")):
    if not secrets.compare_digest(x_token, os.environ["API_TOKEN"]):
        raise HTTPException(status_code=401, detail="Bad token")


app = FastAPI(lifespan=lifespan, dependencies=[Depends(check_token)])


class SendBody(BaseModel):
    chat_id: int
    text: str


@app.get("/chats")
async def get_chats(limit: int = 20):
    chats = []
    async for d in client.iter_dialogs(limit=limit):
        chats.append({"id": d.id, "name": d.name, "unread": d.unread_count})
    return chats


@app.get("/messages/{chat_id}")
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


@app.post("/send")
async def send(body: SendBody):
    msg = await client.send_message(body.chat_id, body.text)
    return {"id": msg.id}
app.mount("/", StaticFiles(directory="static", html=True), name="static")