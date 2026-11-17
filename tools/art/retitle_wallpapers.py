#!/usr/bin/env python3
"""Re-letters the three bundled Command wallpapers (app/src/main/assets/wallpapers/*.png).

The wallpapers were drawn for an earlier private build; this replaces their heading block and
footer lines with TrekUI's own neutral wording, painting over the old text with the background
colour first. Geometry and colours are left alone.

    python -I tools/art/retitle_wallpapers.py app/src/main/assets/wallpapers app/src/main/assets/fonts
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

TITLE, SUBTITLE = "TREKUI", "COMMAND OPERATIONS"
FOOT1, FOOT2 = "SYS 47-11 • REG 2401", "PERSONAL ACCESS TERMINAL"
ORANGE, VIOLET = (255, 170, 68), (153, 136, 221)


def variable(path, size, weight):
    f = ImageFont.truetype(str(path), size)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


def spaced(d, xy, text, font, fill, tracking):
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=font, fill=fill)
        x += d.textlength(ch, font=font) + tracking


def main():
    out, fonts = Path(sys.argv[1]), Path(sys.argv[2])
    title = variable(fonts / "Antonio.ttf", 110, 700)
    sub = variable(fonts / "Antonio.ttf", 44, 400)
    mono = ImageFont.truetype(str(fonts / "ShareTech-Regular.ttf"), 36)
    for name in ("command", "minimal", "stellar"):
        p = out / f"{name}.png"
        im = Image.open(p).convert("RGB")
        bg = im.getpixel((im.width // 2, im.height // 2))
        d = ImageDraw.Draw(im)
        d.rectangle((250, 225, 900, 420), fill=bg)        # old heading block
        d.rectangle((225, 2700, 800, 2810), fill=bg)       # old footer lines
        d.text((266, 228), TITLE, font=title, fill=ORANGE)
        spaced(d, (268, 368), SUBTITLE, sub, ORANGE, 6)
        d.text((240, 2716), FOOT1, font=mono, fill=VIOLET)
        d.text((240, 2764), FOOT2, font=mono, fill=VIOLET)
        im.save(p, optimize=True)
        print("re-lettered", p)


if __name__ == "__main__":
    main()
