"""Makes the app's and the server's icons from the drawings in this folder: app-icon.png (the
flip phone) and server-icon.png (the laptop), plus *-small.png, simpler drawings for the tiniest
sizes (16-32 px, Windows' Explorer and taskbar). Run it after changing a drawing:

    venv\\Scripts\\python.exe art\\make_icons.py

then rebuild the app and the server. Needs Pillow (the server's requirements have it).

The drawings are pixel art on a grid (since 1.2.0: 48 cells, 16 for the small ones, saved at
10 screen pixels per cell). A size that's a whole multiple of the grid is scaled cell by cell
(nothing blended); other sizes are averaged (BOX: no halos around dark outlines). A drawing that
isn't on a grid is simply averaged.
"""
import os

from PIL import Image, ImageDraw

ART = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(ART)
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
SMALL_UP_TO = 32  # px: sizes up to this use the *-small.png drawing when there is one


def grid_of(drawing):
    """The drawing's pixel grid (cells per side), or None when it isn't pixel art."""
    for cells in range(8, drawing.width // 2 + 1):
        if drawing.width % cells:
            continue
        native = drawing.resize((cells, cells), Image.NEAREST)
        if native.resize(drawing.size, Image.NEAREST).tobytes() == drawing.tobytes():
            return cells
    return None


def scaled(drawing, px):
    cells = grid_of(drawing)
    if cells and px % cells == 0:
        return drawing.resize((cells, cells), Image.NEAREST).resize((px, px), Image.NEAREST)
    return drawing.resize((px, px), Image.BOX)


def open_drawing(name, px):
    small = os.path.join(ART, name.replace(".png", "-small.png"))
    path = small if px <= SMALL_UP_TO and os.path.exists(small) else os.path.join(ART, name)
    return Image.open(path).convert("RGBA")


def icon(name, px):
    return scaled(open_drawing(name, px), px)


def app_icons():
    # Android 8+: the adaptive icon's picture (ic_launcher_foreground.xml shows it in the
    # visible 72dp, at most 288 px on the sharpest screens). Lossless WebP keeps pixels crisp.
    icon("app-icon.png", 288).save(os.path.join(RES, "drawable-nodpi", "ic_launcher_art.webp"), lossless=True, method=6)
    # Older Android: a square and a round picture for each screen density
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        folder = os.path.join(RES, f"mipmap-{density}")
        square = icon("app-icon.png", px)
        square.save(os.path.join(folder, "ic_launcher.webp"), lossless=True, method=6)
        big = square.resize((px * 4, px * 4), Image.NEAREST)  # cut the circle at 4x, for a smooth edge
        circle = Image.new("L", big.size, 0)
        ImageDraw.Draw(circle).ellipse((0, 0, big.width - 1, big.height - 1), fill=255)
        big.putalpha(circle)
        big.resize((px, px), Image.BOX).save(os.path.join(folder, "ic_launcher_round.webp"), lossless=True, method=6)
    corner = open_drawing("app-icon.png", 288).getpixel((5, 5))
    print("App icons made. The drawing's corner color is #%02X%02X%02X; ic_launcher_background.xml "
          "should use the same." % corner[:3])


def server_icon():
    # Each size made here (the small drawing for the tiny ones); Pillow would use its own filter
    sizes = [icon("server-icon.png", px) for px in (256, 128, 64, 48, 32, 24, 16)]
    sizes[0].save(os.path.join(ROOT, "server", "packaging", "tgflip-server.ico"),
                  sizes=[image.size for image in sizes], append_images=sizes[1:])
    print("Server icon made (server/packaging/tgflip-server.ico).")


if __name__ == "__main__":
    app_icons()
    server_icon()
