"""Telegram's live updates, passed on to every app of the account over /ws (broadcast).
"""
import copy

from telethon import errors, events, types, utils

import module_loader

from tgflip.account import account, client, on
from tgflip.messages import call_of, message_json, poll_of, service_of, custom_emoji_of, file_of, forget_forum_unread, image_of, is_edited, learn_live_custom_emoji, reactions_list, reactions_of, reply_of, round_of, sender_id_of, sender_name, sticker_of, topic_of, video_of, voice_of


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
        "chat_id": event.chat_id,
        "topic_id": topic_of(event.message),  # None in ordinary chats and General
        **message_json(event.message, sender),
    })


# Telethon's NewMessage skips service messages, but call records and "X pinned a message" are worth
# showing live. Calls only happen in private chats (UpdateNewMessage); pins happen anywhere.
@on(events.Raw((types.UpdateNewMessage, types.UpdateNewChannelMessage)))
async def on_service_message(update):
    message = update.message
    if not isinstance(message, types.MessageService):
        return
    if call_of(message) is None and service_of(message) is None:
        return
    await broadcast({
        "type": "message",
        "chat_id": utils.get_peer_id(message.peer_id),
        "topic_id": topic_of(message),
        **message_json(message),
    })


# Messages were pinned or unpinned (since 1.9.0): the app asks for the chat's pinned messages again
@on(events.Raw((types.UpdatePinnedMessages, types.UpdatePinnedChannelMessages)))
async def on_pinned(update):
    if isinstance(update, types.UpdatePinnedChannelMessages):
        chat_id = utils.get_peer_id(types.PeerChannel(update.channel_id))
    else:
        chat_id = utils.get_peer_id(update.peer)
    await broadcast({"type": "pinned", "chat_id": chat_id, "ids": list(update.messages), "pinned": bool(update.pinned)})


# Someone voted, or the poll closed (since 1.9.0): the poll as it is now, read again (the update may
# carry only the results)
@on(events.Raw(types.UpdateMessagePoll))
async def on_poll(update):
    if getattr(update, "peer", None) is None or not getattr(update, "msg_id", None):
        return
    chat_id = utils.get_peer_id(update.peer)
    try:
        message = await client.get_messages(chat_id, ids=update.msg_id)
    except (ValueError, errors.RPCError):
        return
    poll = poll_of(message) if message is not None else None
    if poll is not None:
        await broadcast({"type": "poll", "chat_id": chat_id, "id": update.msg_id, "poll": poll})


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
