"""Chats, topics, messages, profiles and groups: listing, sending, editing, deleting,
forwarding, marking as read.
"""
import itertools
import time
from datetime import datetime, timezone
from fastapi import HTTPException
from typing import Optional
from telethon import errors, functions, types, utils
from telethon.client.dialogs import _dialog_message_key
from telethon.tl import custom

from tgflip.account import client
from tgflip.api import api
from tgflip.bodies import DeleteBody, EditBody, ForwardBody, SendBody
from tgflip.messages import call_of, custom_emoji_ids, file_of, custom_emoji_of, forget_forum_unread, forum_unread, get_forum_topics, image_of, is_edited, last_message_preview, learn_custom_emoji, photo_id_of, reactions_of, reply_of, reply_target, round_of, sender_id_of, sender_name, sticker_of, topic_messages, video_of, voice_of
from tgflip.telegram import bad_request, can_delete_others, get_telegram_config, is_cluster_error, recovering


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
         "round": round_of(m), "file": file_of(m), "reactions": reactions_of(m),
         "sticker": sticker_of(m), "custom_emoji": custom_emoji_of(m), "sender": sender_name(m), "sender_id": sender_id_of(m)}
        for m in found
    ]
    return list(reversed(msgs))


@api.post("/send")
@recovering
async def send(body: SendBody):
    msg = await client.send_message(body.chat_id, body.text,
                                    reply_to=reply_target(body.topic_id, body.reply_to_id))
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
