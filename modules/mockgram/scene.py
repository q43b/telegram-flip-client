"""Scenes: the made-up Telegram's content (a JSON file; see scenes/showcase.json).

A scene lists people, chats of every kind the app knows (private, group, supergroup, forum with
topics, channel), their messages and the stickers, custom emoji and reactions on offer. Times are
relative ("ago": "2h30m"), counted from when the scene is loaded, so screenshots always look fresh.
Ids are handed out here, like Telegram would: one counter for private chats and small groups
(their message ids are account-wide), one per supergroup, forum or channel.
"""
import json
import re
from datetime import datetime, timedelta, timezone

from telethon import types, utils

from .tl import utf16_length

GENERAL = 1  # a forum's General topic always has id 1
KINDS = ("private", "group", "supergroup", "forum", "channel")
DURATION = re.compile(r"(\d+)([smhd])")
EMOJI_TOKEN = re.compile(r"\[\[ce:([a-z0-9_]+)\]\]")  # [[ce:key]] in a text: that custom emoji
PLACEHOLDER = chr(0xE000)  # stands for a custom emoji in a text until its emoji is filled in (Scene._fill_emoji)


class SceneError(Exception):
    """The scene can't be used as written; the message says where."""


def parse_ago(text, where):
    text = str(text).strip()
    if text in ("0", ""):
        return timedelta(0)
    parts = DURATION.findall(text)
    if not parts or "".join(n + u for n, u in parts) != text:
        raise SceneError(f'{where}: "ago" must look like "30s", "5m", "2h30m", "3d" (not "{text}")')
    seconds = {"s": 1, "m": 60, "h": 3600, "d": 86400}
    return timedelta(seconds=sum(int(n) * seconds[u] for n, u in parts))


class Person:
    def __init__(self, data):
        self.id = int(data["id"])
        self.first = data.get("first_name", "")
        self.last = data.get("last_name", "")
        self.username = data.get("username")
        self.phone = data.get("phone")
        self.bot = bool(data.get("bot", False))
        self.color = int(data.get("color", self.id))
        self.photo = bool(data.get("photo", True))  # a generated profile photo


class Message:
    def __init__(self, chat, data, now, index):
        where = f'chat "{chat.title or chat.person_name}" message {index + 1}'
        self.chat = chat
        self.key = data.get("key")
        self.sender = data.get("from")  # a person id, "me", or None (a channel's own post)
        self.date = now - parse_ago(data.get("ago", "0"), where)
        self.text = data.get("text", "")
        self.entities = []  # (offset, length, custom emoji key), UTF-16 units
        self.reply_key = data.get("reply")
        self.edited = bool(data.get("edited", False))
        self.reactions = data.get("reactions", [])  # [{"emoji", "count", "mine", "custom"}]
        self.media = data.get("media")
        self.topic_key = data.get("topic")
        self.id = None
        self.reply_id = None
        self.topic_id = None
        self.creates = None  # a topic, for the service message that creates it
        self.where = where
        if self.media is not None and self.media.get("type") not in ("photo", "sticker", "voice", "video", "round", "call", "file"):
            raise SceneError(f"{where}: unknown media type {self.media.get('type')!r}")
        self._tokens()

    def _tokens(self):
        """Custom emoji written as [[ce:key]]: replaced by the emoji they stand for, and noted as
        entities with their offsets, which Telegram counts in UTF-16 units."""
        pieces, position = [], 0
        text = ""
        for match in EMOJI_TOKEN.finditer(self.text):
            text += self.text[position:match.start()]
            self.entities.append((utf16_length(text), match.group(1)))
            text += PLACEHOLDER  # placeholder, replaced once the emoji is known (Scene.finish)
            position = match.end()
        self.text = text + self.text[position:]

    @classmethod
    def service(cls, chat, date, media):
        """A service message (no sender, no text): 'topic created' and the like."""
        m = cls(chat, {"media": None}, date, -1)
        m.date, m.media, m.sender, m.index = date, media, None, -1
        return m

    @property
    def out(self):
        return self.sender == "me"


class Topic:
    def __init__(self, data, general=False):
        self.key = "general" if general else data["key"]
        self.title = "General" if general else data["title"]
        self.color = int(data.get("icon_color", 0x6FB9F0))
        self.unread = int(data.get("unread", 0))
        self.general = general
        self.id = GENERAL if general else None
        self.created = None  # the service message that made it (not General)


