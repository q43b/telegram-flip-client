"""What happens on its own in the made-up Telegram: other people writing, reacting and editing.

Two kinds, both written in the scene (see scenes/showcase.json):
- "live": {"events": [...]}: a timeline that starts when the app first asks for the chat list. Each
  event is a message (the default), a reaction ({"react": true, "message": key, "emoji" or "custom"}) or
  an edit ({"edit": true, "message": key, "text"}) by a person, "after" so many seconds.
- a chat's "auto_replies": when I send something to that chat, the next reply on its list (cycling)
  arrives "after" a few seconds, so a screenshot can show an answer coming in after sending.

Each one changes the backend's state and is delivered as the Telegram update it would be, to the
server's own handlers, which turn it into the app's live events. A mixin of Backend.
"""
import asyncio

from telethon import types


class Script:
    def start_script(self):
        """Called once, at the app's first chat list request: schedules the scene's live events."""
        loop = asyncio.get_running_loop()
        for event in self.scene.events:
            loop.call_later(event["delay"], self._safely, self._run_event, event)

    def _safely(self, function, *args):
        try:
            function(*args)
        except Exception as e:  # a scripted event must never take anything down
            self.log(f"A scripted event failed: {type(e).__name__}: {e}")

    def _run_event(self, event):
        chat = event["chat_obj"]
        if event.get("seen"):
            self.read_by_them(chat)
        elif event.get("vote"):
            self.vote_by(chat, event)
        elif event.get("pin"):
            self.pin_by(chat, event)
        elif event.get("react"):
            self.react_by(chat, event)
        elif event.get("edit"):
            self.edit_by(chat, event)
        else:
            self.incoming(chat, event)

    def read_by_them(self, chat):
        """The other person reads all my messages in a private chat (Telegram: UpdateReadHistoryOutbox)."""
        peer_id = chat.peer_id
        top = self._top(peer_id).id
        if top <= self.read_outbox[peer_id]:
            return
        self.read_outbox[peer_id] = top
        self._deliver(types.UpdateReadHistoryOutbox(peer=self.peer_of(chat), max_id=top, pts=1, pts_count=0), [peer_id])

    def after_send(self, chat):
        """I just sent something to this chat: it may be read soon, and if it has auto replies, the next one is on its way."""
        if chat.reads_after is not None:
            try:
                asyncio.get_running_loop().call_later(chat.reads_after, self._safely, self.read_by_them, chat)
            except RuntimeError:
                pass  # no running loop (a test calling the backend directly)
        if not chat.auto_replies:
            return
        number = self.reply_count.get(chat.peer_id, 0)
        self.reply_count[chat.peer_id] = number + 1
        reply = chat.auto_replies[number % len(chat.auto_replies)]
        data = {k: v for k, v in reply.items() if k in ("from", "text", "media", "topic", "reply")}
        try:
            asyncio.get_running_loop().call_later(reply["delay"], self._safely, self.incoming, chat, data)
        except RuntimeError:
            pass  # no running loop (a test calling the backend directly)

    # ---- the three things people do -----------------------------------------------------------------
    def incoming(self, chat, data):
        """Someone writes in a chat: a new message (with an id, unread until I read it) and its update."""
        peer_id = chat.peer_id
        m = self.scene.new_message(chat, data, self.now())
        m.id = self.next_message_id(peer_id)
        chat.messages.append(m)  # keeps the scene's list and the backend's in step (and its key findable)
        message = self._message(chat, m)
        self.messages[peer_id].append(message)
        if m.key:
            self.keys[(peer_id, m.key)] = m.id
        new = types.UpdateNewChannelMessage if self._channel_like(peer_id) else types.UpdateNewMessage
        self._deliver(new(message=message, pts=1, pts_count=1), [peer_id], [message])

    def _keyed(self, chat, key):
        peer_id = chat.peer_id
        message_id = self.keys[(peer_id, key)]
        return peer_id, next(x for x in self.messages[peer_id] if x.id == message_id)

    def react_by(self, chat, data):
        peer_id, message = self._keyed(chat, data["message"])
        if data.get("custom"):
            reaction = types.ReactionCustomEmoji(document_id=self.emoji_docs[data["custom"]].id)
        else:
            reaction = types.ReactionEmoji(emoticon=data["emoji"])
        results = list(message.reactions.results) if message.reactions else []
        for i, count in enumerate(results):
            if count.reaction.to_dict() == reaction.to_dict():
                results[i] = types.ReactionCount(reaction=count.reaction, count=count.count + 1, chosen_order=count.chosen_order)
                break
        else:
            results.append(types.ReactionCount(reaction=reaction, count=1, chosen_order=None))
        message.reactions = types.MessageReactions(results=results)
        update = types.UpdateMessageReactions(peer=self.peer_of(chat), msg_id=message.id, reactions=message.reactions)
        self._deliver(update, [peer_id])

    def edit_by(self, chat, data):
        peer_id, message = self._keyed(chat, data["message"])
        message.message = data["text"]
        message.edit_date = self.now()
        edited = types.UpdateEditChannelMessage if self._channel_like(peer_id) else types.UpdateEditMessage
        self._deliver(edited(message=message, pts=1, pts_count=1), [peer_id], [message])

    def vote_by(self, chat, data):
        """Someone votes for data["answer"] (a position) in a poll."""
        peer_id, message = self._keyed(chat, data["message"])
        state = self.polls[(peer_id, message.id)]
        state["votes"][data["answer"]] += 1
        self.set_poll_results(state)
        poll = state["media"].poll
        self._deliver(types.UpdateMessagePoll(poll_id=poll.id, results=state["media"].results,
                                              peer=self.peer_of(chat), msg_id=message.id), [peer_id])

    def pin_by(self, chat, data):
        """Someone (data["from"]) pins a message: the pin and the "pinned a message" line, both live."""
        peer_id, message = self._keyed(chat, data["message"])
        message.pinned = True
        self._deliver(self.pin_update(chat, [message.id], True), [peer_id])
        service = self.pin_service(chat, message.id, by_me=False, person=data.get("from"))
        new = types.UpdateNewChannelMessage if self._channel_like(peer_id) else types.UpdateNewMessage
        self._deliver(new(message=service, pts=1, pts_count=1), [peer_id], [service])

    def _deliver(self, update, peer_ids, messages=()):
        if self.deliver is None:
            return  # nobody is listening (the backend on its own, in a test)
        users, chats = self._entities_of(peer_ids, messages)
        self.deliver(update, users, chats)
