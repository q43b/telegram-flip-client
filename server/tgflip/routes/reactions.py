"""Reactions: which ones a chat allows, and adding or taking back mine.
"""
from fastapi import HTTPException
from telethon import errors, functions, types

from tgflip.account import client
from tgflip.api import api
from tgflip.bodies import ReactBody
from tgflip.messages import learn_live_custom_emoji, reactions_list
from tgflip.telegram import bad_request, is_cluster_error, recovering


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
