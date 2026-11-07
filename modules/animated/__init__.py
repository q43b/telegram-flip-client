"""Animated stickers: answers the server's sticker.animate hook (since server 0.16.0-beta) by
turning Telegram's animated stickers into animated WebP, which the app plays.

- Lottie stickers (TGS: gzipped JSON) are drawn frame by frame with rlottie-python.
- Video stickers (WebM, VP9 with transparency) are decoded with PyAV, through the libvpx-vp9
  decoder: ffmpeg's own VP9 decoder would drop the transparency.
- Pillow (the server's own) writes the WebP.

Kept light for the phone: at most FPS frames a second and MAX_SECONDS long (Telegram's stickers
are at most 3 s), fitted into the size the app asked for. The server caches the result.

Since 1.1.0, heavy stickers are slimmed: frames (almost) the same as the one before are merged
into it (a longer duration instead: free size, nothing visible), and a WebP over its size budget
is made again at a lower frame rate (SLOWER_FPS), until it fits or reaches the lowest.
"""
import gzip
import threading
from io import BytesIO

FPS = 20
SLOWER_FPS = (15, 12, 10)  # tried in turn while a WebP is over its budget
MAX_SECONDS = 4
QUALITY = 70
BUDGET_144 = 150 * 1024  # bytes for a 144 px sticker (the chat's); scaled by the area for other sizes
SAME_FRAME = 1.0  # average difference per channel (of 255) below which two frames count as the same
# Converting is heavy for the PC's processor; at most this many at once (the rest wait)
at_once = threading.Semaphore(2)
log = print  # context.log once set up


def fit(width, height, size):
    scale = size / max(width, height)
    return max(1, round(width * scale)), max(1, round(height * scale))


def lottie_frames(data, size):
    """The frames at FPS, each with its start time (ms), and the end time (ms)."""
    from rlottie_python import LottieAnimation
    animation = LottieAnimation.from_data(gzip.decompress(data).decode("utf-8"))
    try:
        total = animation.lottie_animation_get_totalframe()
        rate = animation.lottie_animation_get_framerate() or 60
        width, height = fit(*animation.lottie_animation_get_size(), size)
        step = max(1, round(rate / FPS))  # 60 fps stickers: every 3rd frame
        last = min(total, rate * MAX_SECONDS)
        numbers = list(range(0, last, step))
        frames = [animation.render_pillow_frame(i, width=width, height=height) for i in numbers]
        return frames, [1000 * i / rate for i in numbers], 1000 * (numbers[-1] + step) / rate if numbers else 0
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
    """The frames at up to FPS, each with its start time (ms), and the end time (ms)."""
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
                times.append(1000 * seconds)
                next_time = seconds + 1 / FPS
    end = times[-1] + 1000 / FPS if times else 0
    return frames, times, end


def at_rate(frames, times, end, fps):
    """The frames kept at fps (from the FPS ones, by their times), with their durations (ms)."""
    kept, kept_times, next_time = [], [], 0.0
    for frame, t in zip(frames, times):
        if t + 1e-6 >= next_time:
            kept.append(frame)
            kept_times.append(t)
            next_time = t + 1000 / fps
    durations = [round(b - a) for a, b in zip(kept_times, kept_times[1:] + [end])]
    return kept, [max(20, d) for d in durations]


def merge_repeats(frames, durations):
    """A frame (almost) the same as the one before is dropped; the one before shows longer."""
    from PIL import ImageChops, ImageStat
    kept, kept_durations = [frames[0]], [durations[0]]
    for frame, duration in zip(frames[1:], durations[1:]):
        difference = ImageChops.difference(kept[-1].convert("RGBA"), frame.convert("RGBA"))
        if max(ImageStat.Stat(difference).mean) < SAME_FRAME:
            kept_durations[-1] += duration
        else:
            kept.append(frame)
            kept_durations.append(duration)
    return kept, kept_durations


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
        frames, times, end = lottie_frames(data, size) if kind == "animated" else video_frames(data, size)
        if not frames:
            return None
        budget = BUDGET_144 * (size / 144) ** 2
        first = None
        for fps in (FPS, *SLOWER_FPS):
            webp = to_webp(*merge_repeats(*at_rate(frames, times, end, fps)))
            first = first or (len(webp), len(frames))
            if len(webp) <= budget:
                break
        if fps != FPS:
            name = "Lottie" if kind == "animated" else "video"
            log(f"Slimmed a {name} sticker at {size} px: {first[0] / 1024:.0f} KB at {FPS} fps -> "
                f"{len(webp) / 1024:.0f} KB at {fps} fps")
        return webp


def setup(context):
    global log
    # Loaded right away (not at the first sticker), so a library that can't load on this PC
    # shows as "failed: <reason>" when the server starts, instead of stickers silently staying still
    import av  # noqa: F401
    import rlottie_python  # noqa: F401
    log = context.log
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