class Chat:
    def __init__(self, data, people, me):
        self.kind = data.get("kind")
        if self.kind not in KINDS:
            raise SceneError(f"a chat has the kind {self.kind!r}; it must be one of {', '.join(KINDS)}")
        self.person = None
        self.person_name = ""
        self.title = data.get("title", "")
        self.unread = int(data.get("unread", 0))
        self.pinned = bool(data.get("pinned", False))
        self.color = int(data.get("color", 0))
        self.photo = bool(data.get("photo", True))
        if self.kind == "private":
            who = data.get("person")
            self.person = me if who == "me" else people.get(who)
            if self.person is None:
                raise SceneError(f"a private chat names the person {who!r}, who isn't in the scene's people")
            self.person_name = (self.person.first + " " + self.person.last).strip()
            self.raw_id = self.person.id
            self.peer_id = self.person.id
            self.title = self.person_name
        else:
            if "id" not in data or not self.title:
                raise SceneError(f"a {self.kind} needs an id and a title")
            self.raw_id = int(data["id"])
            self.peer_id = (utils.get_peer_id(types.PeerChat(self.raw_id)) if self.kind == "group"
                            else utils.get_peer_id(types.PeerChannel(self.raw_id)))
        self.members = [people[m] for m in data.get("members", [])]
        self.owner = people.get(data.get("owner"))
        self.admins = [people[m] for m in data.get("admins", [])]
        self.participants = int(data.get("participants", len(self.members) + 1))  # the shown count
        self.topics = []
        if self.kind == "forum":
            self.topics = [Topic({}, general=True)] + [Topic(t) for t in data.get("topics", [])]
        self.messages = []
        self.raw = data.get("messages", [])
        # When I write in this chat, the next of these answers (cycling): {"from", "after", "text"|"media"}
        self.auto_replies = data.get("auto_replies", [])

    @property
    def is_channel_like(self):
        """Supergroups, forums and channels share Telegram's channel id space."""
        return self.kind in ("supergroup", "forum", "channel")

    def topic(self, key):
        for t in self.topics:
            if t.key == key:
                return t
        raise SceneError(f"chat {self.title!r}: there is no topic {key!r}")


