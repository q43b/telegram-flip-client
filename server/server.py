import os
import secrets
import asyncio
import functools
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


def reply_of(message):
    """Id of the message this one replies to, or None.
    In a topic every message points at the topic; that alone is not a reply."""
    r = message.reply_to
    if not isinstance(r, types.MessageReplyHeader):
        return None
    if getattr(r, "forum_topic", False) and r.reply_to_top_id is None:
        return None
    return r.reply_to_msg_id


def is_edited(message):
    """Whether to show "(edited)". edit_hide marks edits Telegram says not to show."""
    return message.edit_date is not None and not message.edit_hide


# Telegram's server settings, including how long messages can be edited and deleted
# for everyone. Asked once; they rarely change.
telegram_config = None


async def get_telegram_config():
    global telegram_config
    if telegram_config is None:
        telegram_config = await client(functions.help.GetConfigRequest())
    return telegram_config


def can_delete_others(entity):
    """Creator, or an admin allowed to delete other people's messages."""
    rights = getattr(entity, "admin_rights", None)
    return bool(getattr(entity, "creator", False) or (rights and rights.delete_messages))


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
        "reply_to_id": reply_of(event.message),
        "text": event.raw_text or "",
        "out": event.out,
        "date": event.date.isoformat(),
        "edited": is_edited(event.message),
    })


@client.on(events.MessageEdited)
async def on_edited(event):
    await broadcast({
        "type": "edited",
        "id": event.id,
        "chat_id": event.chat_id,
        "text": event.raw_text or "",
        "edited": is_edited(event.message),
    })


@client.on(events.MessageDeleted)
async def on_deleted(event):
    # Telegram names the chat only for channels and supergroups; elsewhere chat_id is
    # None, but there message ids are unique across all of the account's chats
    await broadcast({"type": "deleted", "chat_id": event.chat_id, "ids": event.deleted_ids})


@client.on(events.MessageRead(inbox=True))
async def on_read(event):
    forget_forum_unread(event.chat_id)
    await broadcast({"type": "read", "chat_id": event.chat_id})


async def connect_telegram():
    """Connects from scratch; returns get_me() (None = not logged in).

    Through the proxy, Telegram sometimes answers with MTPROTO_CLUSTER_INVALID.
    A failed connect() leaves the client half-connected, and a second connect()
    would then skip setup, so retry from a clean disconnect.
    get_me() returns None only when really logged out; other errors raise and retry.
    (is_user_authorized() would report "not logged in" for any error, and cache it.)
    """
    for attempt in range(1, 6):
        try:
            await client.connect()
            return await client.get_me()
        except Exception as e:
            print(f"Connect attempt {attempt} failed: {e}")
            await client.disconnect()
            await asyncio.sleep(3)
    raise RuntimeError("Could not connect to Telegram after 5 attempts")


# The same error also strikes while running: when the proxy drops the connection,
# Telethon reconnects by itself, but then every request can fail with
# MTPROTO_CLUSTER_INVALID until we reconnect from scratch.
def is_cluster_error(e):
    return "MTPROTO_CLUSTER_INVALID" in str(e)


reconnect_lock = asyncio.Lock()
last_recovery = 0.0


async def recover():
    """Reconnects from scratch. Several requests failing at once trigger one reconnect."""
    global last_recovery
    async with reconnect_lock:
        if time.monotonic() - last_recovery < 10:
            return  # another request just did it
        print("Telegram connection is broken (MTPROTO_CLUSTER_INVALID); reconnecting")
        await client.disconnect()
        await connect_telegram()
        last_recovery = time.monotonic()
        print("Reconnected to Telegram")


def recovering(endpoint):
    """Wraps an endpoint: on MTPROTO_CLUSTER_INVALID, reconnect and try once more."""
    @functools.wraps(endpoint)  # keeps the signature, which FastAPI reads
    async def wrapper(*args, **kwargs):
        try:
            return await endpoint(*args, **kwargs)
        except errors.RPCError as e:
            if not is_cluster_error(e):
                raise
        await recover()
        return await endpoint(*args, **kwargs)
    return wrapper


def bad_request(e):
    """Turns a Telegram error into HTTP 400, except the broken-connection one,
    which must reach @recovering."""
    if is_cluster_error(e):
        raise e
    raise HTTPException(status_code=400, detail=str(e))


WATCHDOG_INTERVAL = 120


