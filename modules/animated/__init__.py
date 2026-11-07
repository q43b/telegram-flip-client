"""Animated stickers: answers the server's sticker.animate hook (since server 0.16.0-beta) by
turning Telegram's animated stickers into animated WebP, which the app plays.

- Lottie stickers (TGS: gzipped JSON) are drawn frame by frame with rlottie-python.
- Video stickers (WebM, VP9 with transparency) are decoded with PyAV, through the libvpx-vp9
  decoder: ffmpeg's own VP9 decoder would drop the transparency.
- Pillow (the server's own) writes the WebP.

Kept light for the phone: at most FPS frames a second and MAX_SECONDS long (Telegram's stickers
are at most 3 s), fitted into the size the app asked for. The server caches the result.
"""
import gzip
import threading
from io import BytesIO

FPS = 20
MAX_SECONDS = 4
QUALITY = 70
# Converting is heavy for the PC's processor; at most this many at once (the rest wait)
at_once = threading.Semaphore(2)


def fit(width, height, size):
    scale = size / max(width, height)
    return max(1, round(width * scale)), max(1, round(height * scale))


def lottie_frames(data, size):
    from rlottie_python import LottieAnimation
    animation = LottieAnimation.from_data(gzip.decompress(data).decode("utf-8"))
    try:
        total = animation.lottie_animation_get_totalframe()
        rate = animation.lottie_animation_get_framerate() or 60
        width, height = fit(*animation.lottie_animation_get_size(), size)
        step = max(1, round(rate / FPS))  # 60 fps stickers: every 3rd frame
        last = min(total, rate * MAX_SECONDS)
        frames = [animation.render_pillow_frame(i, width=width, height=height) for i in range(0, last, step)]
        return frames, [round(1000 * step / rate)] * len(frames)
    finally:
        animation.lottie_animation_destroy()


def rgba_image(frame):
    """A Pillow picture from an RGBA video frame, transparency included (PyAV's own to_image
    makes RGB). Each row in the frame's memory may be padded: line_size says how long it is."""
    from PIL import Image
    plane = frame.planes[0]
    return Image.frombuffer("RGBA", (frame.width, frame.height), bytes(plane), "raw", "RGBA",
                            plane.line_size, 1)


def video_frames(data, size):
    import av
    with av.open(BytesIO(data)) as container:
        stream = container.streams.video[0]
        decoder = av.CodecContext.create("libvpx-vp9", "r")  # keeps the transparency
        width, height = fit(stream.codec_context.width or size, stream.codec_context.height or size, size)
        frames, times, next_time = [], [], 0.0
        # demux ends with an empty packet; skipped, as None (once) makes the decoder hand out
        # the frames it still holds
        packets = [p for p in container.demux(stream) if p.size] + [None]
        for packet in packets:
            for frame in decoder.decode(packet):
                seconds = float(frame.pts * stream.time_base) if frame.pts is not None else next_time
                if seconds > MAX_SECONDS:
                    break
                if seconds + 1e-6 < next_time:
                    continue  # more frames a second than FPS: skip this one
                frames.append(rgba_image(frame.reformat(width=width, height=height, format="rgba")))
                times.append(seconds)
                next_time = seconds + 1 / FPS
    durations = [max(20, round(1000 * (b - a))) for a, b in zip(times, times[1:])]
    durations.append(round(1000 / FPS))
    return frames, durations


def to_webp(frames, durations):
    out = BytesIO()
    frames[0].save(out, "WEBP", save_all=True, append_images=frames[1:], duration=durations,
                   loop=0, quality=QUALITY, method=4)
    return out.getvalue()


def animate(data, kind, size):
    """The sticker.animate hook: an animated WebP (bytes), or None for kinds it doesn't know.
    The server runs it in a worker thread; errors go to the server's log."""
    if kind not in ("animated", "video"):
        return None
    with at_once:
        frames, durations = lottie_frames(data, size) if kind == "animated" else video_frames(data, size)
        if not frames:
            return None
        return to_webp(frames, durations)


def setup(context):
    context.provide("animated_stickers", 1)
    context.hook("sticker.animate")(animate)

    # For apps that don't know this capability yet (before 0.16.0-beta)
    @context.notice("animated_stickers")
    def text():
        return ("This server can make animated and video stickers move (the Animated stickers "
                f"module {context.version}). Apps from 0.16.0-beta play them: Settings > Media "
                "and Cache > Stickers > Animated.")

    @context.on_start
    async def started():
        import av
        import rlottie_python
        context.log(f"Animated stickers ready (PyAV {av.__version__}, rlottie-python "
                    f"{getattr(rlottie_python, '__version__', '?')})")
