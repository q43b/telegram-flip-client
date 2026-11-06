import os
import secrets
import asyncio
import functools
import itertools
import time
from datetime import datetime, timezone
from contextlib import asynccontextmanager
from io import BytesIO

from dotenv import load_dotenv
from fastapi import (APIRouter, Depends, FastAPI, Header, HTTPException, Request, Response,
                     WebSocket, WebSocketDisconnect)
from fastapi.staticfiles import StaticFiles
from typing import Optional

from PIL import Image
from pydantic import BaseModel
from telethon import TelegramClient, connection, errors, events, functions, types, utils
from telethon.client.dialogs import _dialog_message_key  # to build dialogs like iter_dialogs
from telethon.tl import custom

load_dotenv()

# Printed at startup; build_server.ps1 also names the zip after it
VERSION = "0.3.1-beta"

# The MTProxy is needed where Telegram is blocked; leave PROXY_HOST empty to connect directly
if os.environ.get("PROXY_HOST"):
    proxy_args = dict(
        connection=connection.ConnectionTcpMTProxyRandomizedIntermediate,
        proxy=(
            os.environ["PROXY_HOST"],
            int(os.environ["PROXY_PORT"]),
            os.environ["PROXY_SECRET"],
        ),
    )
else:
    proxy_args = {}

client = TelegramClient(
    "tg_session",
    int(os.environ["API_ID"]),
    os.environ["API_HASH"],
    **proxy_args,
)

# The web page lives next to this file, also inside the packaged server
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")

ws_clients = set()  # currently connected browsers

# Profile photos, shrunk to the size the app asked for, saved as
# <chat id>_<photo id>_<size>.jpg so each is fetched from Telegram only once.
# Next to the session and .env (the working folder), also for the packaged server.
PHOTO_CACHE = "photo_cache"
PHOTO_SIZES = range(16, 161)  # Telegram's small profile photo is 160×160

# Forum groups ("topics"): the General topic always has id 1, and its messages
# carry no topic mark at all, unlike messages in every other topic.
GENERAL_TOPIC = 1
# How far back to look when collecting General's messages: at least GENERAL_SCAN, and
# GENERAL_SCAN_PER_MESSAGE per requested message, so "load older messages" can go deeper
GENERAL_SCAN = 200
GENERAL_SCAN_PER_MESSAGE = 10


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


# Why a call ended; Telegram stores calls as service messages with a MessageActionPhoneCall
CALL_REASONS = {
    types.PhoneCallDiscardReasonMissed: "missed",
    types.PhoneCallDiscardReasonBusy: "busy",
    types.PhoneCallDiscardReasonHangup: "hangup",
    types.PhoneCallDiscardReasonDisconnect: "disconnect",
}


def call_of(message):
    """For a call record: {video, reason, duration}; for anything else None.
    reason: missed / busy / hangup / disconnect, or None if unknown.
    duration: seconds talked, or None if the call was never answered."""
    action = getattr(message, "action", None)
    if not isinstance(action, types.MessageActionPhoneCall):
        return None
    return {
        "video": bool(action.video),
        "reason": CALL_REASONS.get(type(action.reason)),
        "duration": action.duration,
    }


def voice_of(message):
    """For a voice message: {duration} in seconds; for anything else None. The file itself
    comes from GET /voice (Telegram keeps voice messages as OGG/Opus)."""
    if getattr(message, "voice", None) is None:
        return None
    return {"duration": int(message.file.duration or 0)}


# Telegram's sizes of a photo that can be downloaded (not the tiny inline previews)
DOWNLOADABLE_SIZES = (types.PhotoSize, types.PhotoSizeProgressive, types.PhotoCachedSize)


def photo_sizes(message):
    """A photo message's downloadable sizes, smallest first; [] for anything else."""
    if not isinstance(message.media, types.MessageMediaPhoto) or not isinstance(message.photo, types.Photo):
        return []
    sizes = [s for s in message.photo.sizes if isinstance(s, DOWNLOADABLE_SIZES)]
    return sorted(sizes, key=lambda s: s.w * s.h)


