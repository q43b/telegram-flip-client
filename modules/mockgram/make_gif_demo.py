"""Makes scenes/gif_demo.mp4, the made-up Telegram's one GIF: a small silent looping MP4, as Telegram keeps
GIFs. Drawn with the module's own pictures (assets.py), encoded as MPEG-4 Part 2, which every Android plays.

Not run by the module; run it once when the picture should change, with PyAV on the path (the animated
module's lib/ has it):
    venv\\Scripts\\python.exe modules\\mockgram\\make_gif_demo.py server\\modules\\animated\\lib
"""
import io
import math
import os
import sys

if len(sys.argv) > 1:
    sys.path.insert(0, os.path.abspath(sys.argv[1]))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import av  # noqa: E402
from PIL import Image  # noqa: E402

from mockgram import assets  # noqa: E402

W, H, FPS, SECONDS = 240, 180, 12, 2


def frame(i):
    """Frame i: the sun sticker bouncing over the sky of the sunset picture."""
    sky = Image.open(io.BytesIO(assets.picture_jpeg("sunset", W, H))).convert("RGB")
    sun = Image.open(io.BytesIO(assets.sticker_webp("sun", 96))).convert("RGBA")
    t = i / (FPS * SECONDS)
    x = int((W - 96) / 2 + math.sin(t * 2 * math.pi) * 60)
    y = int(20 + abs(math.sin(t * 2 * math.pi)) * 40)
    sky.paste(sun, (x, y), sun)
    return sky


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scenes", "gif_demo.mp4")
    container = av.open(out, "w")
    stream = container.add_stream("mpeg4", rate=FPS)
    stream.width, stream.height, stream.pix_fmt = W, H, "yuv420p"
    stream.bit_rate = 200_000
    for i in range(FPS * SECONDS):
        for packet in stream.encode(av.VideoFrame.from_image(frame(i))):
            container.mux(packet)
    for packet in stream.encode():
        container.mux(packet)
    container.close()
    print(f"{out}: {os.path.getsize(out)} bytes")


if __name__ == "__main__":
    main()
