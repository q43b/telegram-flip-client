import os
import re
import sys
import logging
import secrets
import asyncio
import functools
import itertools
import time
import tempfile
import json
import copy
from collections import deque
from contextvars import ContextVar
from datetime import datetime, timezone
from contextlib import asynccontextmanager
from http import HTTPStatus
from io import BytesIO

from dotenv import load_dotenv
from fastapi import (APIRouter, Depends, FastAPI, Header, HTTPException, Request, Response,
                     WebSocket, WebSocketDisconnect)
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from starlette.background import BackgroundTask
from typing import Optional

from PIL import Image
from pydantic import BaseModel
from telethon import TelegramClient, connection, errors, events, functions, types, utils
from telethon.client.dialogs import _dialog_message_key  # to build dialogs like iter_dialogs
from telethon.tl import custom

import accounts
import module_loader

load_dotenv()

# The server's own console output, kept for the app's "under the hood" screen (WebSocket
# /logs): the last LOG_KEEP lines, with the time. Everything printed goes through LogTee on
# its way to the console. Sending lines to the app writes nothing to the log itself, so
# watching it can't make it grow.
LOG_KEEP = 500
log_lines = deque(maxlen=LOG_KEEP)
log_listeners = set()  # one asyncio.Queue per open /logs connection
log_loop = None        # the server's event loop, set at startup
ANSI_COLORS = re.compile(r"\x1b\[[0-9;]*m")  # uvicorn colors its words in a terminal


def record_log(text):
    line = f"{time.strftime('%H:%M:%S')} {ANSI_COLORS.sub('', text).rstrip()}"
    log_lines.append(line)
    if log_loop is not None and log_listeners:
        log_loop.call_soon_threadsafe(notify_log_listeners, line)  # safe from any thread


def notify_log_listeners(line):
    for queue in list(log_listeners):
        try:
            queue.put_nowait(line)
        except asyncio.QueueFull:
            pass  # that viewer is far behind; it misses a line rather than holding up the server


class LogTee:
    """Passes console output on unchanged and records each finished line."""

    def __init__(self, stream):
        self.stream = stream
        self.partial = ""

    def write(self, text):
        self.stream.write(text)
        self.partial += text
        while "\n" in self.partial:
            line, self.partial = self.partial.split("\n", 1)
            if line.strip():
                record_log(line)
        return len(text)

    def flush(self):
        self.stream.flush()

    def __getattr__(self, name):  # encoding, isatty, ... as the real console
        return getattr(self.stream, name)


class LogRecorder(logging.Handler):
    """For uvicorn's loggers when they write to the console directly (see start_log_capture)."""

    def emit(self, record):
        record_log(f"{record.levelname}: {record.getMessage()}")


if sys.stdout is not None:
    sys.stdout = LogTee(sys.stdout)
if sys.stderr is not None:
    sys.stderr = LogTee(sys.stderr)


def start_log_capture():
    """Called at startup. run_server.py sets up uvicorn after importing this file, so uvicorn
    already writes through LogTee; `uvicorn server:app` sets up its logging first, with the
    real console, so its loggers get a LogRecorder instead (never both: no doubled lines)."""
    global log_loop
    log_loop = asyncio.get_running_loop()
    logger = logging.getLogger("uvicorn")
    if not any(isinstance(getattr(h, "stream", None), LogTee) for h in logger.handlers):
        logger.addHandler(LogRecorder())
    # AccessLog prints these lines instead, with the account
    logging.getLogger("uvicorn.access").disabled = True


# Printed at startup; build_server.ps1 also names the zip after it
VERSION = "0.16.3-beta"
CODENAME = "Almost Liquid"  # the era's name, printed after the version (from 0.13.0-beta)

def shared_server():
    """SERVER_MODE in .env: "private" (the default: one person's server, every account's app
    may watch the live log) or "shared" (for several people: only admin accounts may, since
    the log shows everyone's addresses and chats). Read each time: maintenance mode can change it."""
    mode = (os.environ.get("SERVER_MODE") or "private").strip().lower()
    if mode not in ("private", "shared"):
        raise RuntimeError(f"SERVER_MODE in .env must be private or shared, not {mode!r}")
    return mode == "shared"


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

class Account:
    """One Telegram account the server works for: its Telegram connection and everything that
    belongs to that account alone, made from its entry in accounts.json (accounts.py). Code
    working for an account gets it with account(), and `client` below is always the current
    account's TelegramClient."""

    def __init__(self, name, token, session, local_favorites, admin=False):
        self.name = name
        self.token = token  # the app token that means this account
        self.admin = admin  # may see the live log on a shared server
        self.client = TelegramClient(session, int(os.environ["API_ID"]), os.environ["API_HASH"], **proxy_args)
        self.ws_clients = set()  # its apps' live connections (several phones can share one account)
        self.telegram_config = None  # Telegram's settings as this account gets them
        self.forum_unread_cache = {}  # chat_id -> (time counted, total unread), see forum_unread
        self.sticker_documents = {}  # sticker id -> document (file references are per account)
        self.sticker_sets = {}  # pack id -> InputStickerSetID
        self.video_downloads = {}  # file path -> running download, see get_video
        self.local_favorites = local_favorites  # its local favourite stickers' file
        # Its own subfolder in every media cache (cache_path): named after its session, which,
        # unlike the account's name, never changes
        self.cache_folder = session
        self.reconnect_lock = asyncio.Lock()
        self.last_recovery = 0.0
        self.watchdog = None  # its watchdog task, while running


# The account the code is running for right now: set for each request by its token
# (current_account), for each live connection, and for each Telegram update (on). A context
# variable, so every request and task keeps its own, like a web framework's current request.
_current_account = ContextVar("account")


def account():
    return _current_account.get()


def use_account(acc):
    _current_account.set(acc)


class CurrentClient:
    """`client` everywhere: the current account's TelegramClient."""

    def __getattr__(self, name):
        return getattr(account().client, name)

    def __call__(self, *args, **kwargs):
        return account().client(*args, **kwargs)


client = CurrentClient()

# Every account in accounts.json (made from .env the first time: the account the server always had)
ACCOUNTS = [Account(**entry) for entry in accounts.load()]

# Telegram update handlers (@on below), registered on every account's client at startup;
# each runs as that account
event_handlers = []


def on(event):
    def register(handler):
        event_handlers.append((event, handler))
        return handler
    return register


def register_handlers(acc):
    for event, handler in event_handlers:
        async def run_as_account(update, handler=handler):
            use_account(acc)
            await handler(update)
        acc.client.add_event_handler(run_as_account, copy.copy(event))  # a builder per client

# The web page lives next to this file, also inside the packaged server
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")

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