def image_of(message):
    """For a photo: {w, h}, its full size in pixels; for anything else None. The picture
    itself comes from GET /image, shrunk to fit the phone's screen."""
    sizes = photo_sizes(message)
    if not sizes:
        return None
    return {"w": sizes[-1].w, "h": sizes[-1].h}


def sender_name(message, sender=None):
    """Who wrote a message, shown in groups: their first name (or a channel's title when a
    channel posts into a group). None for my own messages, private chats and channel posts,
    where it's clear anyway. Telegram sends the senders along with the messages, so this
    needs no extra requests; pass sender when it was fetched separately (live events)."""
    if message.out or message.is_private or getattr(message, "post", False):
        return None
    s = sender if sender is not None else message.sender
    return getattr(s, "first_name", None) or getattr(s, "title", None)


def sender_id_of(message, sender=None):
    """User id of who wrote a message, for opening their profile or a private chat with them
    from a group. Set exactly where sender_name is, and only for people (not channels)."""
    if sender_name(message, sender) is None:
        return None
    s = sender if sender is not None else message.sender
    return s.id if isinstance(s, types.User) else None


PREVIEW_LENGTH = 100  # the phone shows far less; no need to send whole messages


def last_message_preview(message):
    """The chat list's preview line: {text, out, date, sender, kind, call}, or None.

    It comes with the chat list itself (each dialog's last message, in a forum group the
    newest of any topic), so it costs no extra requests. kind: "text", "media" (no text,
    e.g. a sticker), "photo" (text is its caption), "voice" (details in voice, see
    voice_of), "call" (details in
    call, see call_of) or "event" (joined,
    pinned, ...). sender: who wrote it, only for other people's messages in groups;
    None in private chats and channel posts.
    """
    if message is None:
        return None
    call = call_of(message)
    voice = voice_of(message)
    if call is not None:
        text, kind = "", "call"
    elif voice is not None:
        text, kind = message.raw_text or "", "voice"
    elif image_of(message) is not None:
        text, kind = message.raw_text or "", "photo"
    elif isinstance(message, types.MessageService):
        text, kind = "", "event"
    else:
        text = message.raw_text or ""
        kind = "text" if text else "media"
    return {
        "text": text[:PREVIEW_LENGTH],
        "out": message.out,
        "date": message.date.isoformat(),
        "sender": sender_name(message),
        "kind": kind,
        "call": call,
        "voice": voice,
    }


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
    scan = max(GENERAL_SCAN, limit * GENERAL_SCAN_PER_MESSAGE)
    async for m in client.iter_messages(chat_id, limit=scan):
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
        if is_cluster_error(e):
            raise  # a broken connection: let @recovering on /chats reconnect and retry
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
    try:
        sender = await event.get_sender()  # usually cached from the update itself
    except (ValueError, errors.RPCError):
        sender = None
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
        "call": None,
        "voice": voice_of(event.message),
        "image": image_of(event.message),
        "sender": sender_name(event.message, sender),
        "sender_id": sender_id_of(event.message, sender),
    })


# Telethon's NewMessage skips service messages, but call records are worth showing live.
# Calls only happen in private chats, which arrive as UpdateNewMessage.
@client.on(events.Raw(types.UpdateNewMessage))
async def on_call_record(update):
    message = update.message
    call = call_of(message)
    if call is None:
        return
    await broadcast({
        "type": "message",
        "id": message.id,
        "chat_id": utils.get_peer_id(message.peer_id),
        "topic_id": None,
        "reply_to_id": None,
        "text": "",
        "out": message.out,
        "date": message.date.isoformat(),
        "edited": False,
        "call": call,
    })


# While a call is ringing, Telegram sends call updates, not messages (the call record
# message only appears once the call is over). PhoneCallRequested = someone is calling
# me now; Accepted / PhoneCall (answered somewhere) / Discarded = it stopped ringing.
@client.on(events.Raw(types.UpdatePhoneCall))
async def on_phone_call(update):
    call = update.phone_call
    print(f"Call update: {type(call).__name__}")  # shows in the console when calls arrive
    if isinstance(call, types.PhoneCallRequested):
        await broadcast({
            "type": "call_ringing",
            "call_id": call.id,
            "chat_id": call.admin_id,  # the caller; a private chat's id is the user's id
            "name": await display_name(call.admin_id),
            "video": bool(getattr(call, "video", False)),
        })
    elif isinstance(call, (types.PhoneCallAccepted, types.PhoneCall, types.PhoneCallDiscarded)):
        await broadcast({"type": "call_ended", "call_id": call.id})


