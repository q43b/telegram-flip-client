"""Stickers: pictures (still, or animated through the animated module's hook), packs,
favourites (Telegram's and local ones) and sending.
"""
import os
import time
import json
from io import BytesIO
from fastapi import HTTPException, Response
from typing import Optional
from PIL import Image
from telethon import errors, functions, types, utils
import module_loader

from tgflip.account import account, client
from tgflip.api import api
from tgflip.bodies import SendStickerBody
from tgflip.cache import STICKER_CACHE, cache_path, in_cache, sent, stored
from tgflip.messages import STICKER_KINDS, reply_target, sticker_json, sticker_of
from tgflip.telegram import bad_request, is_cluster_error, recovering


STICKER_SIZES = range(32, 513)  # Telegram's stickers are 512 px


async def sticker_document(document_id, chat_id, message_id):
    """The sticker's document, fresh: read again from the message it came in, or for a custom
    emoji (no message given) asked by id alone. Used after a server restart, or when a
    remembered document's file reference expired."""
    if chat_id is not None and message_id is not None:
        message = await client.get_messages(chat_id, ids=message_id)
        if message is not None and sticker_of(message) is not None and message.sticker.id == document_id:
            return message.sticker
        return account().sticker_documents.get(document_id)
    documents = await client(functions.messages.GetCustomEmojiDocumentsRequest(document_id=[document_id]))
    for document in documents:
        if isinstance(document, types.Document):  # an unknown id comes back as DocumentEmpty
            account().sticker_documents[document.id] = document
    return account().sticker_documents.get(document_id)


def still_sticker(data, size):
    """PNG bytes at most size×size, transparency kept; None when the picture is empty (some
    animated custom emoji only have an outline, no real still preview)."""
    image = Image.open(BytesIO(data)).convert("RGBA")
    if image.getbbox() is None:  # fully transparent
        return None
    image.thumbnail((size, size), Image.LANCZOS)
    out = BytesIO()
    image.save(out, "PNG", optimize=True)
    return out.getvalue()


# A sticker as a still picture (PNG, at most size×size): a static sticker's own image; for
# animated and video stickers, the still preview Telegram makes for every sticker. Cached in
# sticker_cache/. chat_id and message_id say where it came from, in case the server has to
# read the message again; without them the id is taken as a custom emoji's (they can be
# asked by id alone). 404 when there is nothing to show (the app then shows text).
async def sticker_download(document_id, chat_id, message_id, still):
    """A sticker's document and its file: for still=True the picture a still is made from (the
    sticker itself if static, else Telegram's preview), otherwise the whole file (WebP, TGS or
    WebM). A file reference that expired is fetched again once. 404 when unknown."""
    document = account().sticker_documents.get(document_id)
    try:
        if document is None:
            document = await sticker_document(document_id, chat_id, message_id)
        if document is None:
            raise HTTPException(status_code=404, detail="Unknown sticker")
        thumb = -1 if still and document.mime_type != "image/webp" else None
        try:
            return document, await client.download_media(document, file=bytes, thumb=thumb)
        except errors.FileReferenceExpiredError:
            document = await sticker_document(document_id, chat_id, message_id)
            if document is None:
                raise HTTPException(status_code=404, detail="Sticker no longer available")
            return document, await client.download_media(document, file=bytes, thumb=thumb)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)


# The hook a module answers to make animated stickers move (since 0.16.0-beta, the animated
# module): sticker.animate(data, kind, size) with the sticker's whole file as bytes, kind
# "animated" (TGS, Lottie) or "video" (WebM), and the size in px; returns an animated WebP of at
# most size x size, as bytes, or None. Only plain values cross, so module API 1 never depends
# on Telethon's objects.
STICKER_ANIMATE = "sticker.animate"


animation_failed = set()  # (document id, size) a module couldn't animate: not asked again