async def watchdog():
    """Live updates stop silently while the connection is broken, so check it
    now and then even when no requests come in."""
    while True:
        await asyncio.sleep(WATCHDOG_INTERVAL)
        try:
            await client.get_me()
        except errors.RPCError as e:
            if is_cluster_error(e):
                try:
                    await recover()
                except Exception as e2:
                    print(f"Reconnect failed: {e2}")
        except Exception:
            pass  # network trouble: Telethon keeps retrying on its own


@asynccontextmanager
async def lifespan(app: FastAPI):
    if await connect_telegram() is None:
        raise RuntimeError("Not logged in. Run list_chats.py once first.")
    checker = asyncio.create_task(watchdog())
    yield
    checker.cancel()
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
    reply_to_id: Optional[int] = None


class EditBody(BaseModel):
    chat_id: int
    message_id: int
    text: str


class DeleteBody(BaseModel):
    chat_id: int
    message_id: int
    revoke: bool  # True = for everyone


class ForwardBody(BaseModel):
    from_chat_id: int
    message_id: int
    to_chat_id: int


@api.get("/chats")
@recovering
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
@recovering
async def get_topics(chat_id: int, limit: int = 50):
    try:
        topics = await get_forum_topics(await client.get_input_entity(chat_id), limit)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    return [{"id": t.id, "title": t.title, "unread": t.unread_count} for t in topics]


@api.get("/messages/{chat_id}")
@recovering
async def get_messages(chat_id: int, limit: int = 20, topic_id: Optional[int] = None):
    try:
        found = await topic_messages(chat_id, topic_id, limit)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    msgs = [
        {"id": m.id, "text": m.text or "", "out": m.out, "date": m.date.isoformat(),
         "reply_to_id": reply_of(m), "edited": is_edited(m)}
        for m in found
    ]
    return list(reversed(msgs))


@api.post("/send")
@recovering
async def send(body: SendBody):
    # Replying to a topic's id is how Telegram files a message into that topic.
    # General takes plain messages. A reply to a message stays in that message's topic.
    if body.reply_to_id is not None:
        reply_to = body.reply_to_id
    elif body.topic_id not in (None, GENERAL_TOPIC):
        reply_to = body.topic_id
    else:
        reply_to = None
    msg = await client.send_message(body.chat_id, body.text, reply_to=reply_to)
    return {"id": msg.id}

@api.post("/read/{chat_id}")
@recovering
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
        bad_request(e)
    forget_forum_unread(chat_id)  # after the read, so the next count sees it
    return {"ok": True}


@api.get("/delete_options/{chat_id}/{message_id}")
@recovering
async def delete_options(chat_id: int, message_id: int):
    """Which deletes Telegram allows for this message: {for_me, for_everyone}.

    Limits come from Telegram's config (seconds):
    - private chats: revoke_pm_time_limit; the other person's messages too only
      if revoke_pm_inbox is set
    - basic groups: revoke_time_limit for own messages; admins any time
    - supergroups/channels: deletion is always for everyone (channels.deleteMessages
      has no "for me" option); own messages, or anyone's for admins
    """
    try:
        entity = await client.get_entity(chat_id)
        msg = await client.get_messages(entity, ids=message_id)
        config = await get_telegram_config()
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    if msg is None:
        raise HTTPException(status_code=404, detail="Message not found")
    age = time.time() - msg.date.timestamp()

    if isinstance(entity, types.User):
        if entity.is_self:  # Saved Messages: nobody else to delete it for
            return {"for_me": True, "for_everyone": False}
        everyone = age < config.revoke_pm_time_limit and (msg.out or bool(config.revoke_pm_inbox))
        return {"for_me": True, "for_everyone": everyone}
    if isinstance(entity, types.Chat):  # basic group
        everyone = (msg.out and age < config.revoke_time_limit) or can_delete_others(entity)
        return {"for_me": True, "for_everyone": everyone}
    return {"for_me": False, "for_everyone": msg.out or can_delete_others(entity)}


@api.post("/delete")
@recovering
async def delete(body: DeleteBody):
    try:
        await client.delete_messages(body.chat_id, [body.message_id], revoke=body.revoke)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    return {"ok": True}


@api.post("/edit")
@recovering
async def edit(body: EditBody):
    try:
        await client.edit_message(body.chat_id, body.message_id, body.text)
    except errors.MessageNotModifiedError:
        pass  # same text as before: nothing to do, not a failure
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)  # e.g. MESSAGE_EDIT_TIME_EXPIRED: too old to edit
    return {"ok": True}


@api.post("/forward")
@recovering
async def forward(body: ForwardBody):
    try:
        msg = await client.forward_messages(body.to_chat_id, body.message_id, from_peer=body.from_chat_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    return {"id": msg.id}

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