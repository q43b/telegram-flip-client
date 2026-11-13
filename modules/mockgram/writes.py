"""The write requests of the made-up Telegram: send, send media, edit, delete, forward, react, mark
read, favourite a sticker, and the file upload they use. They change the backend's state and answer
as Telegram would. Nothing is dispatched to the server's live handlers: Telethon marks the answers
to its own client's writes "process, don't dispatch", so the live stream only ever carries what
*other* people do (scripted events, script.py), exactly as with real Telegram.

A mixin of Backend (uses its state and helpers).
"""
import copy
import datetime
from io import BytesIO

from PIL import Image
from telethon import errors, types
from telethon.tl.types import messages as m_types

from . import assets
from .scene import GENERAL

EDIT_LIMIT = datetime.timedelta(days=2)  # as Telegram's edit_time_limit (help_GetConfig says the same)
MAX_FAVED = 5  # Telegram's limit without Premium


class Writes:
    # ---- sending -----------------------------------------------------------------------------
    def _find(self, peer_id, message_id, request):
        found = next((x for x in self.messages[peer_id] if x.id == message_id), None)
        if found is None:
            raise errors.MessageIdInvalidError(request)
        return found

    def _check_can_write(self, peer_id, request):
        # A channel is read-only for everyone but its owner, which I'm not
        if self.by_peer[peer_id].kind == "channel":
            raise errors.ChatWriteForbiddenError(request)

    def _reply_header(self, chat, peer_id, reply_to):
        """What a sent message points at. Telethon sends InputReplyToMessage(reply_to_msg_id): in a
        forum, a topic's id means "post into that topic", a message's id "reply to it" (staying in its topic)."""
        target = getattr(reply_to, "reply_to_msg_id", None)
        if target is None:
            return None
        if chat.kind != "forum":
            return types.MessageReplyHeader(reply_to_msg_id=target)
        if target == GENERAL:
            return None  # General takes plain messages
        if target in {t.id for t in chat.topics}:
            return types.MessageReplyHeader(reply_to_msg_id=target, forum_topic=True)
        replied = next((x for x in self.messages[peer_id] if x.id == target), None)
        topic = self._topic_of(chat, replied) if replied is not None else None
        if topic in (None, GENERAL):
            return types.MessageReplyHeader(reply_to_msg_id=target)
        return types.MessageReplyHeader(reply_to_msg_id=target, reply_to_top_id=topic, forum_topic=True)

    def _outgoing(self, peer_id, text, reply_to=None, media=None, entities=None, fwd_from=None):
        chat = self.by_peer[peer_id]
        in_group = chat.kind in ("group", "supergroup", "forum")
        message = types.Message(
            id=self.next_message_id(peer_id), peer_id=self.peer_of(chat), date=self.now(), message=text or "", out=True,
            from_id=types.PeerUser(self.me.id) if in_group else None, reply_to=self._reply_header(chat, peer_id, reply_to),
            media=media, entities=entities or None, fwd_from=fwd_from)
        self.messages[peer_id].append(message)
        return message

    def _sent(self, peer_id, messages, random_ids):
        """Telegram's answer to a send: the new message(s) with the random id the client chose."""
        users, chats = self._entities_of([peer_id], messages)
        new = types.UpdateNewChannelMessage if self._channel_like(peer_id) else types.UpdateNewMessage
        updates = []
        for message, random_id in zip(messages, random_ids):
            updates.append(types.UpdateMessageID(id=message.id, random_id=random_id))
            updates.append(new(message=copy.copy(message), pts=1, pts_count=1))
        return types.Updates(updates=updates, users=users, chats=chats, date=self.now(), seq=0)

    def messages_SendMessage(self, request):
        peer_id = self.peer_id_of(request.peer)
        self._check_can_write(peer_id, request)
        message = self._outgoing(peer_id, request.message, request.reply_to, entities=request.entities)
        self.after_send(self.by_peer[peer_id])
        return self._sent(peer_id, [message], [request.random_id])

    # ---- uploads and media -----------------------------------------------------------------------
    def upload_SaveFilePart(self, request):
        self.upload_parts.setdefault(request.file_id, {})[request.file_part] = bytes(request.bytes)
        return True

    def upload_SaveBigFilePart(self, request):
        self.upload_parts.setdefault(request.file_id, {})[request.file_part] = bytes(request.bytes)
        return True

    def _uploaded(self, input_file, request):
        parts = self.upload_parts.pop(input_file.id, None)
        if not parts:
            raise errors.MediaInvalidError(request)
        return b"".join(parts[i] for i in sorted(parts))

    def _photo_media(self, photo_id, w, h, date):
        sizes = []
        for letter, longest in self.PHOTO_SIZES.items():
            scale = min(1.0, longest / max(w, h))
            sw, sh = max(1, round(w * scale)), max(1, round(h * scale))
            sizes.append(types.PhotoSize(type=letter, w=sw, h=sh, size=sw * sh // 8))
        photo = types.Photo(id=photo_id, access_hash=self.hash_of(photo_id), file_reference=b"mg", date=date, sizes=sizes, dc_id=self.DC)
        return types.MessageMediaPhoto(photo=photo)

    def _input_media(self, media, request):
        if isinstance(media, types.InputMediaUploadedPhoto):
            data = self._uploaded(media.file, request)
            try:
                w, h = Image.open(BytesIO(data)).size
            except Exception:
                raise errors.MediaInvalidError(request)
            photo_id = self.new_id()
            self.uploaded_photos[photo_id] = data
            return self._photo_media(photo_id, w, h, self.now())
        if isinstance(media, types.InputMediaUploadedDocument):
            data = self._uploaded(media.file, request)
            attributes = list(media.attributes or [])
            document = self._document(media.mime_type, data, attributes)
            audio = next((a for a in attributes if isinstance(a, types.DocumentAttributeAudio)), None)
            video = next((a for a in attributes if isinstance(a, types.DocumentAttributeVideo)), None)
            return types.MessageMediaDocument(
                document=document, voice=True if audio is not None and audio.voice else None,
                video=True if video is not None and not video.round_message else None,
                round=True if video is not None and video.round_message else None)
        if isinstance(media, types.InputMediaDocument):  # a sticker (or any file already on Telegram)
            document = self.documents.get(media.id.id)
            if document is None:
                raise errors.MediaInvalidError(request)
            return types.MessageMediaDocument(document=document)
        raise errors.MediaInvalidError(request)

    def messages_SendMedia(self, request):
        peer_id = self.peer_id_of(request.peer)
        self._check_can_write(peer_id, request)
        media = self._input_media(request.media, request)
        message = self._outgoing(peer_id, request.message, request.reply_to, media=media, entities=request.entities)
        self.after_send(self.by_peer[peer_id])
        return self._sent(peer_id, [message], [request.random_id])

    # ---- editing and deleting ------------------------------------------------------------------------
    def messages_EditMessage(self, request):
        peer_id = self.peer_id_of(request.peer)
        message = self._find(peer_id, request.id, request)
        if not message.out:
            raise errors.MessageAuthorRequiredError(request)
        if self.now() - message.date > EDIT_LIMIT:
            raise errors.MessageEditTimeExpiredError(request)
        text = request.message if request.message is not None else message.message
        if text == message.message:
            raise errors.MessageNotModifiedError(request)
        message.message = text
        message.entities = request.entities or None
        message.edit_date = self.now()
        users, chats = self._entities_of([peer_id], [message])
        edited = types.UpdateEditChannelMessage if self._channel_like(peer_id) else types.UpdateEditMessage
        return types.Updates(updates=[edited(message=copy.copy(message), pts=1, pts_count=1)], users=users, chats=chats,
                             date=self.now(), seq=0)

    def _delete(self, peer_ids, ids):
        removed = 0
        for peer_id in peer_ids:
            before = len(self.messages[peer_id])
            self.messages[peer_id] = [x for x in self.messages[peer_id] if x.id not in ids]
            removed += before - len(self.messages[peer_id])
        return m_types.AffectedMessages(pts=1, pts_count=removed)

    def messages_DeleteMessages(self, request):
        # Private chats and small groups share their ids: the message could be in any of them
        return self._delete([p for p, c in self.by_peer.items() if not c.is_channel_like], set(request.id))

    def channels_DeleteMessages(self, request):
        from telethon import utils
        peer_id = utils.get_peer_id(types.PeerChannel(request.channel.channel_id))
        return self._delete([peer_id], set(request.id))

    # ---- forwarding and reacting ---------------------------------------------------------------------
    def _sender_peer(self, message, chat):
        if message.from_id is not None:
            return message.from_id
        if chat.kind == "private":
            return types.PeerUser(self.me.id if message.out else chat.person.id)
        return message.peer_id  # a channel's own post

    def messages_ForwardMessages(self, request):
        source = self.peer_id_of(request.from_peer)
        target = self.peer_id_of(request.to_peer)
        self._check_can_write(target, request)
        originals = [self._find(source, i, request) for i in request.id]
        sent = []
        for original in originals:
            header = types.MessageFwdHeader(date=original.date, from_id=self._sender_peer(original, self.by_peer[source]))
            sent.append(self._outgoing(target, original.message, media=original.media, entities=original.entities, fwd_from=header))
        return self._sent(target, sent, list(request.random_id))

    def messages_SendReaction(self, request):
        peer_id = self.peer_id_of(request.peer)
        chat = self.by_peer[peer_id]
        message = self._find(peer_id, request.msg_id, request)
        wanted = list(request.reaction or [])
        for reaction in wanted:
            known = (isinstance(reaction, types.ReactionEmoji) and reaction.emoticon in self.scene.reactions) or (
                isinstance(reaction, types.ReactionCustomEmoji) and reaction.document_id in {d.id for d in self.emoji_docs.values()})
            if not known:
                raise errors.ReactionInvalidError(request)
        results = []
        for count in (message.reactions.results if message.reactions else []):
            if count.chosen_order is None:
                results.append(count)
            elif count.count > 1:  # my reaction goes; others who chose it stay
                results.append(types.ReactionCount(reaction=count.reaction, count=count.count - 1, chosen_order=None))
        for reaction in wanted:
            for i, count in enumerate(results):
                if count.reaction.to_dict() == reaction.to_dict():
                    results[i] = types.ReactionCount(reaction=count.reaction, count=count.count + 1, chosen_order=0)
                    break
            else:
                results.append(types.ReactionCount(reaction=reaction, count=1, chosen_order=0))
        message.reactions = types.MessageReactions(results=results) if results else None
        update = types.UpdateMessageReactions(peer=self.peer_of(chat), msg_id=message.id,
                                              reactions=message.reactions or types.MessageReactions(results=[]))
        return types.Updates(updates=[update], users=[], chats=[], date=self.now(), seq=0)

    # ---- reading ---------------------------------------------------------------------------------------
    def _mark_read(self, peer_id, max_id):
        chat = self.by_peer[peer_id]
        top = self._top(peer_id)
        upto = max_id or (top.id if top else 0)  # 0 means everything
        if chat.kind == "forum":
            self.topic_read[(peer_id, GENERAL)] = max(self.topic_read.get((peer_id, GENERAL), 0), upto)
        else:
            self.read_inbox[peer_id] = max(self.read_inbox[peer_id], upto)

    def messages_ReadHistory(self, request):
        self._mark_read(self.peer_id_of(request.peer), request.max_id)
        return m_types.AffectedMessages(pts=1, pts_count=0)

    def channels_ReadHistory(self, request):
        from telethon import utils
        self._mark_read(utils.get_peer_id(types.PeerChannel(request.channel.channel_id)), request.max_id)
        return True

    def messages_ReadDiscussion(self, request):
        """A forum topic is read up to a message (msg_id is the topic's id)."""
        peer_id = self.peer_id_of(request.peer)
        key = (peer_id, request.msg_id)
        if key not in self.topic_read:
            raise errors.MsgIdInvalidError(request)
        self.topic_read[key] = max(self.topic_read[key], request.read_max_id)
        return True

    def messages_GetMessageReadParticipants(self, request):
        """Who read one of my messages in a group (Telegram: small groups, recent messages)."""
        peer_id = self.peer_id_of(request.peer)
        chat = self.by_peer[peer_id]
        message = self._find(peer_id, request.msg_id, request)
        if chat.kind not in ("group", "supergroup", "forum") or not message.out:
            raise errors.MessageIdInvalidError(request)
        return [types.ReadParticipantDate(user_id=who, date=message.date + datetime.timedelta(minutes=5 * (i + 1)))
                for i, who in enumerate(self.read_by.get((peer_id, message.id), []))]

    # ---- pins and polls ------------------------------------------------------------------------------
    def pin_update(self, chat, ids, pinned):
        if chat.is_channel_like:
            return types.UpdatePinnedChannelMessages(channel_id=chat.raw_id, messages=ids, pts=1, pts_count=1,
                                                     pinned=True if pinned else None)
        return types.UpdatePinnedMessages(peer=self.peer_of(chat), messages=ids, pts=1, pts_count=1, pinned=True if pinned else None)

    def pin_service(self, chat, target_id, by_me, person=None):
        """The "pinned a message" service message, added to the chat."""
        peer_id = chat.peer_id
        from_id = None
        if chat.kind != "channel":
            from_id = types.PeerUser(self.me.id if by_me else person)
        service = types.MessageService(
            id=self.next_message_id(peer_id), peer_id=self.peer_of(chat), date=self.now(), out=True if by_me else None,
            from_id=from_id if chat.kind in ("group", "supergroup", "forum") or not by_me else None,
            reply_to=types.MessageReplyHeader(reply_to_msg_id=target_id), action=types.MessageActionPinMessage())
        self.messages[peer_id].append(service)
        return service

    def messages_UpdatePinnedMessage(self, request):
        peer_id = self.peer_id_of(request.peer)
        chat = self.by_peer[peer_id]
        if chat.kind == "channel":
            raise errors.ChatAdminRequiredError(request)
        message = self._find(peer_id, request.id, request)
        message.pinned = None if request.unpin else True
        updates = [self.pin_update(chat, [message.id], not request.unpin)]
        messages = []
        if not request.unpin:
            service = self.pin_service(chat, message.id, by_me=True)
            new = types.UpdateNewChannelMessage if self._channel_like(peer_id) else types.UpdateNewMessage
            updates.append(new(message=copy.copy(service), pts=1, pts_count=1))
            messages.append(service)
        users, chats = self._entities_of([peer_id], messages)
        return types.Updates(updates=updates, users=users, chats=chats, date=self.now(), seq=0)

    def messages_SendVote(self, request):
        peer_id = self.peer_id_of(request.peer)
        state = self.polls.get((peer_id, request.msg_id))
        if state is None:
            raise errors.MessageIdInvalidError(request)
        poll = state["media"].poll
        if poll.closed:
            raise errors.MessagePollClosedError(request)
        chosen = sorted({option[0] for option in request.options})
        if state["chosen"] and poll.quiz:
            raise errors.RevoteNotAllowedError(request)
        if len(chosen) > 1 and not poll.multiple_choice:
            raise errors.OptionInvalidError(request)
        for i in state["chosen"]:
            state["votes"][i] -= 1
        for i in chosen:
            state["votes"][i] += 1
        state["chosen"] = chosen
        self.set_poll_results(state)
        update = types.UpdateMessagePoll(poll_id=poll.id, results=state["media"].results,
                                         peer=self.peer_of(self.by_peer[peer_id]), msg_id=request.msg_id)
        return types.Updates(updates=[update], users=[], chats=[], date=self.now(), seq=0)

    # ---- stickers ---------------------------------------------------------------------------------------
    def messages_FaveSticker(self, request):
        document_id = request.id.id
        if document_id not in self.documents:
            raise errors.StickerIdInvalidError(request)
        if request.unfave:
            if document_id in self.faved_ids:
                self.faved_ids.remove(document_id)
        elif document_id not in self.faved_ids:
            if len(self.faved_ids) >= MAX_FAVED:
                raise errors.StickersTooMuchError(request)
            self.faved_ids.insert(0, document_id)
        return True

    def messages_InstallStickerSet(self, request):
        return m_types.StickerSetInstallResultSuccess()  # the one pack there is, is always installed