def video_of(message):
    """For a plain video: {duration, w, h, size} (seconds, pixels, bytes); None for anything
    else, round videos, GIFs and video stickers included (Telegram keeps GIFs as silent videos
    and video stickers as WebM videos). The file itself comes from GET /video."""
    document = getattr(message, "video", None)
    if document is None or getattr(message, "gif", None) is not None or getattr(message, "sticker", None) is not None:
        return None
    attr = next((a for a in document.attributes if isinstance(a, types.DocumentAttributeVideo)), None)
    if attr is None or attr.round_message:
        return None
    return {"duration": int(attr.duration or 0), "w": attr.w, "h": attr.h, "size": document.size}


def round_of(message):
    """For a round video ("video message"): {duration, size} (seconds, bytes); None for
    anything else. Square videos with a round flag; the file comes from GET /video too."""
    document = getattr(message, "video_note", None)
    if document is None:
        return None
    attr = next((a for a in document.attributes if isinstance(a, types.DocumentAttributeVideo)), None)
    return {"duration": int(attr.duration or 0) if attr else 0, "size": document.size}


CUSTOM_EMOJI = "✦"  # for a custom emoji whose plain emoji isn't known (yet)
PAID_REACTION = "⭐"

# Every custom emoji (a small sticker) names the plain emoji it stands for, its "alt"; reactions
# only give the custom emoji's id. Learned from Telegram once per id and kept: they never change.
custom_emoji_alts = {}  # document id -> plain emoji


def custom_emoji_ids(reactions):
    if reactions is None:
        return []
    return [r.reaction.document_id for r in reactions.results if isinstance(r.reaction, types.ReactionCustomEmoji)]


async def learn_custom_emoji(ids):
    """Asks Telegram for the plain emoji of custom emoji not seen before (200 at a time, its
    limit). On failure they stay "✦"; only a broken connection is passed on (@recovering)."""
    missing = list({i for i in ids if i not in custom_emoji_alts})
    for start in range(0, len(missing), 200):
        try:
            documents = await client(functions.messages.GetCustomEmojiDocumentsRequest(
                document_id=missing[start:start + 200]))
        except errors.RPCError as e:
            if is_cluster_error(e):
                raise
            return
        for document in documents:
            if not isinstance(document, types.Document):
                continue  # DocumentEmpty: an id Telegram doesn't know
            account().sticker_documents[document.id] = document  # /sticker can draw it now
            alt = next((a.alt for a in document.attributes
                        if isinstance(a, types.DocumentAttributeCustomEmoji)), None)
            if alt:
                custom_emoji_alts[document.id] = alt


def reactions_list(reactions):
    """[{emoji, count, mine}] from Telegram's MessageReactions (or None), in Telegram's order.
    mine = I chose this reaction. custom_id = the custom emoji's id (its picture comes from
    /sticker), else None. Custom emoji show as their plain emoji when known (call
    learn_custom_emoji first), else "✦"; paid stars as "⭐". Each custom emoji keeps its own
    entry; the app adds up equal characters when it shows no pictures."""
    if reactions is None or not reactions.results:
        return []
    result = []
    for count in reactions.results:
        reaction = count.reaction
        custom_id = None
        if isinstance(reaction, types.ReactionEmoji):
            emoji = reaction.emoticon
        elif isinstance(reaction, types.ReactionCustomEmoji):
            emoji = custom_emoji_alts.get(reaction.document_id, CUSTOM_EMOJI)
            custom_id = reaction.document_id
        elif type(reaction).__name__ == "ReactionPaid":
            emoji = PAID_REACTION
        else:
            continue
        result.append({"emoji": emoji, "count": count.count, "mine": count.chosen_order is not None,
                       "custom_id": custom_id})
    return result


def reactions_of(message):
    return reactions_list(getattr(message, "reactions", None))


STICKER_KINDS = {"image/webp": "static", "application/x-tgsticker": "animated", "video/webm": "video"}

# Sticker documents seen so far are kept per account (Account.sticker_documents): downloading
# one later needs its document (with the file reference Telegram gave alongside it), not just
# the id. See docs/stickers-plan.md.


def sticker_json(document):
    """{id, emoji, kind} (kind static / animated / video) for a sticker document, which is
    remembered for GET /sticker (the picture) and POST /send_sticker."""
    account().sticker_documents[document.id] = document
    attr = next((a for a in document.attributes if isinstance(a, types.DocumentAttributeSticker)), None)
    return {
        "id": document.id,
        "emoji": (attr.alt if attr else "") or "",
        "kind": STICKER_KINDS.get(document.mime_type, "static"),
    }


def sticker_of(message):
    """For a sticker message: sticker_json; None otherwise."""
    document = getattr(message, "sticker", None)
    return None if document is None else sticker_json(document)


def custom_emoji_of(message):
    """[{offset, length, id}]: the custom emoji in the message's text (offset and length in
    UTF-16 units, as Telegram counts and as Kotlin strings index; the text holds each one's
    plain emoji there). Their pictures come from /sticker."""
    return [{"offset": e.offset, "length": e.length, "id": e.document_id}
            for e in (getattr(message, "entities", None) or [])
            if isinstance(e, types.MessageEntityCustomEmoji)]


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
    e.g. a sticker), "photo" and "video" (text is the caption), "round" (a round video
    message), "sticker" (text is its emoji), "voice" (details in voice, see voice_of), "call" (details in
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
    elif video_of(message) is not None:
        text, kind = message.raw_text or "", "video"
    elif round_of(message) is not None:
        text, kind = "", "round"
    elif sticker_of(message) is not None:
        text, kind = sticker_of(message)["emoji"], "sticker"
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
# for everyone. Asked once per account; they rarely change.
async def get_telegram_config():
    acc = account()
    if acc.telegram_config is None:
        acc.telegram_config = await client(functions.help.GetConfigRequest())
    return acc.telegram_config


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


async def forum_unread(dialog):
    cached = account().forum_unread_cache.get(dialog.id)
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
    account().forum_unread_cache[dialog.id] = (time.monotonic(), total)
    return total


def forget_forum_unread(chat_id):
    account().forum_unread_cache.pop(chat_id, None)


async def broadcast(data):
    """To all of the current account's apps (each phone keeps its own live connection)."""
    clients = account().ws_clients
    for ws in list(clients):
        try:
            await ws.send_json(data)
        except Exception:
            clients.discard(ws)


@on(events.NewMessage)
async def on_new_message(event):
    forget_forum_unread(event.chat_id)
    await learn_live_custom_emoji(getattr(event.message, "reactions", None))
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
        "video": video_of(event.message),
        "round": round_of(event.message),
        "reactions": reactions_of(event.message),
        "sticker": sticker_of(event.message),
        "custom_emoji": custom_emoji_of(event.message),
        "sender": sender_name(event.message, sender),
        "sender_id": sender_id_of(event.message, sender),
    })


# Telethon's NewMessage skips service messages, but call records are worth showing live.
# Calls only happen in private chats, which arrive as UpdateNewMessage.
@on(events.Raw(types.UpdateNewMessage))
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
@on(events.Raw(types.UpdatePhoneCall))
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


