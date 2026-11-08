"""The made-up Telegram: answers Telegram's requests (as Telethon's own request and result objects)
from a scene. Knows nothing about TG Flip: `Backend(scene).handle(request) -> result`.

Everything is built the way Telegram would send it, so Telethon's own code (entity caches,
message wrappers) and the server's routes run unchanged on it. Requests are dispatched by their
module and name ("messages_GetHistory"); one it doesn't know is refused like Telegram would refuse
a method (and named once in the log), so what's missing writes itself.
"""
import copy
import datetime

from telethon import errors, types, utils
from telethon.tl.types import messages as m_types

from . import assets
from .scene import GENERAL
from .script import Script
from .tl import NOW, blank
from .writes import Writes

DC = 2  # the made-up data center; the fake client's session uses it too, so downloads stay local
PHOTO_SIZES = {"s": 100, "m": 320, "x": 800, "y": 1280}  # Telegram's size letters -> longest side
ID_PHOTOS = 6000  # ids of generated files start here (profile photos, then photos)
ID_DOCUMENTS = 7000


class Backend(Writes, Script):
    DC = DC
    PHOTO_SIZES = PHOTO_SIZES

    def __init__(self, scene, log=print):
        self.scene = scene
        self.log = log
        self.unknown = set()
        self._next_id = 100000
        self.files = {}  # document id -> bytes
        self.photos = {}  # photo id -> (picture name, w, h)
        self.avatars = {}  # profile photo id -> (letter, color index)
        self.channel_photos = {}  # peer id -> Photo (what GetFullChannel gives as chat_photo)
        self.documents = {}  # document id -> Document
        self.users = {}  # person id -> User
        self.entities = {}  # peer id -> User / Chat / Channel
        self.by_peer = {}  # peer id -> scene Chat
        self.messages = {}  # peer id -> [TL message, ...] in id order
        self._build_people()
        self._build_stickers()
        for chat in scene.chats:
            self._build_chat(chat)
        self._init_state()
        # Telethon's cache needs every entity before the first request that names one
        self.everything = types.messages.Dialogs(
            dialogs=[], messages=[], chats=[e for e in self.entities.values() if not isinstance(e, types.User)],
            users=list(self.users.values()))

    def _init_state(self):
        """What changes while it runs: read marks (so unread counts follow reading and new messages),
        the next message ids, my favourite stickers, uploads in progress."""
        self.read_inbox = {}  # peer id -> up to which message id I've read (not for forums)
        self.topic_read = {}  # (peer id, topic id) -> the same for a forum's topic
        self.channel_counter = {}  # peer id -> the last message id of a supergroup, forum or channel
        shared = 0
        for peer_id, chat in self.by_peer.items():
            messages = self.messages[peer_id]
            if chat.kind == "forum":
                for topic in chat.topics:
                    in_topic = [x for x in messages if self._topic_of(chat, x) == topic.id and x.id != topic.id]
                    self.topic_read[(peer_id, topic.id)] = self._read_max(chat, in_topic, topic.unread)
            else:
                self.read_inbox[peer_id] = self._read_max(chat, messages, chat.unread)
            if chat.is_channel_like:
                self.channel_counter[peer_id] = max((x.id for x in messages), default=0)
            else:
                shared = max([shared] + [x.id for x in messages])
        self.shared_counter = shared  # private chats and small groups share one counter
        self.faved_ids = [self.sticker_docs[k].id for k in self.scene.faved]
        self.upload_parts = {}  # file id -> {part number: bytes}
        self.uploaded_photos = {}  # photo id -> JPEG bytes (a photo I sent)
        self.keys = {}  # (peer id, scene key) -> message id, for scripted reactions and edits
        for peer_id, chat in self.by_peer.items():
            for m in chat.messages:
                if m.key:
                    self.keys[(peer_id, m.key)] = m.id
        self.reply_count = {}  # peer id -> auto replies given so far
        self.started = False  # the scripted events start with the app's first chat list request
        self.deliver = None  # set by the client: deliver(update, users, chats) to the server's handlers

    def next_message_id(self, peer_id):
        if self._channel_like(peer_id):
            self.channel_counter[peer_id] += 1
            return self.channel_counter[peer_id]
        self.shared_counter += 1
        return self.shared_counter

    @staticmethod
    def now():
        return datetime.datetime.now(datetime.timezone.utc)

    # ---- ids ---------------------------------------------------------------------------------
    def new_id(self):
        self._next_id += 1
        return self._next_id

    @staticmethod
    def hash_of(identifier):
        return 1000003 * abs(identifier) + 17  # any number does: nothing checks it

    # ---- people and chats --------------------------------------------------------------------
    def _photo_for(self, name, color):
        """A profile photo (registered, so GetFile can draw it); its id changes with nothing."""
        photo_id = ID_PHOTOS + len(self.avatars)
        self.avatars[photo_id] = (name[:1], color)
        return photo_id

    def _build_people(self):
        for person in [self.scene.me] + list(self.scene.people.values()):
            photo = (types.UserProfilePhoto(photo_id=self._photo_for(person.first or "?", person.color), dc_id=DC)
                     if person.photo else types.UserProfilePhotoEmpty())
            user = types.User(
                id=person.id, access_hash=self.hash_of(person.id), first_name=person.first,
                last_name=person.last or None, username=person.username, phone=person.phone,
                bot=True if person.bot else None, is_self=True if person is self.scene.me else None,
                photo=photo, bot_info_version=1 if person.bot else None)
            self.users[person.id] = user
            self.entities[person.id] = user
        self.me = self.users[self.scene.me.id]

    def _build_chat(self, chat):
        if chat.kind == "private":
            peer_id = chat.person.id
        else:
            photo = (types.ChatPhoto(photo_id=self._photo_for(chat.title, chat.color), dc_id=DC)
                     if chat.photo else types.ChatPhotoEmpty())
            if chat.kind == "group":
                entity = blank(types.Chat, id=chat.raw_id, title=chat.title, photo=photo, participants_count=chat.participants,
                               date=chat.messages[0].date, version=1)
            else:
                entity = blank(types.Channel, id=chat.raw_id, title=chat.title, photo=photo, date=chat.messages[0].date,
                               access_hash=self.hash_of(chat.raw_id), megagroup=True if chat.kind != "channel" else None,
                               broadcast=True if chat.kind == "channel" else None, forum=True if chat.kind == "forum" else None,
                               participants_count=chat.participants)
            peer_id = chat.peer_id
            self.entities[peer_id] = entity
            if chat.is_channel_like and chat.photo:
                # For channels Telethon takes the picture from GetFullChannel, as a Photo with sizes
                photo_id = entity.photo.photo_id
                self.channel_photos[peer_id] = types.Photo(
                    id=photo_id, access_hash=self.hash_of(photo_id), file_reference=b"mg", date=NOW, dc_id=DC,
                    sizes=[types.PhotoSize(type="a", w=160, h=160, size=4000), types.PhotoSize(type="c", w=640, h=640, size=20000)])
        self.by_peer[peer_id] = chat
        self.messages[peer_id] = [self._message(chat, m) for m in chat.messages]

    def peer_of(self, chat):
        if chat.kind == "private":
            return types.PeerUser(chat.person.id)
        if chat.kind == "group":
            return types.PeerChat(chat.raw_id)
        return types.PeerChannel(chat.raw_id)

    # ---- stickers, custom emoji ----------------------------------------------------------------
    def _document(self, mime, data, attributes, thumbs=None):
        document_id = ID_DOCUMENTS + len(self.documents)
        document = types.Document(id=document_id, access_hash=self.hash_of(document_id), file_reference=b"mg", date=NOW,
                                  mime_type=mime, size=len(data), dc_id=DC, attributes=attributes, thumbs=thumbs)
        self.documents[document_id] = document
        self.files[document_id] = data
        return document

    def _build_stickers(self):
        scene, s = self.scene, self.scene.set
        self.sticker_set_id = types.InputStickerSetID(id=s["id"], access_hash=self.hash_of(s["id"]))
        self.sticker_docs = {}
        for key, item in scene.stickers.items():
            data = assets.sticker_webp(item["draw"])
            self.sticker_docs[key] = self._document(
                "image/webp", data,
                [types.DocumentAttributeImageSize(w=512, h=512),
                 types.DocumentAttributeSticker(alt=item["emoji"], stickerset=self.sticker_set_id)],
                self._thumbs(data))
        self.emoji_docs = {}
        for key, item in scene.custom_emoji.items():
            data = assets.sticker_webp(item["draw"], 100)
            self.emoji_docs[key] = self._document(
                "image/webp", data,
                [types.DocumentAttributeImageSize(w=100, h=100),
                 types.DocumentAttributeCustomEmoji(alt=item["emoji"], stickerset=types.InputStickerSetEmpty(), free=True)],
                self._thumbs(data))
        self.sticker_set = blank(types.StickerSet, title=s["title"], short_name=s["short_name"], count=len(self.sticker_docs),
                                 hash=1, id=s["id"], access_hash=self.hash_of(s["id"]), installed_date=NOW)

    def _thumbs(self, webp):
        """Documents come with a small preview (a JPEG made on demand by GetFile)."""
        return [types.PhotoSize(type="m", w=128, h=128, size=len(assets.thumb_jpeg(webp, 128)))]

    # ---- messages ----------------------------------------------------------------------------
    def _media(self, m):
        spec = m.media
        kind = spec["type"]
        if kind == "photo":
            photo_id = ID_PHOTOS + 5000 + len(self.photos)
            w, h = int(spec.get("w", 1280)), int(spec.get("h", 960))
            self.photos[photo_id] = (spec.get("picture", "mountains"), w, h)
            sizes = []
            for letter, longest in PHOTO_SIZES.items():
                scale = min(1.0, longest / max(w, h))
                sw, sh = max(1, round(w * scale)), max(1, round(h * scale))
                sizes.append(types.PhotoSize(type=letter, w=sw, h=sh, size=sw * sh // 8))
            photo = types.Photo(id=photo_id, access_hash=self.hash_of(photo_id), file_reference=b"mg", date=m.date,
                                sizes=sizes, dc_id=DC)
            return types.MessageMediaPhoto(photo=photo)
        if kind == "sticker":
            return types.MessageMediaDocument(document=self.sticker_docs[spec["key"]])
        if kind == "voice":
            document = self._document("audio/ogg", assets.OGG_STUB,
                                      [types.DocumentAttributeAudio(duration=int(spec["duration"]), voice=True, waveform=b"")])
            return types.MessageMediaDocument(document=document, voice=True)
        if kind == "video":
            document = self._document("video/mp4", assets.MP4_STUB,
                                      [types.DocumentAttributeVideo(duration=int(spec["duration"]), w=int(spec.get("w", 640)),
                                                                    h=int(spec.get("h", 360)), supports_streaming=True)])
            document.size = int(spec.get("size", len(assets.MP4_STUB)))  # what the app shows ("8.0 MB")
            return types.MessageMediaDocument(document=document, video=True)
        if kind == "round":
            document = self._document("video/mp4", assets.MP4_STUB,
                                      [types.DocumentAttributeVideo(duration=int(spec["duration"]), w=384, h=384, round_message=True)])
            document.size = int(spec.get("size", len(assets.MP4_STUB)))
            return types.MessageMediaDocument(document=document, round=True)
        raise ValueError(f"unknown media {kind}")

    def _message(self, chat, m):
        peer = self.peer_of(chat)
        reply_to = self._reply(chat, m)
        spec = m.media or {}
        if spec.get("type") == "topic_create":
            return types.MessageService(
                id=m.id, peer_id=peer, date=m.date,
                action=types.MessageActionTopicCreate(title=spec["title"], icon_color=spec["color"]),
                from_id=types.PeerUser(chat.owner.id) if chat.owner else None)
        if spec.get("type") == "call":
            reasons = {"missed": types.PhoneCallDiscardReasonMissed, "busy": types.PhoneCallDiscardReasonBusy,
                       "hangup": types.PhoneCallDiscardReasonHangup, "disconnect": types.PhoneCallDiscardReasonDisconnect}
            reason = reasons[spec.get("reason", "hangup")]()
            return types.MessageService(
                id=m.id, peer_id=peer, date=m.date, out=True if m.out else None,
                action=types.MessageActionPhoneCall(call_id=self.new_id(), video=True if spec.get("video") else None,
                                                    reason=reason, duration=spec.get("duration")))
        entities = [types.MessageEntityCustomEmoji(offset=offset, length=length, document_id=self.emoji_docs[key].id)
                    for offset, length, key in m.entities]
        from_id = None
        if chat.kind in ("group", "supergroup", "forum") and m.sender is not None:
            from_id = types.PeerUser(self.scene.me.id if m.out else m.sender)
        return types.Message(
            id=m.id, peer_id=peer, date=m.date, message=m.text, out=True if m.out else None, from_id=from_id,
            post=True if chat.kind == "channel" else None, reply_to=reply_to, media=self._media(m) if m.media else None,
            entities=entities or None, reactions=self._reactions(m),
            edit_date=m.date + datetime.timedelta(minutes=2) if m.edited else None)

    def _reply(self, chat, m):
        """Replies, and for a forum's topics the marks Telegram puts on every message of a topic."""
        in_topic = chat.kind == "forum" and m.topic_id not in (None, GENERAL)
        if m.reply_id is not None:
            return types.MessageReplyHeader(reply_to_msg_id=m.reply_id, forum_topic=True if in_topic else None,
                                            reply_to_top_id=m.topic_id if in_topic else None)
        if in_topic:
            return types.MessageReplyHeader(reply_to_msg_id=m.topic_id, forum_topic=True)
        return None

    def _reactions(self, m):
        if not m.reactions:
            return None
        results = []
        for r in m.reactions:
            if "custom" in r:
                reaction = types.ReactionCustomEmoji(document_id=self.emoji_docs[r["custom"]].id)
            else:
                reaction = types.ReactionEmoji(emoticon=r["emoji"])
            results.append(types.ReactionCount(reaction=reaction, count=int(r.get("count", 1)),
                                               chosen_order=0 if r.get("mine") else None))
        return types.MessageReactions(results=results)

    # ---- dialogs -----------------------------------------------------------------------------
    def _top(self, peer_id):
        messages = self.messages[peer_id]
        return messages[-1] if messages else None

    def _read_max(self, chat, messages, unread):
        """The id up to which everything counts as read, leaving `unread` incoming messages unread."""
        incoming = [x for x in messages if not x.out and not isinstance(x, types.MessageService)]
        if unread <= 0 or not incoming:
            return messages[-1].id if messages else 0
        unread = min(unread, len(incoming))
        return incoming[-unread].id - 1

    @staticmethod
    def _incoming(messages):
        return [x for x in messages if not x.out and not isinstance(x, types.MessageService)]

    def unread_count(self, peer_id):
        """Incoming messages after what I've read; a forum adds up its topics (as the server does)."""
        chat = self.by_peer[peer_id]
        if chat.kind == "forum":
            return sum(self.topic_unread(peer_id, t) for t in chat.topics)
        return sum(1 for x in self._incoming(self.messages[peer_id]) if x.id > self.read_inbox[peer_id])

    def topic_unread(self, peer_id, topic):
        chat = self.by_peer[peer_id]
        in_topic = [x for x in self.messages[peer_id] if self._topic_of(chat, x) == topic.id and x.id != topic.id]
        return sum(1 for x in self._incoming(in_topic) if x.id > self.topic_read[(peer_id, topic.id)])

    def _dialog(self, peer_id):
        chat = self.by_peer[peer_id]
        top = self._top(peer_id)
        read = top.id if chat.kind == "forum" else self.read_inbox[peer_id]
        return types.Dialog(
            peer=self.peer_of(chat), top_message=top.id, read_inbox_max_id=read, read_outbox_max_id=top.id,
            unread_count=self.unread_count(peer_id), unread_mentions_count=0, unread_reactions_count=0,
            unread_poll_votes_count=0, notify_settings=types.PeerNotifySettings(), pinned=True if chat.pinned else None)

    def _entities_of(self, peer_ids, messages=()):
        """The users and chats Telegram sends along: the chats themselves, who wrote what, me."""
        user_ids, chats = {self.me.id}, {}
        for peer_id in peer_ids:
            entity = self.entities[peer_id]
            if isinstance(entity, types.User):
                user_ids.add(entity.id)
            else:
                chats[peer_id] = entity
        for message in messages:
            sender = getattr(message, "from_id", None)
            if isinstance(sender, types.PeerUser):
                user_ids.add(sender.user_id)
        return [self.users[i] for i in sorted(user_ids)], list(chats.values())

    def _dialog_order(self):
        return sorted((p for p in self.by_peer if self.messages[p]), key=lambda p: self._top(p).date, reverse=True)

    def messages_GetDialogs(self, request):
        if not self.started:  # the app is looking at its chat list: the scripted events begin
            self.started = True
            self.start_script()
        order = self._dialog_order()
        if request.offset_date:  # Telethon pages by the date of the last message it got
            order = [p for p in order if self._top(p).date < request.offset_date]
        page = order[:request.limit]
        users, chats = self._entities_of(page, [self._top(p) for p in page])
        arguments = dict(dialogs=[self._dialog(p) for p in page], messages=[copy.copy(self._top(p)) for p in page], chats=chats, users=users)
        if len(order) <= request.limit:
            return m_types.Dialogs(**arguments)  # the last page
        return m_types.DialogsSlice(count=len(self._dialog_order()), **arguments)

    def messages_GetPeerDialogs(self, request):
        peers = [utils.get_peer_id(p.peer) if not isinstance(p.peer, types.InputPeerSelf) else self.me.id for p in request.peers]
        peers = [p for p in peers if p in self.by_peer]
        users, chats = self._entities_of(peers, [self._top(p) for p in peers])
        return m_types.PeerDialogs(dialogs=[self._dialog(p) for p in peers], messages=[self._top(p) for p in peers],
                                   chats=chats, users=users, state=types.updates.State(pts=1, qts=0, date=NOW, seq=1, unread_count=0))

    # ---- one chat's messages -------------------------------------------------------------------
    def peer_id_of(self, input_peer):
        if isinstance(input_peer, types.InputPeerSelf):
            return self.me.id
        return utils.get_peer_id(input_peer)

    def _channel_like(self, peer_id):
        return self.by_peer[peer_id].is_channel_like

    def _page(self, peer_id, request, only=None):
        """Telegram's paging of a message list: newest first, offset_id/offset_date/add_offset/
        max_id/min_id and a limit. `only` filters (a topic's messages)."""
        found = [x for x in reversed(self.messages[peer_id]) if only is None or only(x)]
        if request.offset_id:
            found = [x for x in found if x.id < request.offset_id]
        if request.offset_date:
            found = [x for x in found if x.date < request.offset_date]
        total = len(found)
        if request.max_id:
            found = [x for x in found if x.id < request.max_id]
        if request.min_id:
            found = [x for x in found if x.id > request.min_id]
        if request.add_offset > 0:
            found = found[request.add_offset:]
        return [copy.copy(x) for x in found[:request.limit]], total  # copies: Telethon marks what it gets

    def _messages_result(self, peer_id, found, total, partial):
        users, chats = self._entities_of([peer_id], found)
        arguments = dict(messages=found, topics=[], chats=chats, users=users)
        if self._channel_like(peer_id):
            return m_types.ChannelMessages(pts=1, count=total, **arguments)
        return m_types.MessagesSlice(count=total, **arguments) if partial else m_types.Messages(**arguments)

    def messages_GetHistory(self, request):
        peer_id = self.peer_id_of(request.peer)
        found, total = self._page(peer_id, request)
        return self._messages_result(peer_id, found, total, partial=len(found) < total)

    def messages_GetReplies(self, request):
        """A forum topic's messages (msg_id is the topic's id)."""
        peer_id = self.peer_id_of(request.peer)
        chat = self.by_peer[peer_id]
        topic = next((t for t in chat.topics if t.id == request.msg_id), None)
        if topic is None:
            raise errors.MsgIdInvalidError(request)
        found, total = self._page(peer_id, request, only=lambda x: self._topic_of(chat, x) == topic.id and x.id != topic.id)
        return self._messages_result(peer_id, found, total, partial=len(found) < total)

    @staticmethod
    def _topic_of(chat, message):
        header = getattr(message, "reply_to", None)
        if header is not None and getattr(header, "forum_topic", False):
            return header.reply_to_top_id or header.reply_to_msg_id
        return GENERAL if chat.kind == "forum" and not isinstance(message, types.MessageService) else None

    def messages_GetMessages(self, request):
        wanted = {i.id for i in request.id if isinstance(i, types.InputMessageID)}
        found = [copy.copy(x) for p, ms in self.messages.items() if not self._channel_like(p) for x in ms if x.id in wanted]
        peers = [p for p, ms in self.messages.items() if not self._channel_like(p) and any(x.id in wanted for x in ms)]
        users, chats = self._entities_of(peers, found)
        return m_types.Messages(messages=found, topics=[], chats=chats, users=users)

    def channels_GetMessages(self, request):
        peer_id = utils.get_peer_id(types.PeerChannel(request.channel.channel_id))
        wanted = {i.id for i in request.id if isinstance(i, types.InputMessageID)}
        found = [copy.copy(x) for x in self.messages.get(peer_id, []) if x.id in wanted]
        return self._messages_result(peer_id, found, len(found), partial=False)

    # ---- forum topics ------------------------------------------------------------------------
    def messages_GetForumTopics(self, request):
        peer_id = self.peer_id_of(request.peer)
        chat = self.by_peer[peer_id]
        topics, tops = [], []
        for topic in chat.topics:
            in_topic = [x for x in self.messages[peer_id] if self._topic_of(chat, x) == topic.id and x.id != topic.id]
            top = in_topic[-1] if in_topic else (topic.created and self.messages[peer_id][chat.messages.index(topic.created)])
            if top is None:
                continue
            tops.append(copy.copy(top))
            topics.append(blank(
                types.ForumTopic, id=topic.id, date=top.date, title=topic.title, icon_color=topic.color, top_message=top.id,
                read_inbox_max_id=self.topic_read[(peer_id, topic.id)], read_outbox_max_id=top.id,
                unread_count=self.topic_unread(peer_id, topic), from_id=types.PeerUser(chat.owner.id) if chat.owner else types.PeerUser(self.me.id),
                peer=self.peer_of(chat), notify_settings=types.PeerNotifySettings()))
        topics.sort(key=lambda t: t.date, reverse=True)
        users, chats = self._entities_of([peer_id], tops)
        return m_types.ForumTopics(count=len(topics), topics=topics[:request.limit], messages=tops, chats=chats, users=users, pts=1)

    # ---- who is who -----------------------------------------------------------------------------
    def users_GetUsers(self, request):
        found = []
        for item in request.id:
            if isinstance(item, types.InputUserSelf):
                found.append(self.me)
            else:
                found.append(self.users.get(getattr(item, "user_id", 0)) or types.UserEmpty(id=getattr(item, "user_id", 0)))
        return found

    def channels_GetChannels(self, request):
        chats = []
        for item in request.id:
            chats.append(self.entities.get(utils.get_peer_id(types.PeerChannel(item.channel_id))) or types.ChannelForbidden(
                id=item.channel_id, access_hash=0, title="?"))
        return m_types.Chats(chats=chats)

    def messages_GetChats(self, request):
        return m_types.Chats(chats=[self.entities.get(utils.get_peer_id(types.PeerChat(i))) or types.ChatEmpty(id=i) for i in request.id])

    def messages_GetFullChat(self, request):
        peer_id = utils.get_peer_id(types.PeerChat(request.chat_id))
        chat = self.by_peer[peer_id]
        owner, admins = chat.owner, chat.admins
        participants = []
        for person in [owner] if owner else []:
            participants.append(types.ChatParticipantCreator(user_id=person.id))
        for person in admins:
            participants.append(types.ChatParticipantAdmin(user_id=person.id, inviter_id=owner.id if owner else person.id, date=NOW))
        known = {getattr(p, "user_id") for p in participants}
        for person in chat.members:
            if person.id not in known:
                participants.append(types.ChatParticipant(user_id=person.id, inviter_id=owner.id if owner else person.id, date=NOW))
        participants.append(types.ChatParticipant(user_id=self.me.id, inviter_id=self.me.id, date=NOW))
        full = blank(types.ChatFull, id=chat.raw_id, about="", participants=types.ChatParticipants(
            chat_id=chat.raw_id, participants=participants, version=1), notify_settings=types.PeerNotifySettings(),
            available_reactions=types.ChatReactionsAll())
        users, chats = self._entities_of([peer_id])
        users = [self.users[p.user_id] for p in participants] + [u for u in users if u.id not in {p.user_id for p in participants}]
        return m_types.ChatFull(full_chat=full, chats=chats, users=users)

    def channels_GetFullChannel(self, request):
        peer_id = utils.get_peer_id(types.PeerChannel(request.channel.channel_id))
        chat = self.by_peer[peer_id]
        full = blank(types.ChannelFull, id=chat.raw_id, about="", participants_count=chat.participants,
                     chat_photo=self.channel_photos.get(peer_id) or types.PhotoEmpty(id=0), notify_settings=types.PeerNotifySettings(),
                     available_reactions=types.ChatReactionsAll())
        users, chats = self._entities_of([peer_id])
        return m_types.ChatFull(full_chat=full, chats=chats, users=users)

    def channels_GetParticipants(self, request):
        peer_id = utils.get_peer_id(types.PeerChannel(request.channel.channel_id))
        chat = self.by_peer[peer_id]
        if chat.kind == "channel":
            raise errors.ChatAdminRequiredError(request)  # Telegram hides a channel's subscribers
        rights = types.ChatAdminRights()
        participants, ordered = [], []
        if chat.owner:
            participants.append(types.ChannelParticipantCreator(user_id=chat.owner.id, admin_rights=rights))
            ordered.append(chat.owner)
        for person in chat.admins:
            participants.append(types.ChannelParticipantAdmin(user_id=person.id, promoted_by=chat.owner.id if chat.owner else person.id,
                                                              date=NOW, admin_rights=rights))
            ordered.append(person)
        for person in chat.members:
            if person not in ordered:
                participants.append(types.ChannelParticipant(user_id=person.id, date=NOW))
                ordered.append(person)
        page = participants[request.offset:request.offset + request.limit]
        return types.channels.ChannelParticipants(count=chat.participants, participants=page, chats=[self.entities[peer_id]],
                                                  users=[self.users[p.user_id] for p in page] or [self.me])

    # ---- files ------------------------------------------------------------------------------------
    def upload_GetFile(self, request):
        location = request.location
        if isinstance(location, types.InputPeerPhotoFileLocation):
            letter, color = self.avatars[location.photo_id]
            data, kind = assets.avatar_jpeg(letter, color, 640 if location.big else 160), types.storage.FileJpeg()
        elif isinstance(location, types.InputPhotoFileLocation) and location.id in self.avatars:
            letter, color = self.avatars[location.id]  # a channel's profile photo ('a' small, 'c' big)
            data, kind = assets.avatar_jpeg(letter, color, 160 if location.thumb_size == "a" else 640), types.storage.FileJpeg()
        elif isinstance(location, types.InputPhotoFileLocation):
            name, w, h = self.photos[location.id]
            scale = min(1.0, PHOTO_SIZES.get(location.thumb_size, 1280) / max(w, h))
            data, kind = assets.picture_jpeg(name, max(1, round(w * scale)), max(1, round(h * scale))), types.storage.FileJpeg()
        elif isinstance(location, types.InputDocumentFileLocation):
            document = self.documents[location.id]
            data = self.files[location.id]
            kind = types.storage.FileWebp() if document.mime_type == "image/webp" else types.storage.FileUnknown()
            if location.thumb_size:  # a thumbnail of a sticker or custom emoji
                data, kind = assets.thumb_jpeg(data, 128), types.storage.FileJpeg()
        else:
            raise errors.LocationInvalidError(request)
        return types.upload.File(type=kind, mtime=0, bytes=data[request.offset:request.offset + request.limit])

    def messages_GetCustomEmojiDocuments(self, request):
        by_id = {d.id: d for d in self.emoji_docs.values()}
        return [by_id.get(i) or types.DocumentEmpty(id=i) for i in request.document_id]

    # ---- stickers --------------------------------------------------------------------------------
    def _packs(self, docs):
        return [types.StickerPack(emoticon=next(a.alt for a in d.attributes if isinstance(a, types.DocumentAttributeSticker)),
                                  documents=[d.id]) for d in docs]

    def messages_GetAllStickers(self, request):
        return m_types.AllStickers(hash=1, sets=[self.sticker_set])

    def messages_GetStickerSet(self, request):
        wanted = request.stickerset
        ok = (isinstance(wanted, types.InputStickerSetID) and wanted.id == self.scene.set["id"]) or (
            isinstance(wanted, types.InputStickerSetShortName) and wanted.short_name == self.scene.set["short_name"])
        if not ok:
            raise errors.StickersetInvalidError(request)
        docs = list(self.sticker_docs.values())
        return m_types.StickerSet(set=self.sticker_set, packs=self._packs(docs), keywords=[], documents=docs)

    def messages_GetFavedStickers(self, request):
        docs = [self.documents[i] for i in self.faved_ids]
        return m_types.FavedStickers(hash=1, packs=self._packs(docs), stickers=docs)

    def messages_GetRecentStickers(self, request):
        docs = [self.sticker_docs[k] for k in self.scene.recent]
        return m_types.RecentStickers(hash=1, packs=self._packs(docs), stickers=docs, dates=[0] * len(docs))

    # ---- reactions and Telegram's settings -----------------------------------------------------------
    def messages_GetAvailableReactions(self, request):
        icon = self.sticker_docs[next(iter(self.sticker_docs))]
        return m_types.AvailableReactions(hash=1, reactions=[
            blank(types.AvailableReaction, reaction=e, title=e, static_icon=icon, appear_animation=icon,
                  select_animation=icon, activate_animation=icon, effect_animation=icon) for e in self.scene.reactions])

    def messages_GetTopReactions(self, request):
        return m_types.Reactions(hash=1, reactions=[types.ReactionEmoji(emoticon=e) for e in self.scene.reactions][:request.limit])

    def help_GetConfig(self, request):
        day = 86400
        return blank(types.Config, edit_time_limit=2 * day, revoke_time_limit=2 * day, revoke_pm_time_limit=2 * day,
                     revoke_pm_inbox=True, this_dc=DC)

    # ---- dispatch ---------------------------------------------------------------------------------------
    def handle(self, request):
        module = type(request).__module__.rsplit(".", 1)[-1]
        name = type(request).__name__.removesuffix("Request")
        method = getattr(self, f"{module}_{name}", None)
        if method is None:
            key = f"{module}.{name}"
            if key not in self.unknown:
                self.unknown.add(key)
                self.log(f"The made-up Telegram doesn't know the request {key} yet")
            raise errors.RPCError(request, "METHOD_NOT_IMPLEMENTED", 400)
        return method(request)
