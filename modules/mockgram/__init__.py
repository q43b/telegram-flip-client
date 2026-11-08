"""Mockgram: a made-up Telegram for demo accounts (accounts.json kind "mockgram"), so screenshots can
show invented chats and people instead of real ones (docs/traffic-plan.md).

The server asks this module, through the account.make hook, for the client of every account of its
kind. The client is a real Telethon client whose connection is replaced (client.py) by a Backend
(backend.py) that answers Telegram's requests from a scene (scene.py, scenes/*.json). Nothing here
touches the network or a real Telegram session.

The account's "settings" may name a scene: {"scene": "showcase"} (the default). Scenes are the module's
own scenes/ folder and, taking precedence, module_data/mockgram/scenes/ (the owner's own).
"""
import os

import telethon

from . import scene as scene_module
from .backend import Backend
from .client import FakeClient

TELETHON = "1.45"  # the version this was written and checked against (the server pins it)
KIND = "mockgram"


def setup(context):
    if not telethon.__version__.startswith(TELETHON):
        raise RuntimeError(f"written for Telethon {TELETHON}, this server has {telethon.__version__}")
    context.provide("mockgram", 1)
    folders = [os.path.join(context.data_folder, "scenes"), os.path.join(context.folder, "scenes")]

    def find_scene(name):
        for folder in folders:
            path = os.path.join(folder, f"{name}.json")
            if os.path.isfile(path):
                return path
        raise scene_module.SceneError(f'there is no scene "{name}" (looked in {", ".join(folders)})')

    @context.hook("account.make")
    def make(info):
        if info["kind"] != KIND:
            return None
        name = info["settings"].get("scene", "showcase")
        scene = scene_module.load(find_scene(name))
        context.log(f'Scene "{name}" for account "{info["name"]}": {len(scene.chats)} chats, '
                    f'{sum(len(c.messages) for c in scene.chats)} messages')
        return FakeClient(Backend(scene, log=context.log))