class Scene:
    def __init__(self, data, now=None):
        self.now = now or datetime.now(timezone.utc)
        self.name = data.get("name", "scene")
        self.me = Person(data["me"])
        self.people = {int(p["id"]): Person(p) for p in data.get("people", [])}
        self.chats = [Chat(c, self.people, self.me) for c in data.get("chats", [])]
        if not self.chats:
            raise SceneError("the scene has no chats")
        set_data = data.get("sticker_set", {})
        self.set = {"id": int(set_data.get("id", 5000)), "title": set_data.get("title", "Stickers"),
                    "short_name": set_data.get("short_name", "stickers")}
        self.stickers = {s["key"]: s for s in set_data.get("items", [])}
        self.custom_emoji = {c["key"]: c for c in data.get("custom_emoji", [])}
        self.reactions = data.get("reactions", ["👍", "❤", "🔥", "😂"])
        self.faved = data.get("faved", [])
        self.recent = data.get("recent", [])
        self.finish()
        self.events = self._events(data.get("live", {}).get("events", []))
        for chat in self.chats:
            for reply in chat.auto_replies:
                self._check_actor(reply, f"an auto reply in {chat.title!r}")
                reply["delay"] = parse_ago(reply.get("after", "2s"), f"an auto reply in {chat.title!r}").total_seconds()

    def finish(self):
        for chat in self.chats:
            for index, raw in enumerate(chat.raw):
                m = Message(chat, raw, self.now, index)
                m.index = index
                if m.sender not in (None, "me") and m.sender not in self.people:
                    raise SceneError(f"{m.where}: from {m.sender!r} is not in the scene's people")
                if chat.messages and m.date < chat.messages[-1].date:
                    raise SceneError(f"{m.where}: it is older than the message before it (\"ago\" must not grow down the list)")
                if chat.kind == "channel":
                    m.sender = None
                for offset, key in m.entities:
                    if key not in self.custom_emoji:
                        raise SceneError(f"{m.where}: there is no custom emoji {key!r}")
                m.text = self._fill_emoji(m)
                chat.messages.append(m)
        self._assign_ids()

    def chat_named(self, name):
        found = [c for c in self.chats if (c.title or c.person_name) == name]
        if len(found) != 1:
            raise SceneError(f"{name!r} should name exactly one chat (by its title), but names {len(found)}")
        return found[0]

    def _check_actor(self, data, where):
        who = data.get("from")
        if who not in (None, "me") and who not in self.people:
            raise SceneError(f"{where}: from {who!r} is not in the scene's people")

    def _events(self, raw):
        """The scripted live events, checked now so a typo shows at startup, not mid-screenshot. They
        start counting from the first time the app asks for the chat list (Backend), and each is a
        message (the default), {"react": true, "message": key, ...} or {"edit": true, "message": key, "text"}."""
        events = []
        for number, e in enumerate(raw, 1):
            where = f"live event {number}"
            chat = self.chat_named(e["chat"])
            self._check_actor(e, where)
            keys = {m.key for m in chat.messages if m.key}
            if (e.get("react") or e.get("edit")) and e.get("message") not in keys:
                raise SceneError(f"{where}: the message {e.get('message')!r} isn't a message of {chat.title!r} (give it a \"key\")")
            if e.get("react") and not (e.get("emoji") or e.get("custom")):
                raise SceneError(f"{where}: a reaction needs an emoji or a custom emoji key")
            events.append({**e, "chat_obj": chat, "delay": parse_ago(e.get("after", "0"), where).total_seconds()})
        return sorted(events, key=lambda e: e["delay"])

    def new_message(self, chat, data, date):
        """A message made while running (a scripted event, an auto-reply): built like the scene's own,
        but dated now. The backend gives it its id."""
        m = Message(chat, {**data, "ago": "0"}, date, len(chat.messages))
        m.date = date
        if m.sender not in (None, "me") and m.sender not in self.people:
            raise SceneError(f"{m.where}: from {m.sender!r} is not in the scene's people")
        if chat.kind == "channel":
            m.sender = None
        for _, key in m.entities:
            if key not in self.custom_emoji:
                raise SceneError(f"{m.where}: there is no custom emoji {key!r}")
        m.text = self._fill_emoji(m)
        if m.reply_key is not None:
            target = next((x for x in chat.messages if x.key == m.reply_key), None)
            if target is None:
                raise SceneError(f"{m.where}: it replies to {m.reply_key!r}, which isn't a message of that chat")
            m.reply_id = target.id
        if chat.kind == "forum":
            m.topic_id = chat.topic(m.topic_key or "general").id
        return m

    def _fill_emoji(self, m):
        """Each placeholder becomes the plain emoji its custom emoji stands for; m.entities is
        rewritten as (offset, length, key) in UTF-16 units, as Telegram counts."""
        text, entities, entity = "", [], 0
        for char in m.text:
            if char == PLACEHOLDER:
                key = m.entities[entity][1]
                emoji = self.custom_emoji[key]["emoji"]
                entities.append((utf16_length(text), utf16_length(emoji), key))
                text += emoji
                entity += 1
            else:
                text += char
        m.entities = entities
        return text

    def _assign_ids(self):
        # Private chats and small groups: one account-wide counter, in time order
        shared = sorted((m for c in self.chats if not c.is_channel_like for m in c.messages),
                        key=lambda m: (m.date, self.chats.index(m.chat), m.index))
        for number, m in enumerate(shared, 1):
            m.id = number
        # Supergroups, forums, channels: a counter each (a forum's General is 1, so it starts at 2)
        for chat in self.chats:
            if not chat.is_channel_like:
                continue
            if chat.kind == "forum":
                self._add_topic_creations(chat)
            for number, m in enumerate(chat.messages, 2 if chat.kind == "forum" else 1):
                m.id = number
                if m.creates is not None:
                    m.creates.id = number  # a topic's id is the id of the service message that made it
        for chat in self.chats:
            by_key = {m.key: m for m in chat.messages if m.key}
            for m in chat.messages:
                if m.reply_key is not None:
                    if m.reply_key not in by_key:
                        raise SceneError(f"{m.where}: it replies to {m.reply_key!r}, which isn't a message of that chat")
                    m.reply_id = by_key[m.reply_key].id
                if chat.kind == "forum" and m.creates is None:
                    m.topic_id = chat.topic(m.topic_key or "general").id

    def _add_topic_creations(self, chat):
        """Every topic but General starts with a service message ("topic created"), whose id is
        the topic's id. It goes a minute before the topic's first message."""
        for topic in chat.topics:
            if topic.general:
                continue
            first = next((m for m in chat.messages if (m.topic_key or "general") == topic.key), None)
            when = (first.date if first else chat.messages[0].date) - timedelta(minutes=1)
            service = Message.service(chat, when, {"type": "topic_create", "title": topic.title, "color": topic.color})
            service.creates = topic
            topic.created = service
            chat.messages.append(service)
        # time order; at equal times a service message comes first (index -1)
        chat.messages.sort(key=lambda m: (m.date, m.index))


def load(path_or_data, now=None):
    if isinstance(path_or_data, dict):
        data = path_or_data
    else:
        with open(path_or_data, encoding="utf-8") as f:
            data = json.load(f)
    try:
        return Scene(data, now)
    except KeyError as e:
        raise SceneError(f"the scene is missing {e.args[0]!r} somewhere") from None