async def animated_sticker(document_id, size, chat_id, message_id):
    """The animated WebP of an animated or video sticker, made by a module and cached, or None
    (no such module, a static sticker, or it failed): then the still picture is used. The cached
    file is named after the module version that made it (since 0.16.3-beta:
    <id>_anim_<size>_animated-1.1.0.webp), so updating the module makes them again."""
    maker = module_loader.hook_maker(STICKER_ANIMATE)
    path = cache_path(STICKER_CACHE, f"{document_id}_anim_{size}_{maker}.webp")
    if in_cache(path):
        with open(path, "rb") as f:
            webp = f.read()
        sent(path)
        return webp
    if (document_id, size) in animation_failed:
        return None
    known = account().sticker_documents.get(document_id)
    if known is not None and STICKER_KINDS.get(known.mime_type) not in ("animated", "video"):
        return None  # static: nothing to animate, and nothing downloaded to find out
    document, data = await sticker_download(document_id, chat_id, message_id, still=False)
    kind = STICKER_KINDS.get(document.mime_type)
    if kind not in ("animated", "video") or not data:
        return None
    started = time.monotonic()
    webp = await module_loader.call_hook(STICKER_ANIMATE, data, kind, size, thread=True)
    if not isinstance(webp, (bytes, bytearray)) or not webp:
        animation_failed.add((document_id, size))
        return None
    name = {"animated": "Lottie (TGS)", "video": "video (WebM)"}[kind]
    print(f"Animated a {name} sticker at {size} px: {len(data) / 1024:.0f} KB -> "
          f"{len(webp) / 1024:.0f} KB in {time.monotonic() - started:.1f} s")
    with open(path, "wb") as f:
        f.write(webp)
    # Older versions of it (another module version, or from before 0.16.3) are no use any more
    folder, mine = os.path.split(path)
    for old in os.listdir(folder):
        if old.startswith(f"{document_id}_anim_{size}") and old.endswith(".webp") and old != mine:
            os.remove(os.path.join(folder, old))
    stored()
    sent(path)
    return bytes(webp)


@api.get("/sticker/{document_id}")
@recovering
async def get_sticker(document_id: int, size: int = 128, chat_id: Optional[int] = None,
                      message_id: Optional[int] = None, animated: bool = False):
    if size not in STICKER_SIZES:
        raise HTTPException(status_code=400, detail="size must be 32-512")
    headers = {"Cache-Control": "private, max-age=604800"}  # a sticker never changes
    # animated=true (apps from 0.16.0-beta, with a module answering sticker.animate): an
    # animated WebP; otherwise, or if it can't be made, the still PNG as always
    if animated and module_loader.has_hook(STICKER_ANIMATE):
        webp = await animated_sticker(document_id, size, chat_id, message_id)
        if webp is not None:
            return Response(webp, media_type="image/webp", headers=headers)
    path = cache_path(STICKER_CACHE, f"{document_id}_still_{size}.png")
    if not in_cache(path):
        _, data = await sticker_download(document_id, chat_id, message_id, still=True)
        picture = still_sticker(data, size) if data else None
        if picture is None:
            raise HTTPException(status_code=404, detail="No picture for this sticker")
        with open(path, "wb") as f:
            f.write(picture)
        stored()
    with open(path, "rb") as f:
        data = f.read()
    sent(path)
    return Response(data, media_type="image/png", headers=headers)


async def installed_sticker_sets():
    found = await client(functions.messages.GetAllStickersRequest(hash=0))
    for pack in found.sets:
        account().sticker_sets[pack.id] = types.InputStickerSetID(id=pack.id, access_hash=pack.access_hash)
    return found.sets


# My sticker packs, in my order, for the app's sticker screen (it puts Recent and Favorites
# first, which come from /sticker_set/recent and /sticker_set/faved)
@api.get("/sticker_sets")
@recovering
async def get_sticker_sets():
    try:
        packs = await installed_sticker_sets()
    except errors.RPCError as e:
        bad_request(e)
    return {"packs": [{"id": p.id, "title": p.title, "count": p.count} for p in packs]}


