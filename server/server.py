import os
import secrets
import asyncio
import time
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import (APIRouter, Depends, FastAPI, Header, HTTPException,
                     WebSocket, WebSocketDisconnect)
from fastapi.staticfiles import StaticFiles
from typing import Optional

from pydantic import BaseModel
from telethon import TelegramClient, connection, errors, events, functions, types

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

# Forum groups ("topics"): the General topic always has id 1, and its messages
# carry no topic mark at all, unlike messages in every other topic.
GENERAL_TOPIC = 1
GENERAL_SCAN = 200  # how far back to look when collecting General's messages


def topic_of(message):
    """Topic id of a message in a forum group, or None for General and ordinary chats."""
    r = message.reply_to
    if r is not None and getattr(r, "forum_topic", False):
        # A reply inside a topic points at the topic with reply_to_top_id
        return r.reply_to_top_id or r.reply_to_msg_id
    return None


async def topic_messages(chat_id, topic_id, limit):
    """Newest first. topic_id None = the whole chat (ordinary chats)."""
    if topic_id is None:
        return [m async for m in client.iter_messages(chat_id, limit=limit)]
    if topic_id != GENERAL_TOPIC:
        return [m async for m in client.iter_messages(chat_id, limit=limit, reply_to=topic_id)]
    # General: take the group's recent messages that belong to no other topic.
    # "Topic created" notices also carry no mark, so skip those.
    found = []
    async for m in client.iter_messages(chat_id, limit=GENERAL_SCAN):
        if topic_of(m) is None and not isinstance(m.action, types.MessageActionTopicCreate):
            found.append(m)
            if len(found) >= limit:
                break
    return found


async def get_forum_topics(peer, limit):
    result = await client(functions.messages.GetForumTopicsRequest(
        peer=peer, offset_date=None, offset_id=0, offset_topic=0, limit=limit,
    ))
    # Deleted topics come back as ForumTopicDeleted, which has no title or counts
    return [t for t in result.topics if isinstance(t, types.ForumTopic)]


# A forum group's own unread counter goes stale: reading topic by topic never clears it
# (it can say 47 while every topic is read). Like the official apps, add up the topics.
# That costs a request per forum, so remember totals and recount only after activity
# in that group (see forget_forum_unread) or after FORUM_UNREAD_TTL seconds.
FORUM_UNREAD_TTL = 60
forum_unread_cache = {}  # chat_id -> (time counted, total unread)


async def forum_unread(dialog):
    cached = forum_unread_cache.get(dialog.id)
    if cached and time.monotonic() - cached[0] < FORUM_UNREAD_TTL:
        return cached[1]
    try:
        topics = await get_forum_topics(dialog.input_entity, 100)
    except errors.RPCError as e:
        print(f"Could not count topics of {dialog.id}: {e}")
        return 0
    total = sum(t.unread_count for t in topics)
    forum_unread_cache[dialog.id] = (time.monotonic(), total)
    return total


def forget_forum_unread(chat_id):
    forum_unread_cache.pop(chat_id, None)


async def broadcast(data):
    for ws in list(ws_clients):
        try:
            await ws.send_json(data)
        except Exception:
            ws_clients.discard(ws)


@client.on(events.NewMessage)
async def on_new_message(event):
    forget_forum_unread(event.chat_id)
    await broadcast({
        "type": "message",
        "id": event.id,
        "chat_id": event.chat_id,
        "topic_id": topic_of(event.message),  # None in ordinary chats and General
        "text": event.raw_text or "",
        "out": event.out,
        "date": event.date.isoformat(),
    })


@client.on(events.MessageRead(inbox=True))
async def on_read(event):
    forget_forum_unread(event.chat_id)
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
    topic_id: Optional[int] = None


@api.get("/chats")
async def get_chats(limit: int = 20):
    chats = []
    async for d in client.iter_dialogs(limit=limit):
        forum = bool(getattr(d.entity, "forum", False))  # a group split into topics
        chats.append({
            "id": d.id,
            "name": d.name,
            "unread": await forum_unread(d) if forum else d.unread_count,
            "forum": forum,
        })
    return chats


@api.get("/topics/{chat_id}")
async def get_topics(chat_id: int, limit: int = 50):
    try:
        topics = await get_forum_topics(await client.get_input_entity(chat_id), limit)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return [{"id": t.id, "title": t.title, "unread": t.unread_count} for t in topics]


@api.get("/messages/{chat_id}")
async def get_messages(chat_id: int, limit: int = 20, topic_id: Optional[int] = None):
    try:
        found = await topic_messages(chat_id, topic_id, limit)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        raise HTTPException(status_code=400, detail=str(e))
    msgs = [
        {"id": m.id, "text": m.text or "", "out": m.out, "date": m.date.isoformat()}
        for m in found
    ]
    return list(reversed(msgs))


@api.post("/send")
async def send(body: SendBody):
    # Replying to a topic's id is how Telegram files a message into that topic.
    # General takes plain messages.
    reply_to = body.topic_id if body.topic_id not in (None, GENERAL_TOPIC) else None
    msg = await client.send_message(body.chat_id, body.text, reply_to=reply_to)
    return {"id": msg.id}

@api.post("/read/{chat_id}")
async def mark_read(chat_id: int, topic_id: Optional[int] = None):
    try:
        if topic_id is None:
            await client.send_read_acknowledge(chat_id)
        else:
            # Topics are read one at a time, up to their newest message
            newest = await topic_messages(chat_id, topic_id, 1)
            if newest:
                await client(functions.messages.ReadDiscussionRequest(
                    peer=await client.get_input_entity(chat_id),
                    msg_id=topic_id,
                    read_max_id=newest[0].id,
                ))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        raise HTTPException(status_code=400, detail=str(e))
    forget_forum_unread(chat_id)  # after the read, so the next count sees it
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