@on(events.MessageEdited)
async def on_edited(event):
    await learn_live_custom_emoji(getattr(event.message, "reactions", None))
    await broadcast({
        "type": "edited",
        "id": event.id,
        "chat_id": event.chat_id,
        "text": event.raw_text or "",
        "edited": is_edited(event.message),
        "reactions": reactions_of(event.message),  # some reaction changes arrive as edits
        "custom_emoji": custom_emoji_of(event.message),
    })


async def learn_live_custom_emoji(reactions):
    """For live events: a failure only leaves "✦", it never stops the event."""
    try:
        await learn_custom_emoji(custom_emoji_ids(reactions))
    except errors.RPCError:
        pass


# Reactions added or taken back, by anyone: Telegram sends these as their own update
@on(events.Raw(types.UpdateMessageReactions))
async def on_reactions(update):
    await learn_live_custom_emoji(update.reactions)
    await broadcast({
        "type": "reactions",
        "chat_id": utils.get_peer_id(update.peer),
        "id": update.msg_id,
        "reactions": reactions_list(update.reactions),
    })


@on(events.MessageDeleted)
async def on_deleted(event):
    # Telegram names the chat only for channels and supergroups; elsewhere chat_id is
    # None, but there message ids are unique across all of the account's chats
    await broadcast({"type": "deleted", "chat_id": event.chat_id, "ids": event.deleted_ids})


@on(events.MessageRead(inbox=True))
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


async def recover():
    """Reconnects the current account from scratch. Several requests failing at once trigger
    one reconnect."""
    acc = account()
    async with acc.reconnect_lock:
        if time.monotonic() - acc.last_recovery < 10:
            return  # another request just did it
        print("Telegram connection is broken (MTPROTO_CLUSTER_INVALID); reconnecting")
        await client.disconnect()
        await connect_telegram()
        acc.last_recovery = time.monotonic()
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
    start_log_capture()
    move_old_caches()
    print(f"Server cache: {describe_cache_setting()}")
    clean_caches()
    cleaner = asyncio.create_task(keep_caches_clean())
    for acc in ACCOUNTS:
        use_account(acc)  # the tasks started here keep it (they copy the context)
        register_handlers(acc)
        if await connect_telegram() is None:
            raise RuntimeError(f"Account \"{acc.name}\" is not logged in to Telegram. "
                               "Log it in with tgflip-server --maintenancemode.")
        print(f"Account \"{acc.name}\" connected to Telegram")
        acc.watchdog = asyncio.create_task(watchdog())
    if shared_server():
        print("Shared server: only admin accounts' apps may see this log")
    else:
        print("Private server: every account's app may see this log")
    await module_loader.start_all()
    print(f"TG Flip server {VERSION} \"{CODENAME}\" started successfully")
    yield
    await module_loader.stop_all()
    cleaner.cancel()
    for acc in ACCOUNTS:
        acc.watchdog.cancel()
        await acc.client.disconnect()


def account_with_token(token):
    """The account this app token means, or None. Compared with every account's token."""
    found = None
    for acc in ACCOUNTS:
        if secrets.compare_digest(token.encode(), acc.token.encode()):
            found = acc
    return found


async def current_account(request: Request, x_token: str = Header(default="")):
    """Every request's first step: the token says which account it is for (401 if none), which
    is then the current account for the rest of the request. async, so it runs in the
    request's own context (a plain def would run in another thread and the choice would be lost)."""
    acc = account_with_token(x_token)
    if acc is None:
        raise HTTPException(status_code=401, detail="Bad token")
    use_account(acc)
    request.scope["tgflip_account"] = acc.name  # for its line in the log (AccessLog)
    return acc