async def display_name(user_id):
    """A user's name for the incoming call alert; Telethon usually has them cached from
    the update itself. Falls back to a placeholder rather than failing the alert."""
    try:
        return utils.get_display_name(await client.get_entity(user_id)) or "Someone"
    except (ValueError, errors.RPCError):
        return "Someone"


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
    """Wraps an endpoint: on MTPROTO_CLUSTER_INVALID, reconnect and try once more.

    Requests still waiting on Telegram when another request's recover() disconnects get
    cancelled by Telethon; those wait for that recovery and retry too.
    """
    @functools.wraps(endpoint)  # keeps the signature, which FastAPI reads
    async def wrapper(*args, **kwargs):
        try:
            return await endpoint(*args, **kwargs)
        except errors.RPCError as e:
            if not is_cluster_error(e):
                raise
        except asyncio.CancelledError:
            task = asyncio.current_task()
            if task is not None and task.cancelling():
                raise  # this request itself was cancelled (e.g. the phone hung up)
            # Otherwise only Telethon's pending request was cancelled, by a reconnect
        await recover()  # waits for a recovery already under way, then skips its own
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
    print(f"TG Flip server {VERSION} started successfully")
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


async def chat_json(dialog):
    forum = bool(getattr(dialog.entity, "forum", False))  # a group split into topics
    return {
        "id": dialog.id,
        "name": dialog.name,
        "unread": await forum_unread(dialog) if forum else dialog.unread_count,
        "forum": forum,
        "last": last_message_preview(dialog.message),
        "photo": photo_id_of(dialog.entity),
    }


def photo_id_of(entity):
    """Id of the chat's current profile photo as a string, or None without one.
    It changes when the photo does, so the app knows when its saved copy is stale."""
    photo = getattr(entity, "photo", None)
    pid = getattr(photo, "photo_id", None)  # the *Empty photo types have none
    return str(pid) if pid else None


def shrink_photo(data, size):
    """JPEG bytes resized to size×size (profile photos are square)."""
    image = Image.open(BytesIO(data)).convert("RGB")
    if image.size != (size, size):
        image = image.resize((size, size), Image.LANCZOS)
    out = BytesIO()
    image.save(out, "JPEG", quality=85)
    return out.getvalue()


async def dialogs_by_ids(chat_ids):
    """The chats with these ids, newest activity first, built like iter_dialogs builds
    its own (custom.Dialog), from one GetPeerDialogs request. Chats this account can't
    resolve (left, or never seen) are skipped."""
    peers = []
    for chat_id in chat_ids:
        try:
            peers.append(types.InputDialogPeer(await client.get_input_entity(chat_id)))
        except ValueError:
            pass
    if not peers:
        return []
    r = await client(functions.messages.GetPeerDialogsRequest(peers=peers))
    entities = {
        utils.get_peer_id(x): x
        for x in itertools.chain(r.users, r.chats)
        if not isinstance(x, (types.UserEmpty, types.ChatEmpty))
    }
    messages = {}
    for m in r.messages:
        m._finish_init(client, entities, None)  # as iter_dialogs does
        messages[_dialog_message_key(m.peer_id, m.id)] = m
    dialogs = [
        custom.Dialog(client, d, entities, messages.get(_dialog_message_key(d.peer, d.top_message)))
        for d in r.dialogs
        if utils.get_peer_id(d.peer) in entities
    ]
    oldest = datetime.min.replace(tzinfo=timezone.utc)
    return sorted(dialogs, key=lambda d: d.date or oldest, reverse=True)


