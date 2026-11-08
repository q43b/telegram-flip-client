"""Telegram's live updates, passed on to every app of the account over /ws (broadcast).
"""
import copy

from telethon import errors, events, types, utils

import module_loader

from tgflip.account import account, client, on
from tgflip.messages import call_of, custom_emoji_of, file_of, forget_forum_unread, image_of, is_edited, learn_live_custom_emoji, reactions_list, reactions_of, reply_of, round_of, sender_id_of, sender_name, sticker_of, topic_of, video_of, voice_of


async def broadcast(data):
    """To all of the current account's apps (each phone keeps its own live connection). With
    modules' live.event filters (since 1.4.0), each connection gets the event as they made it
    for that app (they see the app's version): on a copy of its own, or not at all (DROP)."""
    acc = account()
    clients = acc.ws_clients
    filtering = module_loader.has_filter("live.event")
    for ws in list(clients):
        payload = data
        if filtering:
            info = module_loader.Traffic(account=acc.name, host=ws.client.host if ws.client else None,
                                         client_version=getattr(ws.state, "app_version", None))
            payload = await module_loader.call_filters("live.event", copy.deepcopy(data), info)
            if payload is module_loader.DROP:
                continue
        try:
            await ws.send_json(payload)
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
        "file": file_of(event.message),
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


# The other person read my messages up to max_id (private chats; supergroups send it too, the app
# ignores those)
@on(events.MessageRead(inbox=False))
async def on_read_outbox(event):
    await broadcast({"type": "read_outbox", "chat_id": event.chat_id, "max_id": event.max_id})


@on(events.MessageRead(inbox=True))
async def on_read(event):
    forget_forum_unread(event.chat_id)
    await broadcast({"type": "read", "chat_id": event.chat_id})