class AccessLog:
    """One line per request, like uvicorn's own access log (turned off at startup:
    start_log_capture), plus the account it was for. uvicorn's runs before the token is read,
    so it can't say whose request it was; this one prints when the answer has gone."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        status = 500  # unless an answer starts

        async def send_noting_status(message):
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, send_noting_status)
        finally:
            host, port = scope.get("client") or ("?", 0)
            name = scope.get("tgflip_account")
            whose = f" [{name}]" if name else ""
            path = scope["path"] + (f"?{scope['query_string'].decode()}" if scope["query_string"] else "")
            try:
                phrase = HTTPStatus(status).phrase
            except ValueError:
                phrase = ""
            print(f'INFO:     {host}:{port}{whose} - "{scope["method"]} {path} '
                  f'HTTP/{scope["http_version"]}" {status} {phrase}')


app = FastAPI(lifespan=lifespan)
app.add_middleware(AccessLog)
api = APIRouter(dependencies=[Depends(current_account)])  # token required on these


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


class SendStickerBody(BaseModel):
    chat_id: int
    sticker_id: int
    topic_id: Optional[int] = None
    reply_to_id: Optional[int] = None


class ReactBody(BaseModel):
    chat_id: int
    message_id: int
    emoji: Optional[str] = None  # None or "" = take my reaction back


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


# About the server itself, for the app: its version and whether this account's app may see
# the live log (private server, or an admin on a shared one)
@api.get("/server_info")
async def server_info():
    shared = shared_server()
    return {"version": VERSION, "codename": CODENAME, "shared": shared,
            "log": not shared or account().admin,
            # What installed modules add, e.g. {"animated_stickers": 1} (since 0.15.0-beta).
            # Apps ask for these, never for a module's name (docs/modules-plan.md)
            "capabilities": module_loader.capabilities()}


# The installed modules (since 0.15.0-beta): status "on", "off" or "failed: <reason>"
@api.get("/modules")
async def installed_modules():
    return {"modules": [m.info() for m in module_loader.modules]}


# Messages from the server: a fallback, so a newer server can still tell an older app what it
# needs to know (since 0.14.0-beta). Every kind of information the server tells apps is a
# notice, registered with @notice(kind): a function giving its text now (None = nothing to say).
# Apps list the kinds they can receive in their own form in x-app-knows; /server_messages sends
# the text of every other notice. So a kind gets its own endpoint or event for apps that know
# it, and is registered here too, for the apps that don't yet.
notices = []


def notice(kind):
    def register(text_of):
        notices.append((kind, text_of))
        return text_of
    return register


@notice("server_info")
def server_info_text():
    acc = account()
    who = f'your account is "{acc.name}"' + (", an admin" if acc.admin else "")
    mode = "Shared server" if shared_server() else "Private server"
    return (f'TG Flip server {VERSION} "{CODENAME}". {mode}, {who}. '
            f"Server cache: {describe_cache_setting()}.")


@notice("modules")
def modules_text():
    if not module_loader.modules:
        return None
    listed = ", ".join(f"{m.name} {m.version} ({m.status})" for m in module_loader.modules)
    return f"Modules on this server: {listed}."


@api.get("/server_messages")
async def server_messages(x_app_knows: str = Header(default="")):
    known = {kind.strip() for kind in x_app_knows.split(",") if kind.strip()}
    messages = []
    for kind, text_of in notices:
        if kind not in known:
            text = text_of()
            if text:
                messages.append({"kind": kind, "text": text})
    return {"messages": messages}


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
        return cache_path(PHOTO_CACHE, f"{chat_id}_{pid}_{size}.jpg")

    if photo_id and photo_id.isdigit() and in_cache(cached(photo_id)):
        with open(cached(photo_id), "rb") as f:
            data = f.read()
        sent(cached(photo_id))
        return Response(data, media_type="image/jpeg", headers=headers)
    try:
        entity = await client.get_entity(chat_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    current = photo_id_of(entity)
    if current is None:
        raise HTTPException(status_code=404, detail="No photo")
    path = cached(current)
    if not in_cache(path):
        try:
            data = await client.download_profile_photo(entity, file=bytes, download_big=False)
        except errors.RPCError as e:
            bad_request(e)
        if not data:
            raise HTTPException(status_code=404, detail="No photo")
        # Copies of this chat's older photos are no use any more
        folder = os.path.dirname(path)
        for old in os.listdir(folder):
            if old.startswith(f"{chat_id}_") and not old.startswith(f"{chat_id}_{current}_"):
                os.remove(os.path.join(folder, old))
        with open(path, "wb") as f:
            f.write(shrink_photo(data, size))
        stored()
    with open(path, "rb") as f:
        data = f.read()
    sent(path)
    return Response(data, media_type="image/jpeg", headers=headers)


IMAGE_CACHE = "image_cache"
VIDEO_CACHE = "video_cache"
MAX_VIDEO_BYTES = 100 * 1024 * 1024  # more takes minutes through the proxy, and the phone has little room


def cache_path(folder, name):
    """Where a cached media file lives: in the current account's own subfolder, so one account
    can never get another's pictures by guessing ids (message ids in private chats are per
    account anyway). The one place that builds these paths."""
    folder = os.path.join(folder, account().cache_folder)
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, name)


def move_old_caches():
    """Before 0.13.0 the caches had no account subfolders, and their files all belonged to the
    first account; they move into its subfolder, so nothing has to be downloaded again."""
    first = ACCOUNTS[0].cache_folder
    for folder in (PHOTO_CACHE, IMAGE_CACHE, VIDEO_CACHE, STICKER_CACHE):
        if not os.path.isdir(folder):
            continue
        files = [f for f in os.listdir(folder) if os.path.isfile(os.path.join(folder, f))]
        if not files:
            continue
        os.makedirs(os.path.join(folder, first), exist_ok=True)
        for f in files:
            os.replace(os.path.join(folder, f), os.path.join(folder, first, f))
        print(f"Moved {len(files)} cached files from {folder}/ to {folder}/{first}/")


# How long the server keeps the media files it fetched for the apps: SERVER_CACHE in .env,
# "forever" (the default), a number of days since a file was last used, or "off" (each file is
# deleted once it has been sent). Set at first-run setup or in maintenance mode. Read each
# time, like SERVER_MODE.
CACHE_DAYS_MAX = 100_000
CACHE_MB_MAX = 10_000_000
MB = 1024 * 1024
CACHE_CLEAN_EVERY = 6 * 3600  # seconds
CACHE_OFF_GRACE = 600  # with the cache off, leftovers this old (seconds) go at the next cleaning


def cache_days():
    """None = forever, 0 = off, otherwise days since last use."""
    value = (os.environ.get("SERVER_CACHE") or "forever").strip().lower()
    if value == "forever":
        return None
    if value == "off":
        return 0
    if value.isdigit() and 1 <= int(value) <= CACHE_DAYS_MAX:
        return int(value)
    raise RuntimeError(f"SERVER_CACHE in .env must be forever, off or 1-{CACHE_DAYS_MAX} (days), not {value!r}")


def cache_mb():
    """SERVER_CACHE_MB in .env: the most the whole cache may take, in MB, or None (empty, the
    default) for no limit. Each account may use an equal share of it (cache_share_mb)."""
    value = (os.environ.get("SERVER_CACHE_MB") or "").strip().lower()
    if value in ("", "none"):
        return None
    if value.isdigit() and 1 <= int(value) <= CACHE_MB_MAX:
        return int(value)
    raise RuntimeError(f"SERVER_CACHE_MB in .env must be empty (no limit) or 1-{CACHE_MB_MAX}, not {value!r}")


def cache_share_mb():
    """Each account's share of the size limit (None = no limit)."""
    total = cache_mb()
    return None if total is None else total / len(ACCOUNTS)


def describe_cache_setting():
    days = cache_days()
    if days == 0:
        return "off (media files are deleted once sent)"
    if days is None:
        text = "media files are kept forever"
    else:
        text = f"media files are kept for {days} day{'s' if days != 1 else ''} since last used"
    total = cache_mb()
    if total is not None:
        text += f", at most {total} MB in all ({cache_share_mb():.0f} MB per account)"
    return text


def in_cache(path):
    """True if the file is cached; marks it as just used (its modified time), which is what
    the cleaning goes by."""
    if not os.path.exists(path):
        return False
    try:
        os.utime(path)
    except OSError:
        pass
    return True


def sent(path):
    """Called once a cached file has been sent: with the cache off, it goes."""
    if cache_days() == 0:
        try:
            os.remove(path)
        except OSError:
            pass  # still in use (another request): the next cleaning takes it


def cached_files():
    """Every cached file as (path, owner, last used, size); owner = the account's cache folder
    (a removed account's folder still counts towards the whole cache)."""
    found = []
    for folder in (PHOTO_CACHE, IMAGE_CACHE, VIDEO_CACHE, STICKER_CACHE, *module_loader.cache_roots()):
        if not os.path.isdir(folder):
            continue
        for owner in os.listdir(folder):
            for parent, _, files in os.walk(os.path.join(folder, owner)):
                for name in files:
                    path = os.path.join(parent, name)
                    try:
                        info = os.stat(path)
                    except OSError:
                        continue
                    found.append((path, owner, info.st_mtime, info.st_size))
    return found


def least_used_over(files, limit):
    """Of these files, the least recently used ones that must go to fit in limit bytes."""
    over = sum(f[3] for f in files) - limit
    going = []
    for f in sorted(files, key=lambda f: f[2]):
        if over <= 0:
            break
        going.append(f)
        over -= f[3]
    return going


def clean_caches():
    """Deletes cached files (every account's) unused for longer than the setting allows, then,
    with a size limit, each account's least recently used files beyond its share, then any
    beyond the limit for the whole cache."""
    days, total = cache_days(), cache_mb()
    if days is None and total is None:
        return
    files = cached_files()
    going = []
    if days is not None:
        oldest = time.time() - (days * 86400 if days else CACHE_OFF_GRACE)
        going = [f for f in files if f[2] < oldest]
    if total is not None and days != 0:
        kept = [f for f in files if f not in going]
        for owner in {f[1] for f in kept}:
            going += least_used_over([f for f in kept if f[1] == owner], cache_share_mb() * MB)
        going += least_used_over([f for f in kept if f not in going], total * MB)
    count = size = 0
    for path, _, _, file_size in going:
        try:
            os.remove(path)
            count += 1
            size += file_size
        except OSError:
            pass  # in use right now: next time
    if count:
        print(f"Server cache: removed {count} files ({size / MB:.1f} MB)")


