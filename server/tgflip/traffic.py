"""What modules may do to the answers the server gives to apps (since 1.4.0): TrafficFilter runs
the "http.json" filters of the modules (module_loader.ModuleContext.filter) on every JSON answer.

It does nothing, and costs nothing, while no module has such a filter. Only successful JSON
answers up to MAX_BODY are touched; pictures, videos and other files stream through as they are,
and so does anything the filters can't read.
"""
import json

import module_loader
from tgflip import clients

MAX_BODY = 2 * 1024 * 1024  # bytes: a bigger JSON answer is passed on unchanged


class TrafficFilter:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not module_loader.has_filter("http.json"):
            return await self.app(scope, receive, send)
        state = {"start": None, "chunks": [], "size": 0, "passing": False}

        async def pass_through():
            """Everything held so far, untouched (the answer is too big, or not JSON we can read)."""
            state["passing"] = True
            if state["start"] is not None:
                await send(state["start"])
                state["start"] = None
            if state["chunks"]:
                await send({"type": "http.response.body", "body": b"".join(state["chunks"]), "more_body": True})
                state["chunks"] = []

        async def finish():
            body = b"".join(state["chunks"])
            start = state["start"]
            try:
                data = json.loads(body)
            except ValueError:
                return await pass_through_and_end(body)
            info = module_loader.Traffic(
                path=scope["path"], method=scope["method"], status=start["status"],
                account=scope.get("tgflip_account"), host=(scope.get("client") or ("?",))[0],
                client_version=self.client_version(scope))
            data = await module_loader.call_filters("http.json", data, info)
            new_body = json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
            headers = [(k, v) for k, v in start["headers"] if k.lower() != b"content-length"]
            headers.append((b"content-length", str(len(new_body)).encode("ascii")))
            await send({**start, "headers": headers})
            await send({"type": "http.response.body", "body": new_body})

        async def pass_through_and_end(body):
            await send(state["start"])
            await send({"type": "http.response.body", "body": body})

        async def send_filtered(message):
            if state["passing"]:
                return await send(message)
            if message["type"] == "http.response.start":
                content_type = dict(message.get("headers", [])).get(b"content-type", b"")
                if message["status"] < 300 and content_type.startswith(b"application/json"):
                    state["start"] = message
                else:
                    state["passing"] = True
                    await send(message)
                return
            if message["type"] != "http.response.body":
                return await send(message)
            state["chunks"].append(message.get("body", b""))
            state["size"] += len(state["chunks"][-1])
            if state["size"] > MAX_BODY:
                await pass_through()
                if not message.get("more_body", False):
                    await send({"type": "http.response.body", "body": b"", "more_body": False})
                return
            if not message.get("more_body", False):
                await finish()

        await self.app(scope, receive, send_filtered)

    @staticmethod
    def client_version(scope):
        """The app's version: its own header (apps from 1.4.0), else what its address showed
        on a live connection (clients.py)."""
        for key, value in scope.get("headers", []):
            if key == b"x-app-version":
                return value.decode("latin-1")
        return clients.version_of(scope.get("tgflip_account"), (scope.get("client") or ("?",))[0])
