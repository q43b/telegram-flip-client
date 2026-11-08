"""The made-up Telegram's side of the server: a real Telethon client whose connection is replaced.

Telethon's `_call` hands every request to `client._sender.send(request)`, a future that resolves to
the result, then does its usual bookkeeping on the result (entity caches). FakeSender answers from the
Backend instead of the network, so everything above it (Telethon's high-level methods, the server's
routes) runs unchanged. Checked against Telethon 1.45: `_sender`, and the session's data center
(downloads use `_sender` when a file's dc_id equals it).
"""
import asyncio

from telethon import TelegramClient, utils
from telethon.sessions import MemorySession

from .backend import DC


class FakeSender:
    dc_id = DC

    def __init__(self, backend):
        self.backend = backend

    def is_connected(self):
        return True

    async def connect(self, connection):
        pass

    async def disconnect(self):
        pass

    def send(self, request, ordered=False):
        if isinstance(request, (list, tuple)):  # several requests in one call: a future for each
            return [self.send(r) for r in request]
        future = asyncio.get_running_loop().create_future()
        try:
            future.set_result(self.backend.handle(request))
        except Exception as e:  # Telegram's errors travel the same way
            future.set_exception(e)
        return future


class FakeClient(TelegramClient):
    """In memory: no session file, no login, no network. A made-up account can't hold a real
    Telegram session, which is what keeps real data out of the screenshots."""

    def __init__(self, backend):
        super().__init__(MemorySession(), 1, "0" * 32)
        self.backend = backend
        self.session.set_dc(DC, "127.0.0.1", 443)
        self.session.process_entities(backend.everything)  # every entity known before the first request names one
        self._sender = FakeSender(backend)
        self._connected = False
        self._mb_entity_cache.set_self_user(backend.me.id, False, backend.me.access_hash)
        backend.deliver = self.deliver

    def deliver(self, update, users, chats):
        """A scripted update from the made-up Telegram, as if it had arrived over the connection."""
        update._entities = {utils.get_peer_id(e): e for e in [*users, *chats]}
        task = asyncio.ensure_future(self._dispatch_update(update))
        task.add_done_callback(lambda t: t.cancelled() or t.exception() is None or self.backend.log(
            f"Delivering a scripted update failed: {type(t.exception()).__name__}: {t.exception()}"))

    async def connect(self):
        self._connected = True

    async def disconnect(self):
        self._connected = False

    def is_connected(self):
        return self._connected

    async def is_user_authorized(self):
        return True