cleaning_soon = False  # a size check is already waiting


def stored():
    """A new file went into the cache: with a size limit, check the sizes in a moment (one
    check for many new files), so a big video doesn't stay over the limit for hours."""
    global cleaning_soon
    if cache_mb() is None or cache_days() == 0 or cleaning_soon:
        return
    cleaning_soon = True

    def clean():
        global cleaning_soon
        cleaning_soon = False
        clean_caches()

    asyncio.get_running_loop().call_later(30, clean)


async def keep_caches_clean():
    while True:
        await asyncio.sleep(CACHE_CLEAN_EVERY)
        clean_caches()


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
    path = cache_path(IMAGE_CACHE, f"{chat_id}_{message_id}.jpg")
    if not in_cache(path):
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
        with open(path, "wb") as f:
            f.write(shrink_to_fit(data, SCREEN))
        stored()
    with open(path, "rb") as f:
        data = f.read()
    sent(path)
    return Response(data, media_type="image/jpeg", headers=headers)


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


STICKER_CACHE = "sticker_cache"
STICKER_SIZES = range(32, 513)  # Telegram's stickers are 512 px


async def sticker_document(document_id, chat_id, message_id):
    """The sticker's document, fresh: read again from the message it came in, or for a custom
    emoji (no message given) asked by id alone. Used after a server restart, or when a
    remembered document's file reference expired."""
    if chat_id is not None and message_id is not None:
        message = await client.get_messages(chat_id, ids=message_id)
        if message is not None and sticker_of(message) is not None and message.sticker.id == document_id:
            return message.sticker
        return account().sticker_documents.get(document_id)
    documents = await client(functions.messages.GetCustomEmojiDocumentsRequest(document_id=[document_id]))
    for document in documents:
        if isinstance(document, types.Document):  # an unknown id comes back as DocumentEmpty
            account().sticker_documents[document.id] = document
    return account().sticker_documents.get(document_id)


def still_sticker(data, size):
    """PNG bytes at most size×size, transparency kept; None when the picture is empty (some
    animated custom emoji only have an outline, no real still preview)."""
    image = Image.open(BytesIO(data)).convert("RGBA")
    if image.getbbox() is None:  # fully transparent
        return None
    image.thumbnail((size, size), Image.LANCZOS)
    out = BytesIO()
    image.save(out, "PNG", optimize=True)
    return out.getvalue()


# A sticker as a still picture (PNG, at most size×size): a static sticker's own image; for
# animated and video stickers, the still preview Telegram makes for every sticker. Cached in
# sticker_cache/. chat_id and message_id say where it came from, in case the server has to
# read the message again; without them the id is taken as a custom emoji's (they can be
# asked by id alone). 404 when there is nothing to show (the app then shows text).
async def sticker_download(document_id, chat_id, message_id, still):
    """A sticker's document and its file: for still=True the picture a still is made from (the
    sticker itself if static, else Telegram's preview), otherwise the whole file (WebP, TGS or
    WebM). A file reference that expired is fetched again once. 404 when unknown."""
    document = account().sticker_documents.get(document_id)
    try:
        if document is None:
            document = await sticker_document(document_id, chat_id, message_id)
        if document is None:
            raise HTTPException(status_code=404, detail="Unknown sticker")
        thumb = -1 if still and document.mime_type != "image/webp" else None
        try:
            return document, await client.download_media(document, file=bytes, thumb=thumb)
        except errors.FileReferenceExpiredError:
            document = await sticker_document(document_id, chat_id, message_id)
            if document is None:
                raise HTTPException(status_code=404, detail="Sticker no longer available")
            return document, await client.download_media(document, file=bytes, thumb=thumb)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)


# The hook a module answers to make animated stickers move (since 0.16.0-beta, the animated
# module): sticker.animate(data, kind, size) with the sticker's whole file as bytes, kind
# "animated" (TGS, Lottie) or "video" (WebM), and the size in px; returns an animated WebP of at
# most size x size, as bytes, or None. Only plain values cross, so module API 1 never depends
# on Telethon's objects.
STICKER_ANIMATE = "sticker.animate"
animation_failed = set()  # (document id, size) a module couldn't animate: not asked again


async def animated_sticker(document_id, size, chat_id, message_id):
    """The animated WebP of an animated or video sticker, made by a module and cached, or None
    (no such module, a static sticker, or it failed): then the still picture is used. The cached
    file is named after the module version that made it (since 0.16.3-beta:
    <id>_anim_<size>_animated-1.1.0.webp), so updating the module makes them again."""
    maker = module_loader.hook_maker(STICKER_ANIMATE)
    path = cache_path(STICKER_CACHE, f"{document_id}_anim_{size}_{maker}.webp")
    if in_cache(path):
        with open(path, "rb") as f:
            webp = f.read()
        sent(path)
        return webp
    if (document_id, size) in animation_failed:
        return None
    known = account().sticker_documents.get(document_id)
    if known is not None and STICKER_KINDS.get(known.mime_type) not in ("animated", "video"):
        return None  # static: nothing to animate, and nothing downloaded to find out
    document, data = await sticker_download(document_id, chat_id, message_id, still=False)
    kind = STICKER_KINDS.get(document.mime_type)
    if kind not in ("animated", "video") or not data:
        return None
    started = time.monotonic()
    webp = await module_loader.call_hook(STICKER_ANIMATE, data, kind, size, thread=True)
    if not isinstance(webp, (bytes, bytearray)) or not webp:
        animation_failed.add((document_id, size))
        return None
    name = {"animated": "Lottie (TGS)", "video": "video (WebM)"}[kind]
    print(f"Animated a {name} sticker at {size} px: {len(data) / 1024:.0f} KB -> "
          f"{len(webp) / 1024:.0f} KB in {time.monotonic() - started:.1f} s")
    with open(path, "wb") as f:
        f.write(webp)
    # Older versions of it (another module version, or from before 0.16.3) are no use any more
    folder, mine = os.path.split(path)
    for old in os.listdir(folder):
        if old.startswith(f"{document_id}_anim_{size}") and old.endswith(".webp") and old != mine:
            os.remove(os.path.join(folder, old))
    stored()
    sent(path)
    return bytes(webp)