def load_local_favorites():
    try:
        with open(account().local_favorites, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return []


def save_local_favorites(entries):
    path = account().local_favorites
    part = path + ".part"  # replaced in one step, so a crash never leaves half a file
    with open(part, "w", encoding="utf-8") as f:
        json.dump(entries, f)
    os.replace(part, path)


async def local_favorite_documents():
    """The local favourites' documents, in the list's order: each pack is loaded once.
    Stickers whose pack is gone are left out."""
    entries = load_local_favorites()
    found = {}
    for set_id, set_hash in {(e["set_id"], e["set_hash"]) for e in entries}:
        try:
            result = await client(functions.messages.GetStickerSetRequest(
                stickerset=types.InputStickerSetID(id=set_id, access_hash=set_hash), hash=0))
        except errors.RPCError as e:
            if is_cluster_error(e):
                raise
            continue  # the pack was deleted: its stickers drop out
        for document in result.documents:
            found[document.id] = document
    return [found[e["id"]] for e in entries if e["id"] in found]


# Adds a sticker the server has seen to the local favourites (first in the list), or with
# remove=true takes it out. Stickers that belong to no pack can't be kept (no way to reload).
@api.post("/sticker/{sticker_id}/local_favorite")
async def local_favorite(sticker_id: int, remove: bool = False):
    entries = [e for e in load_local_favorites() if e["id"] != sticker_id]
    if not remove:
        document = account().sticker_documents.get(sticker_id)
        if document is None:
            raise HTTPException(status_code=404, detail="Unknown sticker: open its pack again")
        attr = next((a for a in document.attributes if isinstance(a, types.DocumentAttributeSticker)), None)
        if attr is None or not isinstance(attr.stickerset, types.InputStickerSetID):
            raise HTTPException(status_code=400, detail="This sticker belongs to no pack")
        entries.insert(0, {"id": sticker_id, "set_id": attr.stickerset.id, "set_hash": attr.stickerset.access_hash})
    save_local_favorites(entries)
    return {"ok": True}


# One pack's stickers ({id, emoji, kind} each, remembered for /sticker and /send_sticker):
# "recent" = my recently used, "faved" = my Telegram favorites, "local" = the local
# favourites above, or an installed pack's id
@api.get("/sticker_set/{pack}")
@recovering
async def get_sticker_set(pack: str):
    try:
        if pack == "local":
            documents = await local_favorite_documents()
        elif pack == "recent":
            documents = (await client(functions.messages.GetRecentStickersRequest(hash=0))).stickers
        elif pack == "faved":
            documents = (await client(functions.messages.GetFavedStickersRequest(hash=0))).stickers
        else:
            try:
                pack_id = int(pack)
            except ValueError:
                raise HTTPException(status_code=404, detail="No such pack")
            if pack_id not in account().sticker_sets:
                await installed_sticker_sets()
            if pack_id not in account().sticker_sets:
                raise HTTPException(status_code=404, detail="No such pack")
            found = await client(functions.messages.GetStickerSetRequest(stickerset=account().sticker_sets[pack_id], hash=0))
            documents = found.documents
    except errors.RPCError as e:
        bad_request(e)
    return {"stickers": [sticker_json(d) for d in documents]}


# The pack a sticker comes from (message menu > View sticker pack): {pack: {id, title, count,
# installed}, stickers: [...]}. chat_id and message_id as in /sticker, in case the server has
# to read the message again. 404 for stickers that belong to no pack.
@api.get("/sticker_set_of/{sticker_id}")
@recovering
async def get_sticker_set_of(sticker_id: int, chat_id: Optional[int] = None, message_id: Optional[int] = None):
    try:
        document = account().sticker_documents.get(sticker_id) or await sticker_document(sticker_id, chat_id, message_id)
        attr = None if document is None else next(
            (a for a in document.attributes if isinstance(a, types.DocumentAttributeSticker)), None)
        if attr is None or not isinstance(attr.stickerset, types.InputStickerSetID):
            raise HTTPException(status_code=404, detail="This sticker belongs to no pack")
        found = await client(functions.messages.GetStickerSetRequest(stickerset=attr.stickerset, hash=0))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    pack = found.set
    account().sticker_sets[pack.id] = types.InputStickerSetID(id=pack.id, access_hash=pack.access_hash)
    return {
        "pack": {"id": pack.id, "title": pack.title, "count": pack.count,
                 "installed": pack.installed_date is not None and not pack.archived},
        "stickers": [sticker_json(d) for d in found.documents],
    }


# Adds a sticker the server has seen to my Telegram Favorites, or with unfave=true takes it out.
# Telegram allows 5 favorites without Premium; a refusal's reason goes to the log.
@api.post("/sticker/{sticker_id}/fave")
@recovering
async def fave_sticker(sticker_id: int, unfave: bool = False):
    document = account().sticker_documents.get(sticker_id)
    if document is None:
        raise HTTPException(status_code=404, detail="Unknown sticker: open its pack again")
    try:
        await client(functions.messages.FaveStickerRequest(id=utils.get_input_document(document), unfave=unfave))
    except errors.RPCError as e:
        print(f"Telegram refused to {'unfave' if unfave else 'fave'} a sticker: {e}")  # for the log screen
        bad_request(e)
    return {"ok": True}


# Adds a pack to my packs (one the server has seen: listed, or opened from a sticker)
@api.post("/sticker_set/{pack_id}/install")
@recovering
async def install_sticker_set(pack_id: int):
    if pack_id not in account().sticker_sets:
        raise HTTPException(status_code=404, detail="Unknown pack: open it again")
    try:
        await client(functions.messages.InstallStickerSetRequest(stickerset=account().sticker_sets[pack_id], archived=False))
    except errors.RPCError as e:
        bad_request(e)
    return {"ok": True}


# Sends a sticker the server has seen (in a pack listed above, or in a message); topics and
# replies as in /send. Answers with the new message's id and the sticker.
@api.post("/send_sticker")
@recovering
async def send_sticker(body: SendStickerBody):
    document = account().sticker_documents.get(body.sticker_id)
    if document is None:
        raise HTTPException(status_code=404, detail="Unknown sticker: open its pack again")
    try:
        msg = await client.send_file(body.chat_id, document,
                                     reply_to=reply_target(body.topic_id, body.reply_to_id))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    return {"id": msg.id, "sticker": sticker_json(document)}
