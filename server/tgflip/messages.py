"""Turning Telegram's messages into what the apps get as JSON: replies, topics, calls, voice,
photos, videos, stickers, reactions, custom emoji, senders and previews.
"""
import os
import time
from telethon import errors, functions, types

from tgflip.account import account, client
from tgflip.telegram import is_cluster_error


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


def file_of(message):
    """For a file sent as a document (anything that isn't a voice message, video, round video,
    GIF or sticker): {name, ext, size, mime}; None for anything else. name is the file name
    the sender gave (may be empty), ext its extension with the dot (".docx", may be empty).
    The file itself comes from GET /file."""
    document = getattr(message, "document", None)
    if document is None or not isinstance(message.media, types.MessageMediaDocument):
        return None
    if (voice_of(message) is not None or video_of(message) is not None or round_of(message) is not None
            or getattr(message, "gif", None) is not None or getattr(message, "sticker", None) is not None):
        return None
    attr = next((a for a in document.attributes if isinstance(a, types.DocumentAttributeFilename)), None)
    name = attr.file_name if attr else ""
    return {"name": name, "ext": os.path.splitext(name)[1].lower(), "size": document.size, "mime": document.mime_type or ""}


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
    elif file_of(message) is not None:
        text, kind = message.raw_text or "", "file"
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
        "file": file_of(message),
    }


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


async def learn_live_custom_emoji(reactions):
    """For live events: a failure only leaves "✦", it never stops the event."""
    try:
        await learn_custom_emoji(custom_emoji_ids(reactions))
    except errors.RPCError:
        pass


def photo_id_of(entity):
    """Id of the chat's current profile photo as a string, or None without one.
    It changes when the photo does, so the app knows when its saved copy is stale."""
    photo = getattr(entity, "photo", None)
    pid = getattr(photo, "photo_id", None)  # the *Empty photo types have none
    return str(pid) if pid else None


def reply_target(topic_id, reply_to_id):
    """What a new message replies to. Replying to a topic's id is how Telegram files a
    message into that topic. General takes plain messages. A reply to a message stays in
    that message's topic."""
    if reply_to_id is not None:
        return reply_to_id
    if topic_id not in (None, GENERAL_TOPIC):
        return topic_id
    return None