@api.get("/sticker/{document_id}")
@recovering
async def get_sticker(document_id: int, size: int = 128, chat_id: Optional[int] = None,
                      message_id: Optional[int] = None, animated: bool = False):
    if size not in STICKER_SIZES:
        raise HTTPException(status_code=400, detail="size must be 32-512")
    headers = {"Cache-Control": "private, max-age=604800"}  # a sticker never changes
    # animated=true (apps from 0.16.0-beta, with a module answering sticker.animate): an
    # animated WebP; otherwise, or if it can't be made, the still PNG as always
    if animated and module_loader.has_hook(STICKER_ANIMATE):
        webp = await animated_sticker(document_id, size, chat_id, message_id)
        if webp is not None:
            return Response(webp, media_type="image/webp", headers=headers)
    path = cache_path(STICKER_CACHE, f"{document_id}_still_{size}.png")
    if not in_cache(path):
        _, data = await sticker_download(document_id, chat_id, message_id, still=True)
        picture = still_sticker(data, size) if data else None
        if picture is None:
            raise HTTPException(status_code=404, detail="No picture for this sticker")
        with open(path, "wb") as f:
            f.write(picture)
        stored()
    with open(path, "rb") as f:
        data = f.read()
    sent(path)
    return Response(data, media_type="image/png", headers=headers)


# Downloads still running, by file: a second request for the same video waits for the first
# download instead of starting its own