# ids: comma-separated chat ids (an app folder); then only those chats, ignoring limit
@api.get("/chats")
@recovering
async def get_chats(limit: int = 20, ids: Optional[str] = None):
    if ids is not None:
        try:
            wanted = [int(i) for i in ids.split(",") if i.strip()]
        except ValueError:
            raise HTTPException(status_code=400, detail="ids must be comma-separated numbers")
        return [await chat_json(d) for d in await dialogs_by_ids(wanted)]
    return [await chat_json(d) async for d in client.iter_dialogs(limit=limit)]


# A person's profile. username and phone are null when not set or hidden by their privacy
# settings. Asks Telegram each time, so it shows what is visible right now.
@api.get("/profile/{user_id}")
@recovering
async def get_profile(user_id: int):
    try:
        user = await client.get_entity(user_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="User not found")
    except errors.RPCError as e:
        bad_request(e)
    if not isinstance(user, types.User):
        raise HTTPException(status_code=404, detail="Not a person")
    # Collectible usernames come as a list; the first active one is the main one
    username = user.username or next((u.username for u in user.usernames or [] if u.active), None)
    return {
        "id": user.id,
        "name": utils.get_display_name(user),
        "username": username,
        "phone": f"+{user.phone}" if user.phone else None,
        "photo": photo_id_of(user),
        "bot": bool(user.bot),
    }


MAX_MEMBERS = 500  # per request; the app asks for more a page at a time


def member_role(participant):
    """"owner", "admin" or None, for both kinds of groups (Chat* in small old-style groups)."""
    if isinstance(participant, (types.ChannelParticipantCreator, types.ChatParticipantCreator)):
        return "owner"
    if isinstance(participant, (types.ChannelParticipantAdmin, types.ChatParticipantAdmin)):
        return "admin"
    return None


