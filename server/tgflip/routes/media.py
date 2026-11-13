"""Media: profile photos, photos, videos, round videos and voice messages, both ways.
"""
import os
import asyncio
import shutil
import tempfile
from io import BytesIO
from fastapi import HTTPException, Request, Response
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask
from typing import Optional
from PIL import Image
from telethon import errors, types

from tgflip.account import account, client
from tgflip.api import api
from tgflip.cache import IMAGE_CACHE, PHOTO_CACHE, VIDEO_CACHE, cache_path, in_cache, sent, stored
from tgflip.messages import file_of, gif_of, photo_id_of, photo_sizes, reply_target, round_of, video_of, voice_of
from tgflip.telegram import bad_request, recovering


PHOTO_SIZES = range(16, 161)  # Telegram's small profile photo is 160×160


def shrink_photo(data, size):
    """JPEG bytes resized to size×size (profile photos are square)."""
    image = Image.open(BytesIO(data)).convert("RGB")
    if image.size != (size, size):
        image = image.resize((size, size), Image.LANCZOS)
    out = BytesIO()
    image.save(out, "JPEG", quality=85)
    return out.getvalue()


# photo_id (from /chats) lets a saved copy answer without asking Telegram at all
@api.get("/photo/{chat_id}")
@recovering
async def get_photo(chat_id: int, size: int = 48, photo_id: Optional[str] = None):
    if size not in PHOTO_SIZES:
        raise HTTPException(status_code=400, detail=f"size must be {PHOTO_SIZES.start}-{PHOTO_SIZES.stop - 1}")
    headers = {"Cache-Control": "private, max-age=86400"}

    def cached(pid):
        return cache_path(PHOTO_CACHE, f"{chat_id}_{pid}_{size}.jpg")

    if photo_id and photo_id.isdigit() and in_cache(cached(photo_id)):
        with open(cached(photo_id), "rb") as f:
            data = f.read()
        sent(cached(photo_id))
        return Response(data, media_type="image/jpeg", headers=headers)
    try:
        entity = await client.get_entity(chat_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    current = photo_id_of(entity)
    if current is None:
        raise HTTPException(status_code=404, detail="No photo")
    path = cached(current)
    if not in_cache(path):
        try:
            data = await client.download_profile_photo(entity, file=bytes, download_big=False)
        except errors.RPCError as e:
            bad_request(e)
        if not data:
            raise HTTPException(status_code=404, detail="No photo")
        # Copies of this chat's older photos are no use any more
        folder = os.path.dirname(path)
        for old in os.listdir(folder):
            if old.startswith(f"{chat_id}_") and not old.startswith(f"{chat_id}_{current}_"):
                os.remove(os.path.join(folder, old))
        with open(path, "wb") as f:
            f.write(shrink_photo(data, size))
        stored()
    with open(path, "rb") as f:
        data = f.read()
    sent(path)
    return Response(data, media_type="image/jpeg", headers=headers)


MAX_VIDEO_BYTES = 100 * 1024 * 1024  # more takes minutes through the proxy, and the phone has little room


SCREEN = (480, 854)  # the flip phone's screen; photos are shrunk to fit inside it


def shrink_to_fit(data, box):
    """JPEG bytes no larger than box (width, height), keeping the picture's shape."""
    image = Image.open(BytesIO(data)).convert("RGB")
    image.thumbnail(box, Image.LANCZOS)  # only ever makes it smaller
    out = BytesIO()
    image.save(out, "JPEG", quality=80)
    return out.getvalue()


# A photo from a message, shrunk to fit the phone's screen (usually 30-60 KB). Telegram
# keeps several sizes; the smallest one that still fills the screen is downloaded, not the
# original, which can be several MB. Saved in image_cache/, so opening it again is instant.
# full=true instead sends the biggest size Telegram keeps (up to 2560 px), unchanged and
# not saved here: for saving a photo on the phone.
@api.get("/image/{chat_id}/{message_id}")
@recovering
async def get_image(chat_id: int, message_id: int, full: bool = False):
    headers = {"Cache-Control": "private, max-age=86400"}
    if full:
        return Response(await full_photo(chat_id, message_id), media_type="image/jpeg", headers=headers)
    path = cache_path(IMAGE_CACHE, f"{chat_id}_{message_id}.jpg")
    if not in_cache(path):
        try:
            message = await client.get_messages(chat_id, ids=message_id)
        except ValueError:
            raise HTTPException(status_code=404, detail="Chat not found")
        except errors.RPCError as e:
            bad_request(e)
        sizes = photo_sizes(message) if message is not None else []
        if not sizes:
            raise HTTPException(status_code=404, detail="No such photo")
        size = next((s for s in sizes if max(s.w, s.h) >= max(SCREEN)), sizes[-1])
        try:
            data = await client.download_media(message, file=bytes, thumb=size.type)  # by type: works for every kind of size
        except errors.RPCError as e:
            bad_request(e)
        if not data:
            raise HTTPException(status_code=404, detail="No such photo")
        with open(path, "wb") as f:
            f.write(shrink_to_fit(data, SCREEN))
        stored()
    with open(path, "rb") as f:
        data = f.read()
    sent(path)
    return Response(data, media_type="image/jpeg", headers=headers)


async def full_photo(chat_id, message_id):
    """A photo message's biggest size as JPEG bytes (Telegram keeps photos as JPEG)."""
    try:
        message = await client.get_messages(chat_id, ids=message_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    if message is None or not photo_sizes(message):
        raise HTTPException(status_code=404, detail="No such photo")
    try:
        data = await client.download_media(message, file=bytes)
    except errors.RPCError as e:
        bad_request(e)
    if not data:
        raise HTTPException(status_code=404, detail="No such photo")
    return data


async def download_video(chat_id, message_id, path):
    try:
        message = await client.get_messages(chat_id, ids=message_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    info = (video_of(message) or round_of(message) or gif_of(message)) if message is not None else None
    if info is None:
        raise HTTPException(status_code=404, detail="No such video")
    if info["size"] > MAX_VIDEO_BYTES:
        raise HTTPException(status_code=413, detail="Video too big (over 100 MB)")
    part = path + ".part"  # renamed when complete, so a half download is never served
    await client.download_media(message, file=part)
    os.replace(part, path)
    stored()


# A still picture of a GIF or a video (Telegram's own preview of it), at most size px on its long side,
# as JPEG (since 1.9.0: the GIF's picture in the chat until it plays). Kept in image_cache.
@api.get("/thumb/{chat_id}/{message_id}")
@recovering
async def get_thumb(chat_id: int, message_id: int, size: int = 256):
    size = max(32, min(size, 512))
    path = cache_path(IMAGE_CACHE, f"{chat_id}_{message_id}_thumb_{size}.jpg")
    if in_cache(path):
        return FileResponse(path, media_type="image/jpeg", background=BackgroundTask(sent, path))
    try:
        message = await client.get_messages(chat_id, ids=message_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    if message is None or (gif_of(message) or video_of(message)) is None:
        raise HTTPException(status_code=404, detail="No such GIF or video")
    try:
        data = await client.download_media(message, file=bytes, thumb=-1)
    except errors.RPCError as e:
        bad_request(e)
    if not data:
        raise HTTPException(status_code=404, detail="No picture of it")
    image = Image.open(BytesIO(data)).convert("RGB")
    image.thumbnail((size, size), Image.LANCZOS)
    image.save(path, "JPEG", quality=80)
    stored()
    return FileResponse(path, media_type="image/jpeg", background=BackgroundTask(sent, path))


# A video or round video message, as Telegram keeps it (no converting yet). Downloaded once from Telegram into
# video_cache/ and then sent from there; the answer starts only when that download is done.
@api.get("/video/{chat_id}/{message_id}")
@recovering
async def get_video(chat_id: int, message_id: int):
    path = cache_path(VIDEO_CACHE, f"{chat_id}_{message_id}.mp4")
    if not in_cache(path):
        task = account().video_downloads.get(path)
        if task is None:
            task = asyncio.create_task(download_video(chat_id, message_id, path))
            account().video_downloads[path] = task
            task.add_done_callback(lambda _: account().video_downloads.pop(path, None))
        try:
            await asyncio.shield(task)  # the phone giving up must not stop a shared download
        except errors.RPCError as e:
            bad_request(e)
    # With the cache off, it goes once sent (if another request is still sending it, the
    # removal fails and the next cleaning takes it)
    return FileResponse(path, media_type="video/mp4", background=BackgroundTask(sent, path))


# The voice message's OGG/Opus file as Telegram keeps it (about 100-250 KB a minute).
# Not saved on the server: the phone keeps its own copy.
@api.get("/voice/{chat_id}/{message_id}")
@recovering
async def get_voice(chat_id: int, message_id: int):
    try:
        message = await client.get_messages(chat_id, ids=message_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    if message is None or voice_of(message) is None:
        raise HTTPException(status_code=404, detail="No such voice message")
    try:
        data = await client.download_media(message, file=bytes)
    except errors.RPCError as e:
        bad_request(e)
    return Response(data, media_type="audio/ogg", headers={"Cache-Control": "private, max-age=86400"})


MAX_VOICE_BYTES = 10 * 1024 * 1024  # about 40 minutes of the phone's recordings


MAX_PHOTO_BYTES = 10 * 1024 * 1024  # Telegram's own limit for photos


PHOTO_TYPES = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}


# The body is the recording itself (OGG/Opus, as the phone's recorder makes it), so no
# form parsing is needed; the rest comes in the query. Sent as a voice message.
@api.post("/send_voice")
@recovering
async def send_voice(request: Request, chat_id: int, duration: int,
                     topic_id: Optional[int] = None, reply_to_id: Optional[int] = None):
    data = await request.body()
    if not data or len(data) > MAX_VOICE_BYTES:
        raise HTTPException(status_code=400, detail="Empty or too large")
    file = BytesIO(data)
    file.name = "voice.ogg"  # tells Telethon the type
    try:
        msg = await client.send_file(
            chat_id, file, voice_note=True,
            attributes=[types.DocumentAttributeAudio(duration=duration, voice=True)],
            reply_to=reply_target(topic_id, reply_to_id))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    return {"id": msg.id}


# The body is the picture itself (JPEG, PNG or WebP; Content-Type says which), the rest
# comes in the query, like /send_voice. Sent as a photo, so Telegram makes its own sizes.
@api.post("/send_photo")
@recovering
async def send_photo(request: Request, chat_id: int, caption: str = "",
                     topic_id: Optional[int] = None, reply_to_id: Optional[int] = None):
    data = await request.body()
    if not data or len(data) > MAX_PHOTO_BYTES:
        raise HTTPException(status_code=400, detail="Empty or too large (at most 10 MB)")
    content_type = request.headers.get("content-type", "image/jpeg").split(";")[0].strip()
    file = BytesIO(data)
    file.name = "photo." + PHOTO_TYPES.get(content_type, "jpg")  # tells Telethon the type
    try:
        msg = await client.send_file(chat_id, file, caption=caption or None, force_document=False,
                                     reply_to=reply_target(topic_id, reply_to_id))
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    return {"id": msg.id}


MAX_FILE_BYTES = MAX_VIDEO_BYTES  # files follow videos: more takes minutes through the proxy


BACKSLASH = chr(92)
BAD_NAME_CHARS = '<>:"/|?*' + BACKSLASH


def safe_name(name):
    """A file name without folders or characters Windows refuses (the name only travels to Telegram)."""
    name = os.path.basename((name or "").replace(BACKSLASH, "/"))
    name = "".join("_" if c in BAD_NAME_CHARS or ord(c) < 32 else c for c in name).strip(" .")
    return name[:120] or "file"


def ascii_name(name):
    return "".join(c if 32 <= ord(c) < 127 and c not in '"' + BACKSLASH else "_" for c in name) or "file"


# A file sent as a document, as Telegram keeps it. Downloaded to a temporary folder and sent from
# there; the answer starts when the download is done, the temporary file goes once it is sent (the
# phone keeps its own copy). The name comes in Content-Disposition (the phone knows it anyway).
@api.get("/file/{chat_id}/{message_id}")
@recovering
async def get_file(chat_id: int, message_id: int):
    try:
        message = await client.get_messages(chat_id, ids=message_id)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
    info = file_of(message) if message is not None else None
    if info is None:
        raise HTTPException(status_code=404, detail="No such file")
    if info["size"] > MAX_FILE_BYTES:
        raise HTTPException(status_code=413, detail="File too big (over 100 MB)")
    folder = tempfile.mkdtemp(prefix="tgflip_file_")
    path = os.path.join(folder, safe_name(info["name"]))
    try:
        await client.download_media(message, file=path)
    except errors.RPCError as e:
        shutil.rmtree(folder, ignore_errors=True)
        bad_request(e)
    except BaseException:
        shutil.rmtree(folder, ignore_errors=True)
        raise
    return FileResponse(path, media_type=info["mime"] or "application/octet-stream",
                        headers={"Content-Disposition": f'attachment; filename="{ascii_name(info["name"])}"'},
                        background=BackgroundTask(shutil.rmtree, folder, True))


# Sends a file as a plain document (never recompressed). The body is the file itself, the
# name and the rest come in the query. Written to disk as it arrives (up to 100 MB, never held
# in memory), then uploaded; a reconnect retries only the upload.
@api.post("/send_file")
async def send_file(request: Request, chat_id: int, name: str, caption: str = "",
                    topic_id: Optional[int] = None, reply_to_id: Optional[int] = None):
    with tempfile.TemporaryDirectory() as folder:
        path = os.path.join(folder, safe_name(name))
        received = 0
        with open(path, "wb") as out:
            async for chunk in request.stream():
                out.write(chunk)
                received += len(chunk)
                if received > MAX_FILE_BYTES:
                    raise HTTPException(status_code=413, detail="File too big (over 100 MB)")
        if received == 0:
            raise HTTPException(status_code=400, detail="Empty file")
        msg = await upload_file(chat_id, path, caption, reply_target(topic_id, reply_to_id))
    return {"id": msg.id}


@recovering
async def upload_file(chat_id, path, caption, reply_to):
    try:
        return await client.send_file(chat_id, path, caption=caption or None, reply_to=reply_to, force_document=True)
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)


MAX_THUMB_BYTES = 200 * 1024  # Telegram's limit for a video's preview picture


# Sends a video. The body is the phone's preview picture (thumb_size bytes of JPEG, may be 0)
# followed by the video file. Telegram only shows a video's length, shape and preview when
# the sender gives them, and the server can't read them from the file, so the phone does
# (duration, w, h). The body is written to disk as it arrives (up to 100 MB, never held in
# memory), then uploaded; a reconnect retries only the upload.
@api.post("/send_video")
async def send_video(request: Request, chat_id: int, duration: int, w: int, h: int,
                     thumb_size: int = 0, caption: str = "",
                     topic_id: Optional[int] = None, reply_to_id: Optional[int] = None):
    if not 0 <= thumb_size <= MAX_THUMB_BYTES:
        raise HTTPException(status_code=400, detail="Bad preview size")
    with tempfile.TemporaryDirectory() as folder:
        thumb_path = os.path.join(folder, "thumb.jpg")
        video_path = os.path.join(folder, "video.mp4")
        received = 0
        with open(thumb_path, "wb") as thumb, open(video_path, "wb") as video:
            async for chunk in request.stream():
                if received < thumb_size:  # the first thumb_size bytes are the preview
                    split = thumb_size - received
                    thumb.write(chunk[:split])
                    video.write(chunk[split:])
                else:
                    video.write(chunk)
                received += len(chunk)
                if received > thumb_size + MAX_VIDEO_BYTES:
                    raise HTTPException(status_code=413, detail="Video too big (over 100 MB)")
        if received <= thumb_size:
            raise HTTPException(status_code=400, detail="No video")
        msg = await upload_video(chat_id, video_path, thumb_path if thumb_size else None,
                                 duration, w, h, caption, reply_target(topic_id, reply_to_id))
    return {"id": msg.id}


@recovering
async def upload_video(chat_id, video_path, thumb_path, duration, w, h, caption, reply_to):
    try:
        return await client.send_file(
            chat_id, video_path, caption=caption or None, thumb=thumb_path, reply_to=reply_to,
            force_document=False, supports_streaming=True,
            attributes=[types.DocumentAttributeVideo(duration=duration, w=w, h=h, supports_streaming=True)])
    except ValueError:
        raise HTTPException(status_code=404, detail="Chat not found")
    except errors.RPCError as e:
        bad_request(e)
