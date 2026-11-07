"""Makes the app's and the server's icons from the owner's drawings in this folder (512 px):
app-icon.png (the flip phone) and server-icon.png (the laptop). Run it after changing a drawing:

    venv\\Scripts\\python.exe art\\make_icons.py

then rebuild the app and the server. Needs Pillow (the server's requirements have it).
"""
import os

from PIL import Image, ImageDraw

ART = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(ART)
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")


def app_icons():
    drawing = Image.open(os.path.join(ART, "app-icon.png")).convert("RGBA")
    # Android 8+: the adaptive icon's picture (ic_launcher_foreground.xml shows it in the
    # visible 72dp, at most 288 px on the sharpest screens). BOX scaling and lossless WebP keep
    # pixel art crisp: no halos around dark outlines, no smudged pixel edges
    drawing.resize((288, 288), Image.BOX).save(
        os.path.join(RES, "drawable-nodpi", "ic_launcher_art.webp"), lossless=True, method=6)
    # Older Android: a square and a round picture for each screen density
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        folder = os.path.join(RES, f"mipmap-{density}")
        drawing.resize((px, px), Image.BOX).save(os.path.join(folder, "ic_launcher.webp"), lossless=True, method=6)
        big = drawing.resize((px * 4, px * 4), Image.BOX)  # cut the circle at 4x, for a smooth edge
        circle = Image.new("L", big.size, 0)
        ImageDraw.Draw(circle).ellipse((0, 0, big.width - 1, big.height - 1), fill=255)
        big.putalpha(circle)
        big.resize((px, px), Image.BOX).save(os.path.join(folder, "ic_launcher_round.webp"), lossless=True, method=6)
    corner = drawing.getpixel((5, 5))
    print("App icons made. The drawing's corner color is #%02X%02X%02X; ic_launcher_background.xml "
          "should use the same." % corner[:3])


def server_icon():
    drawing = Image.open(os.path.join(ART, "server-icon.png")).convert("RGBA")
    # Each size scaled here, the same way as the app's (Pillow would use its own filter)
    sizes = [drawing.resize((px, px), Image.BOX) for px in (256, 128, 64, 48, 32, 24, 16)]
    sizes[0].save(os.path.join(ROOT, "server", "packaging", "tgflip-server.ico"),
                  sizes=[image.size for image in sizes], append_images=sizes[1:])
    print("Server icon made (server/packaging/tgflip-server.ico).")


if __name__ == "__main__":
    app_icons()
    server_icon()