# A group's (or channel's) info and its first `members` members, as Telegram lists them
# (recently active first). total is the real member count. members is null where
# Telegram refuses the list (channels, unless I'm an admin); partial is true where a
# group hides its members, and Telegram then lists only some of them (the admins).
@api.get("/group/{chat_id}")
@recovering
async def get_group(chat_id: int, members: int = 50):
    try:
        entity = await client.get_entity(chat_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    if isinstance(entity, types.User):
        raise HTTPException(status_code=404, detail="Not a group")
    info = {
        "id": chat_id,
        "name": utils.get_display_name(entity),
        "photo": photo_id_of(entity),
        "channel": bool(getattr(entity, "broadcast", False)),
        "total": getattr(entity, "participants_count", None),
        "members": None,
        "partial": False,
    }
    # Supergroups and channels: the real count comes only with the full info (where members
    # are hidden, the member list counts just the few it shows)
    if isinstance(entity, types.Channel):
        try:
            full = (await client(functions.channels.GetFullChannelRequest(entity))).full_chat
            info["total"] = full.participants_count
        except errors.RPCError as e:
            bad_request(e)
    limit = max(1, min(members, MAX_MEMBERS))
    try:
        found = await client.get_participants(entity, limit=limit)
    except errors.RPCError as e:
        if is_cluster_error(e):
            raise
        return info  # Telegram refuses the list
    if info["total"] is None:
        info["total"] = found.total
    # Fewer than asked for and fewer than there are: Telegram shows only some
    info["partial"] = len(found) < min(limit, info["total"])
    info["members"] = [
        {"id": u.id, "name": utils.get_display_name(u) or "Deleted account", "photo": photo_id_of(u),
         "role": member_role(getattr(u, "participant", None)), "bot": bool(u.bot)}
        for u in found
    ]
    return info


# photo_id (from /chats) lets a saved copy answer without asking Telegram at all
@api.get("/photo/{chat_id}")
@recovering
async def get_photo(chat_id: int, size: int = 48, photo_id: Optional[str] = None):
    if size not in PHOTO_SIZES:
        raise HTTPException(status_code=400, detail=f"size must be {PHOTO_SIZES.start}-{PHOTO_SIZES.stop - 1}")
    headers = {"Cache-Control": "private, max-age=86400"}

    def cached(pid):
        return os.path.join(PHOTO_CACHE, f"{chat_id}_{pid}_{size}.jpg")

    if photo_id and photo_id.isdigit() and os.path.exists(cached(photo_id)):
        with open(cached(photo_id), "rb") as f:
            return Response(f.read(), media_type="image/jpeg", headers=headers)
    try:
        entity = await client.get_entity(chat_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    current = photo_id_of(entity)
    if current is None:
        raise HTTPException(status_code=404, detail="No photo")
    path = cached(current)
    if not os.path.exists(path):
        try:
            data = await client.download_profile_photo(entity, file=bytes, download_big=False)
        except errors.RPCError as e:
            bad_request(e)
        if not data:
            raise HTTPException(status_code=404, detail="No photo")
        os.makedirs(PHOTO_CACHE, exist_ok=True)
        # Copies of this chat's older photos are no use any more
        for old in os.listdir(PHOTO_CACHE):
            if old.startswith(f"{chat_id}_") and not old.startswith(f"{chat_id}_{current}_"):
                os.remove(os.path.join(PHOTO_CACHE, old))
        with open(path, "wb") as f:
            f.write(shrink_photo(data, size))
    with open(path, "rb") as f:
        return Response(f.read(), media_type="image/jpeg", headers=headers)


IMAGE_CACHE = "image_cache"
SCREEN = (480, 854)  # the flip phone's screen; photos are shrunk to fit inside it


def shrink_to_fit(data, box):
    """JPEG bytes no larger than box (width, height), keeping the picture's shape."""
    image = Image.open(BytesIO(data)).convert("RGB")
    image.thumbnail(box, Image.LANCZOS)  # only ever makes it smaller
    out = BytesIO()
    image.save(out, "JPEG", quality=80)
    return out.getvalue()


# A photo from a message, shrunk to fit the phone's screen (usually 30-60 KB). Telegram
# keeps several sizes; the smallest one that still fills the screen is downloaded, not the
# original, which can be several MB. Saved in image_cache/, so opening it again is instant.
# full=true instead sends the biggest size Telegram keeps (up to 2560 px), unchanged and
# not saved here: for saving a photo on the phone.
@api.get("/image/{chat_id}/{message_id}")
@recovering
async def get_image(chat_id: int, message_id: int, full: bool = False):
    headers = {"Cache-Control": "private, max-age=86400"}
    if full:
        return Response(await full_photo(chat_id, message_id), media_type="image/jpeg", headers=headers)
    path = os.path.join(IMAGE_CACHE, f"{chat_id}_{message_id}.jpg")
    if not os.path.exists(path):
        try:
            message = await client.get_messages(chat_id, ids=message_id)
        except ValueError:
            raise HTTPException(status_code=404, detail="Chat not found")
        except errors.RPCError as e:
            bad_request(e)
        sizes = photo_sizes(message) if message is not None else []
        if not sizes:
            raise HTTPException(status_code=404, detail="No such photo")
        size = next((s for s in sizes if max(s.w, s.h) >= max(SCREEN)), sizes[-1])
        try:
            data = await client.download_media(message, file=bytes, thumb=size.type)  # by type: works for every kind of size
        except errors.RPCError as e:
            bad_request(e)
        if not data:
            raise HTTPException(status_code=404, detail="No such photo")
        os.makedirs(IMAGE_CACHE, exist_ok=True)
        with open(path, "wb") as f:
            f.write(shrink_to_fit(data, SCREEN))
    with open(path, "rb") as f:
        return Response(f.read(), media_type="image/jpeg", headers=headers)


async def full_photo(chat_id, message_id):
    """A photo message's biggest size as JPEG bytes (Telegram keeps photos as JPEG)."""
    try:
        message = await client.get_messages(chat_id, ids=message_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    if message is None or not photo_sizes(message):
        raise HTTPException(status_code=404, detail="No such photo")
    try:
        data = await client.download_media(message, file=bytes)
    except errors.RPCError as e:
        bad_request(e)
    if not data:
        raise HTTPException(status_code=404, detail="No such photo")
    return data


# The voice message's OGG/Opus file as Telegram keeps it (about 100-250 KB a minute).
# Not saved on the server: the phone keeps its own copy.
@api.get("/voice/{chat_id}/{message_id}")
@recovering
async def get_voice(chat_id: int, message_id: int):
    try:
        message = await client.get_messages(chat_id, ids=message_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    if message is None or voice_of(message) is None:
        raise HTTPException(status_code=404, detail="No such voice message")
    try:
        data = await client.download_media(message, file=bytes)
    except errors.RPCError as e:
        bad_request(e)
    return Response(data, media_type="audio/ogg", headers={"Cache-Control": "private, max-age=86400"})


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
         "reply_to_id": reply_of(m), "edited": is_edited(m), "call": call_of(m),
         "voice": voice_of(m), "image": image_of(m), "sender": sender_name(m), "sender_id": sender_id_of(m)}
        for m in found
    ]
    return list(reversed(msgs))


def reply_target(topic_id, reply_to_id):
    """What a new message replies to. Replying to a topic's id is how Telegram files a
    message into that topic. General takes plain messages. A reply to a message stays in
    that message's topic."""
    if reply_to_id is not None:
        return reply_to_id
    if topic_id not in (None, GENERAL_TOPIC):
        return topic_id
    return None


@api.post("/send")
@recovering
async def send(body: SendBody):
    msg = await client.send_message(body.chat_id, body.text,
                                    reply_to=reply_target(body.topic_id, body.reply_to_id))
    return {"id": msg.id}


MAX_VOICE_BYTES = 10 * 1024 * 1024  # about 40 minutes of the phone's recordings
MAX_PHOTO_BYTES = 10 * 1024 * 1024  # Telegram's own limit for photos
PHOTO_TYPES = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}


# The body is the recording itself (OGG/Opus, as the phone's recorder makes it), so no
# form parsing is needed; the rest comes in the query. Sent as a voice message.
@api.post("/send_voice")
@recovering
async def send_voice(request: Request, chat_id: int, duration: int,
                     topic_id: Optional[int] = None, reply_to_id: Optional[int] = None):
    data = await request.body()
    if not data or len(data) > MAX_VOICE_BYTES:
        raise HTTPException(status_code=400, detail="Empty or too large")
    file = BytesIO(data)
    file.name = "voice.ogg"  # tells Telethon the type
    try:
        msg = await client.send_file(
            chat_id, file, voice_note=True,
            attributes=[types.DocumentAttributeAudio(duration=duration, voice=True)],
            reply_to=reply_target(topic_id, reply_to_id))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    return {"id": msg.id}


# The body is the picture itself (JPEG, PNG or WebP; Content-Type says which), the rest
# comes in the query, like /send_voice. Sent as a photo, so Telegram makes its own sizes.
@api.post("/send_photo")
@recovering
async def send_photo(request: Request, chat_id: int, caption: str = "",
                     topic_id: Optional[int] = None, reply_to_id: Optional[int] = None):
    data = await request.body()
    if not data or len(data) > MAX_PHOTO_BYTES:
        raise HTTPException(status_code=400, detail="Empty or too large (at most 10 MB)")
    content_type = request.headers.get("content-type", "image/jpeg").split(";")[0].strip()
    file = BytesIO(data)
    file.name = "photo." + PHOTO_TYPES.get(content_type, "jpg")  # tells Telethon the type
    try:
        msg = await client.send_file(chat_id, file, caption=caption or None, force_document=False,
                                     reply_to=reply_target(topic_id, reply_to_id))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
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

seen_apps = set()  # (address, app version) already printed, so reconnects don't repeat it


def log_app(ws: WebSocket):
    # Apps from 0.7.1 on send x-app-version when they open the live connection
    version = ws.headers.get("x-app-version")
    host = ws.client.host if ws.client else "?"
    if (host, version) in seen_apps:
        return
    seen_apps.add((host, version))
    name = f"TG Flip {version}" if version else "an app older than 0.7.1"
    print(f"App connected from {host}: {name}")


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    try:
        # The first message must be the token (see notes below)
        first = await asyncio.wait_for(ws.receive_text(), timeout=5)
        if not secrets.compare_digest(first.encode(), os.environ["API_TOKEN"].encode()):
            await ws.close(code=1008)
            return
        log_app(ws)
        ws_clients.add(ws)
        while True:
            await ws.receive_text()  # keeps the connection open
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        ws_clients.discard(ws)


app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")