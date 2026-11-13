"""Every picture the made-up Telegram serves is drawn here with Pillow: profile photos (a colored
square with the initial), photos (little landscapes), stickers and custom emoji. Nothing from real
Telegram, and nothing from real people. Results are kept (they never change).
"""
import math
import os
from io import BytesIO

from PIL import Image, ImageDraw, ImageFont

# Telegram's own name colors, so the avatars look like the real thing
NAME_COLORS = ["#e17076", "#faa774", "#a695e7", "#7bc862", "#6ec9cb", "#65aadd", "#ee7aae"]
INK = (11, 30, 58)

_cache = {}


def cached(key, make):
    if key not in _cache:
        _cache[key] = make()
    return _cache[key]


def _jpeg(image, quality=85):
    out = BytesIO()
    image.convert("RGB").save(out, "JPEG", quality=quality)
    return out.getvalue()


def _font(size):
    return ImageFont.load_default(size=size)


def avatar_jpeg(letter, color_index, size=640):
    """A profile photo: the initial, big, on one of Telegram's name colors."""
    def make():
        color = NAME_COLORS[color_index % len(NAME_COLORS)]
        image = Image.new("RGB", (size, size), color)
        draw = ImageDraw.Draw(image)
        draw.text((size / 2, size / 2), letter.upper()[:1] or "?", font=_font(size // 2), fill="white", anchor="mm")
        return _jpeg(image)
    return cached(("avatar", letter, color_index, size), make)


def _gradient(width, height, top, bottom):
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(1, height - 1)
        draw.line([(0, y), (width, y)], fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return image


def _sunset(w, h):
    image = _gradient(w, h, (255, 183, 94), (92, 62, 148))
    draw = ImageDraw.Draw(image)
    draw.ellipse([w * 0.55, h * 0.40, w * 0.85, h * 0.40 + w * 0.30], fill=(255, 238, 170))
    draw.polygon([(0, h), (0, h * 0.78), (w * 0.3, h * 0.66), (w * 0.6, h * 0.8), (w, h * 0.7), (w, h)], fill=(40, 28, 70))
    return image


def _mountains(w, h):
    image = _gradient(w, h, (120, 190, 235), (225, 240, 250))
    draw = ImageDraw.Draw(image)
    draw.polygon([(0, h), (0, h * 0.7), (w * 0.25, h * 0.35), (w * 0.5, h * 0.7), (w * 0.7, h * 0.3), (w, h * 0.75), (w, h)], fill=(70, 100, 130))
    draw.polygon([(w * 0.25, h * 0.35), (w * 0.2, h * 0.46), (w * 0.3, h * 0.46)], fill="white")
    draw.polygon([(w * 0.7, h * 0.3), (w * 0.64, h * 0.43), (w * 0.76, h * 0.43)], fill="white")
    draw.rectangle([0, h * 0.84, w, h], fill=(60, 120, 80))
    return image


def _city(w, h):
    image = _gradient(w, h, (20, 28, 60), (90, 70, 120))
    draw = ImageDraw.Draw(image)
    x = 0
    seed = 7
    while x < w:
        seed = (seed * 31 + 11) % 97
        bw = w * (0.06 + seed % 5 * 0.012)
        bh = h * (0.25 + seed % 9 * 0.06)
        draw.rectangle([x, h - bh, x + bw, h], fill=(18, 20, 40))
        for wy in range(int(h - bh + 8), int(h - 8), max(10, h // 28)):
            for wx in range(int(x + 6), int(x + bw - 6), max(10, w // 40)):
                if (wx + wy + seed) % 3:
                    draw.rectangle([wx, wy, wx + 4, wy + 6], fill=(255, 220, 120))
        x += bw + w * 0.008
    return image


def _coffee(w, h):
    image = Image.new("RGB", (w, h), (232, 214, 190))
    draw = ImageDraw.Draw(image)
    cx, cy, r = w * 0.5, h * 0.55, min(w, h) * 0.28
    draw.ellipse([cx - r * 1.5, cy - r * 0.35 + r * 0.7, cx + r * 1.5, cy + r * 1.05], fill=(205, 186, 160))
    draw.rectangle([cx - r, cy - r * 0.5, cx + r, cy + r * 0.6], fill="white")
    draw.ellipse([cx - r, cy - r * 0.8, cx + r, cy - r * 0.2], fill=(98, 62, 40))
    draw.arc([cx + r * 0.7, cy - r * 0.3, cx + r * 1.5, cy + r * 0.5], -90, 90, fill="white", width=max(4, int(r * 0.12)))
    return image


SCENES = {"sunset": _sunset, "mountains": _mountains, "city": _city, "coffee": _coffee}


def picture_jpeg(name, width, height):
    """A made-up photo of the named kind (sunset, mountains, city, coffee), at this size."""
    def make():
        draw_scene = SCENES.get(name, _mountains)
        return _jpeg(draw_scene(max(1, width), max(1, height)))
    return cached(("picture", name, width, height), make)


# ---- stickers and custom emoji: drawn at 512 px with an ink outline, as WebP with transparency ----
def _canvas(size):
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def _outlined(draw, s, shapes, color):
    """One outline around the union of several shapes: all of them in ink, slightly grown, then in colour."""
    t = max(3, s // 60)
    for grow, fill in ((t, INK), (0, color)):
        for kind, box in shapes:
            if kind == "ellipse":
                draw.ellipse([box[0] - grow, box[1] - grow, box[2] + grow, box[3] + grow], fill=fill)
            else:  # polygon: grown around its centre
                cx = sum(x for x, _ in box) / len(box)
                cy = sum(y for _, y in box) / len(box)
                draw.polygon([(x + (grow if x > cx else -grow), y + (grow if y > cy else -grow)) for x, y in box], fill=fill)


def _heart(draw, s):
    r = s * 0.22
    shapes = [("ellipse", [cx - r, s * 0.22, cx + r, s * 0.22 + 2 * r]) for cx in (s * 0.36, s * 0.64)]
    shapes.append(("polygon", [(s * 0.16, s * 0.44), (s * 0.84, s * 0.44), (s * 0.5, s * 0.84)]))
    _outlined(draw, s, shapes, (235, 70, 100))


def _star(draw, s):
    points = []
    for i in range(10):
        angle = -math.pi / 2 + i * math.pi / 5
        radius = s * (0.42 if i % 2 == 0 else 0.18)
        points.append((s / 2 + radius * math.cos(angle), s / 2 + radius * math.sin(angle) + s * 0.03))
    draw.polygon(points, fill=(255, 205, 60), outline=INK)
    draw.line(points + [points[0]], fill=INK, width=max(3, s // 60), joint="curve")


def _smile(draw, s):
    draw.ellipse([s * 0.1, s * 0.1, s * 0.9, s * 0.9], fill=(255, 210, 70), outline=INK, width=max(3, s // 50))
    for cx in (0.35, 0.65):
        draw.ellipse([s * (cx - 0.05), s * 0.36, s * (cx + 0.05), s * 0.5], fill=INK)
    draw.arc([s * 0.27, s * 0.35, s * 0.73, s * 0.72], 20, 160, fill=INK, width=max(4, s // 40))


def _sun(draw, s):
    for i in range(12):
        a = i * math.pi / 6
        draw.line([(s / 2 + s * 0.3 * math.cos(a), s / 2 + s * 0.3 * math.sin(a)),
                   (s / 2 + s * 0.45 * math.cos(a), s / 2 + s * 0.45 * math.sin(a))], fill=(255, 170, 40), width=max(4, s // 30))
    draw.ellipse([s * 0.22, s * 0.22, s * 0.78, s * 0.78], fill=(255, 215, 80), outline=INK, width=max(3, s // 50))


def _cloud(draw, s):
    shapes = [("ellipse", [s * b[0], s * b[1], s * b[2], s * b[3]]) for b in
              ([0.1, 0.4, 0.5, 0.78], [0.3, 0.25, 0.7, 0.7], [0.5, 0.4, 0.9, 0.78])]
    shapes.append(("polygon", [(s * 0.3, s * 0.5), (s * 0.7, s * 0.5), (s * 0.7, s * 0.775), (s * 0.3, s * 0.775)]))
    _outlined(draw, s, shapes, (230, 240, 250))


def _check(draw, s):
    draw.ellipse([s * 0.1, s * 0.1, s * 0.9, s * 0.9], fill=(110, 200, 120), outline=INK, width=max(3, s // 50))
    draw.line([(s * 0.3, s * 0.52), (s * 0.45, s * 0.67), (s * 0.72, s * 0.35)], fill="white", width=max(8, s // 18), joint="curve")


DRAWINGS = {"heart": _heart, "star": _star, "smile": _smile, "sun": _sun, "cloud": _cloud, "check": _check}


def _webp(image):
    out = BytesIO()
    image.save(out, "WEBP", quality=90)
    return out.getvalue()


def sticker_webp(drawing, size=512):
    def make():
        image, draw = _canvas(size)
        DRAWINGS.get(drawing, _smile)(draw, size)
        return _webp(image)
    return cached(("sticker", drawing, size), make)


def thumb_jpeg(webp_bytes, size=128):
    """A small JPEG preview of a sticker or custom emoji (Telegram gives each document thumbnails)."""
    def make():
        image = Image.open(BytesIO(webp_bytes)).convert("RGBA")
        image.thumbnail((size, size))
        background = Image.new("RGB", image.size, "white")
        background.paste(image, mask=image.split()[3])
        return _jpeg(background)
    return cached(("thumb", len(webp_bytes), hash(webp_bytes), size), make)


# The one GIF (scenes/gif_demo.mp4, made by make_gif_demo.py: a real looping MP4, as Telegram keeps
# GIFs), and its still picture: the same drawing as its first frame.
GIF_W, GIF_H = 240, 180


def gif_mp4():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scenes", "gif_demo.mp4")
    return cached(("gif", path), lambda: open(path, "rb").read())


def gif_thumb_jpeg():
    def make():
        sky = Image.open(BytesIO(picture_jpeg("sunset", GIF_W, GIF_H))).convert("RGB")
        sun = Image.open(BytesIO(sticker_webp("sun", 96))).convert("RGBA")
        sky.paste(sun, ((GIF_W - 96) // 2, 20), sun)
        return _jpeg(sky)
    return cached(("gif_thumb",), make)


# What a voice message or a video "contains": not playable (a made-up Telegram has no real
# recordings), but real files with the right type, so a download answers 200. Screenshots only
# need their durations.
OGG_STUB = b"OggS\x00\x02" + b"\x00" * 120
MP4_STUB = b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom" + b"\x00" * 120