async def download_video(chat_id, message_id, path):
    try:
        message = await client.get_messages(chat_id, ids=message_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    info = (video_of(message) or round_of(message)) if message is not None else None
    if info is None:
        raise HTTPException(status_code=404, detail="No such video")
    if info["size"] > MAX_VIDEO_BYTES:
        raise HTTPException(status_code=413, detail="Video too big (over 100 MB)")
    part = path + ".part"  # renamed when complete, so a half download is never served
    await client.download_media(message, file=part)
    os.replace(part, path)
    stored()


# A video or round video message, as Telegram keeps it (no converting yet). Downloaded once from Telegram into
# video_cache/ and then sent from there; the answer starts only when that download is done.
@api.get("/video/{chat_id}/{message_id}")
@recovering
async def get_video(chat_id: int, message_id: int):
    path = cache_path(VIDEO_CACHE, f"{chat_id}_{message_id}.mp4")
    if not in_cache(path):
        task = account().video_downloads.get(path)
        if task is None:
            task = asyncio.create_task(download_video(chat_id, message_id, path))
            account().video_downloads[path] = task
            task.add_done_callback(lambda _: account().video_downloads.pop(path, None))
        try:
            await asyncio.shield(task)  # the phone giving up must not stop a shared download
        except errors.RPCError as e:
            bad_request(e)
    # With the cache off, it goes once sent (if another request is still sending it, the
    # removal fails and the next cleaning takes it)
    return FileResponse(path, media_type="video/mp4", background=BackgroundTask(sent, path))


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
    await learn_custom_emoji([i for m in found for i in custom_emoji_ids(getattr(m, "reactions", None))])
    msgs = [
        {"id": m.id, "text": m.raw_text or "", "out": m.out, "date": m.date.isoformat(),
         "reply_to_id": reply_of(m), "edited": is_edited(m), "call": call_of(m),
         "voice": voice_of(m), "image": image_of(m), "video": video_of(m),
         "round": round_of(m), "reactions": reactions_of(m),
         "sticker": sticker_of(m), "custom_emoji": custom_emoji_of(m), "sender": sender_name(m), "sender_id": sender_id_of(m)}
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


MAX_THUMB_BYTES = 200 * 1024  # Telegram's limit for a video's preview picture


# Sends a video. The body is the phone's preview picture (thumb_size bytes of JPEG, may be 0)
# followed by the video file. Telegram only shows a video's length, shape and preview when
# the sender gives them, and the server can't read them from the file, so the phone does
# (duration, w, h). The body is written to disk as it arrives (up to 100 MB, never held in
# memory), then uploaded; a reconnect retries only the upload.
@api.post("/send_video")
async def send_video(request: Request, chat_id: int, duration: int, w: int, h: int,
                     thumb_size: int = 0, caption: str = "",
                     topic_id: Optional[int] = None, reply_to_id: Optional[int] = None):
    if not 0 <= thumb_size <= MAX_THUMB_BYTES:
        raise HTTPException(status_code=400, detail="Bad preview size")
    with tempfile.TemporaryDirectory() as folder:
        thumb_path = os.path.join(folder, "thumb.jpg")
        video_path = os.path.join(folder, "video.mp4")
        received = 0
        with open(thumb_path, "wb") as thumb, open(video_path, "wb") as video:
            async for chunk in request.stream():
                if received < thumb_size:  # the first thumb_size bytes are the preview
                    split = thumb_size - received
                    thumb.write(chunk[:split])
                    video.write(chunk[split:])
                else:
                    video.write(chunk)
                received += len(chunk)
                if received > thumb_size + MAX_VIDEO_BYTES:
                    raise HTTPException(status_code=413, detail="Video too big (over 100 MB)")
        if received <= thumb_size:
            raise HTTPException(status_code=400, detail="No video")
        msg = await upload_video(chat_id, video_path, thumb_path if thumb_size else None,
                                 duration, w, h, caption, reply_target(topic_id, reply_to_id))
    return {"id": msg.id}


@recovering
async def upload_video(chat_id, video_path, thumb_path, duration, w, h, caption, reply_to):
    try:
        return await client.send_file(
            chat_id, video_path, caption=caption or None, thumb=thumb_path, reply_to=reply_to,
            force_document=False, supports_streaming=True,
            attributes=[types.DocumentAttributeVideo(duration=duration, w=w, h=h, supports_streaming=True)])
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)


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


# My installed sticker packs are kept per account (Account.sticker_sets), by id: opening one
# needs its access hash too


async def installed_sticker_sets():
    found = await client(functions.messages.GetAllStickersRequest(hash=0))
    for pack in found.sets:
        account().sticker_sets[pack.id] = types.InputStickerSetID(id=pack.id, access_hash=pack.access_hash)
    return found.sets


# My sticker packs, in my order, for the app's sticker screen (it puts Recent and Favorites
# first, which come from /sticker_set/recent and /sticker_set/faved)
@api.get("/sticker_sets")
@recovering
async def get_sticker_sets():
    try:
        packs = await installed_sticker_sets()
    except errors.RPCError as e:
        bad_request(e)
    return {"packs": [{"id": p.id, "title": p.title, "count": p.count} for p in packs]}


# "Local favourites": the app's own favourite stickers, with no limit (Telegram's Favorites
# allow 5 without Premium), never sent to Telegram. Kept in this file next to .env, newest
# first, as {id, set_id, set_hash}: the pack each came from, so the server can load the
# sticker again after a restart (drawing and sending need its full document).
# One file per account (Account.local_favorites, named in accounts.json).


def load_local_favorites():
    try:
        with open(account().local_favorites, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return []


def save_local_favorites(entries):
    path = account().local_favorites
    part = path + ".part"  # replaced in one step, so a crash never leaves half a file
    with open(part, "w", encoding="utf-8") as f:
        json.dump(entries, f)
    os.replace(part, path)


async def local_favorite_documents():
    """The local favourites' documents, in the list's order: each pack is loaded once.
    Stickers whose pack is gone are left out."""
    entries = load_local_favorites()
    found = {}
    for set_id, set_hash in {(e["set_id"], e["set_hash"]) for e in entries}:
        try:
            result = await client(functions.messages.GetStickerSetRequest(
                stickerset=types.InputStickerSetID(id=set_id, access_hash=set_hash), hash=0))
        except errors.RPCError as e:
            if is_cluster_error(e):
                raise
            continue  # the pack was deleted: its stickers drop out
        for document in result.documents:
            found[document.id] = document
    return [found[e["id"]] for e in entries if e["id"] in found]


# Adds a sticker the server has seen to the local favourites (first in the list), or with
# remove=true takes it out. Stickers that belong to no pack can't be kept (no way to reload).
@api.post("/sticker/{sticker_id}/local_favorite")
async def local_favorite(sticker_id: int, remove: bool = False):
    entries = [e for e in load_local_favorites() if e["id"] != sticker_id]
    if not remove:
        document = account().sticker_documents.get(sticker_id)
        if document is None:
            raise HTTPException(status_code=404, detail="Unknown sticker: open its pack again")
        attr = next((a for a in document.attributes if isinstance(a, types.DocumentAttributeSticker)), None)
        if attr is None or not isinstance(attr.stickerset, types.InputStickerSetID):
            raise HTTPException(status_code=400, detail="This sticker belongs to no pack")
        entries.insert(0, {"id": sticker_id, "set_id": attr.stickerset.id, "set_hash": attr.stickerset.access_hash})
    save_local_favorites(entries)
    return {"ok": True}


# One pack's stickers ({id, emoji, kind} each, remembered for /sticker and /send_sticker):
# "recent" = my recently used, "faved" = my Telegram favorites, "local" = the local
# favourites above, or an installed pack's id
@api.get("/sticker_set/{pack}")
@recovering
async def get_sticker_set(pack: str):
    try:
        if pack == "local":
            documents = await local_favorite_documents()
        elif pack == "recent":
            documents = (await client(functions.messages.GetRecentStickersRequest(hash=0))).stickers
        elif pack == "faved":
            documents = (await client(functions.messages.GetFavedStickersRequest(hash=0))).stickers
        else:
            try:
                pack_id = int(pack)
            except ValueError:
                raise HTTPException(status_code=404, detail="No such pack")
            if pack_id not in account().sticker_sets:
                await installed_sticker_sets()
            if pack_id not in account().sticker_sets:
                raise HTTPException(status_code=404, detail="No such pack")
            found = await client(functions.messages.GetStickerSetRequest(stickerset=account().sticker_sets[pack_id], hash=0))
            documents = found.documents
    except errors.RPCError as e:
        bad_request(e)
    return {"stickers": [sticker_json(d) for d in documents]}


# The pack a sticker comes from (message menu > View sticker pack): {pack: {id, title, count,
# installed}, stickers: [...]}. chat_id and message_id as in /sticker, in case the server has
# to read the message again. 404 for stickers that belong to no pack.
@api.get("/sticker_set_of/{sticker_id}")
@recovering
async def get_sticker_set_of(sticker_id: int, chat_id: Optional[int] = None, message_id: Optional[int] = None):
    try:
        document = account().sticker_documents.get(sticker_id) or await sticker_document(sticker_id, chat_id, message_id)
        attr = None if document is None else next(
            (a for a in document.attributes if isinstance(a, types.DocumentAttributeSticker)), None)
        if attr is None or not isinstance(attr.stickerset, types.InputStickerSetID):
            raise HTTPException(status_code=404, detail="This sticker belongs to no pack")
        found = await client(functions.messages.GetStickerSetRequest(stickerset=attr.stickerset, hash=0))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    pack = found.set
    account().sticker_sets[pack.id] = types.InputStickerSetID(id=pack.id, access_hash=pack.access_hash)
    return {
        "pack": {"id": pack.id, "title": pack.title, "count": pack.count,
                 "installed": pack.installed_date is not None and not pack.archived},
        "stickers": [sticker_json(d) for d in found.documents],
    }


# Adds a sticker the server has seen to my Telegram Favorites, or with unfave=true takes it out.
# Telegram allows 5 favorites without Premium; a refusal's reason goes to the log.
@api.post("/sticker/{sticker_id}/fave")
@recovering
async def fave_sticker(sticker_id: int, unfave: bool = False):
    document = account().sticker_documents.get(sticker_id)
    if document is None:
        raise HTTPException(status_code=404, detail="Unknown sticker: open its pack again")
    try:
        await client(functions.messages.FaveStickerRequest(id=utils.get_input_document(document), unfave=unfave))
    except errors.RPCError as e:
        print(f"Telegram refused to {'unfave' if unfave else 'fave'} a sticker: {e}")  # for the log screen
        bad_request(e)
    return {"ok": True}


# Adds a pack to my packs (one the server has seen: listed, or opened from a sticker)
@api.post("/sticker_set/{pack_id}/install")
@recovering
async def install_sticker_set(pack_id: int):
    if pack_id not in account().sticker_sets:
        raise HTTPException(status_code=404, detail="Unknown pack: open it again")
    try:
        await client(functions.messages.InstallStickerSetRequest(stickerset=account().sticker_sets[pack_id], archived=False))
    except errors.RPCError as e:
        bad_request(e)
    return {"ok": True}


# Sends a sticker the server has seen (in a pack listed above, or in a message); topics and
# replies as in /send. Answers with the new message's id and the sticker.
@api.post("/send_sticker")
@recovering
async def send_sticker(body: SendStickerBody):
    document = account().sticker_documents.get(body.sticker_id)
    if document is None:
        raise HTTPException(status_code=404, detail="Unknown sticker: open its pack again")
    try:
        msg = await client.send_file(body.chat_id, document,
                                     reply_to=reply_target(body.topic_id, body.reply_to_id))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    return {"id": msg.id, "sticker": sticker_json(document)}


# Telegram's standard reactions anyone may choose, in its order. Asked once; rarely changes.
standard_reactions = None


async def get_standard_reactions():
    global standard_reactions
    if standard_reactions is None:
        found = await client(functions.messages.GetAvailableReactionsRequest(hash=0))
        standard_reactions = [r.reaction for r in found.reactions if not r.inactive]
    return standard_reactions


async def get_top_reactions():
    """The reactions I use most, for putting them first. Nice to have: [] if it fails."""
    try:
        found = await client(functions.messages.GetTopReactionsRequest(limit=20, hash=0))
    except errors.RPCError as e:
        if is_cluster_error(e):
            raise
        return []
    return [r.emoticon for r in getattr(found, "reactions", []) if isinstance(r, types.ReactionEmoji)]


async def allowed_reactions(chat_id):
    """The plain emoji reactions this chat allows: all in private chats; groups and channels
    can allow all, some or none (their available_reactions). Custom emoji are never offered.
    Saved Messages: none without Premium (Telegram uses reactions there as Premium "tags")."""
    standard = await get_standard_reactions()
    entity = await client.get_entity(chat_id)
    if isinstance(entity, types.User):
        if entity.is_self and not entity.premium:
            return []
        return standard
    if isinstance(entity, types.Channel):
        full = await client(functions.channels.GetFullChannelRequest(entity))
    else:
        full = await client(functions.messages.GetFullChatRequest(entity.id))
    setting = getattr(full.full_chat, "available_reactions", None)
    if setting is None or isinstance(setting, types.ChatReactionsAll):
        return standard
    if isinstance(setting, types.ChatReactionsSome):
        return [r.emoticon for r in setting.reactions if isinstance(r, types.ReactionEmoji)]
    return []  # ChatReactionsNone


# The reactions I can choose in this chat, my most used first. [] = turned off there.
@api.get("/reactions/{chat_id}")
@recovering
async def get_reactions(chat_id: int):
    try:
        allowed = await allowed_reactions(chat_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    top = [e for e in await get_top_reactions() if e in allowed]
    return {"reactions": top + [e for e in allowed if e not in top]}


# Sets my reaction to one emoji (replacing any I had), or takes it back. Answers with the
# message's reactions as they are now.
@api.post("/react")
@recovering
async def react(body: ReactBody):
    reaction = [types.ReactionEmoji(emoticon=body.emoji)] if body.emoji else []
    try:
        result = await client(functions.messages.SendReactionRequest(
            peer=await client.get_input_entity(body.chat_id), msg_id=body.message_id, reaction=reaction))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        print(f"Telegram refused a reaction in {body.chat_id}: {e}")  # for the log screen
        bad_request(e)
    # Telegram's answer has the new reactions in one of several shapes (its own reactions
    # update, or the edited message, depending on the chat); failing those, read the message.
    # Telegram doesn't send me a live update for my own reaction, so the app needs this answer.
    reactions = None
    for update in getattr(result, "updates", []):
        if isinstance(update, types.UpdateMessageReactions):
            reactions = update.reactions
        elif isinstance(update, (types.UpdateEditMessage, types.UpdateEditChannelMessage)):
            reactions = getattr(update.message, "reactions", None)
        else:
            continue
        break
    else:
        message = await client.get_messages(body.chat_id, ids=body.message_id)
        reactions = getattr(message, "reactions", None)
    await learn_live_custom_emoji(reactions)
    return {"reactions": reactions_list(reactions)}


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

seen_apps = set()  # (address [account], app version) already printed, so reconnects don't repeat it


def log_app(ws: WebSocket):
    # Apps from 0.7.1 on send x-app-version when they open the live connection
    version = ws.headers.get("x-app-version")
    host = client_host(ws)  # with its account
    if (host, version) in seen_apps:
        return
    seen_apps.add((host, version))
    name = f"TG Flip {version}" if version else "an app older than 0.7.1"
    print(f"App connected from {host}: {name}")


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    try:
        if not await ws_token_ok(ws):
            return
        log_app(ws)
        account().ws_clients.add(ws)
        while True:
            await ws.receive_text()  # keeps the connection open
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        acc = _current_account.get(None)
        if acc is not None:
            acc.ws_clients.discard(ws)


def client_host(ws):
    """The app's address and, once its token is known, its account: "192.168.1.5 [main]"."""
    host = ws.client.host if ws.client else "an app"
    acc = _current_account.get(None)
    return f"{host} [{acc.name}]" if acc is not None else host


async def ws_token_ok(ws):
    """The first message must be the token, within 5 seconds (it is not sent in the URL, so it
    never ends up in logs); its account becomes the connection's current account. Closes the
    connection when it is wrong."""
    first = await asyncio.wait_for(ws.receive_text(), timeout=5)
    acc = account_with_token(first)
    if acc is not None:
        use_account(acc)
        return True
    await ws.close(code=1008)
    return False


# Apps before this can't tell a private server's log from a shared one's, so they get only this
# line instead of the log
LOGS_MIN_APP = "0.13.0-beta"
LOGS_OLD_APP_LINE = "Client too old to verify whether logs are private or shared"
LOG_PRIVATE = 4003  # /logs close code: a shared server, and this account is not an admin


def app_version_key(version):
    """For comparing app versions: "0.12.1-beta" -> (1, 0, 12, 1). Every beta comes after every
    alpha (they started over at 0.1.0-beta), a release without a tag after both; "-dev" (debug
    builds) is ignored. None for a missing or unreadable version."""
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta))?(?:-dev)?", version or "")
    if match is None:
        return None
    stage = {"alpha": 0, "beta": 1, None: 2}[match.group(4)]
    return (stage, *map(int, match.group(1, 2, 3)))


def app_is_older_than(ws, version):
    key = app_version_key(ws.headers.get("x-app-version"))
    return key is None or key < app_version_key(version)


# The app's "under the hood" screen: first the kept lines (one JSON list), then each new line
# as it is printed (a list of one). Nothing is logged per line sent; opening and closing the
# screen each leave one line ("Started/Stopped live sharing the log"), so every viewing shows
# as a pair in the log.
@app.websocket("/logs")
async def logs_endpoint(ws: WebSocket):
    await ws.accept()
    queue = asyncio.Queue(maxsize=1000)
    sender = None
    try:
        if not await ws_token_ok(ws):
            return
        if app_is_older_than(ws, LOGS_MIN_APP):
            # Stays open, so the app doesn't keep reconnecting, but nothing more is sent
            await ws.send_json([LOGS_OLD_APP_LINE])
            print(f"Not sharing the log with {client_host(ws)}: the app is older than {LOGS_MIN_APP}")
            while True:
                await ws.receive_text()
        if shared_server() and not account().admin:
            # 4003 tells the app the log is private here, so it says so and stops trying
            print(f"Not sharing the log with {client_host(ws)}: shared server, not an admin")
            await ws.close(code=LOG_PRIVATE)
            return
        # Printed before this viewer listens, so it arrives once, as the last kept line
        print(f"Started live sharing the log with {client_host(ws)}")
        log_listeners.add(queue)
        await ws.send_json(list(log_lines))

        async def send_new_lines():
            while True:
                await ws.send_json([await queue.get()])

        sender = asyncio.create_task(send_new_lines())
        while True:
            await ws.receive_text()  # only to notice when the app closes the screen
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        if queue in log_listeners:
            log_listeners.discard(queue)
            print(f"Stopped live sharing the log with {client_host(ws)}")
        if sender is not None:
            sender.cancel()


app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")


def load_modules():
    """The installed modules (module_loader.py, docs/modules-plan.md). At import, except in
    maintenance mode (run_server.py sets TGFLIP_LOAD_MODULES_LATER), where the menu may still
    replace their files: then just before the server starts."""
    module_loader.load_all(sys.modules[__name__])


if not os.environ.get("TGFLIP_LOAD_MODULES_LATER"):
    load_modules()