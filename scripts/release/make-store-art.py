#!/usr/bin/env python3
"""Generates the Google Play listing graphics from the app's own art.

    python scripts/release/make-store-art.py

If tools/art/store/icon.png and featured.png exist (the owner's art), they are fitted to Play's sizes
(512x512 icon; 1024x500 feature graphic, scaled and centre-cropped) and used for every listing instead.
Otherwise writes generated placeholders into fastlane/metadata/<app>/android/en-US/images/ for each of the four apps (launcher, messages,
keyboard, lockscreen), from that app's own adaptive-icon foreground:
  icon.png            512x512, 32-bit PNG: the adaptive-icon foreground (res/drawable/ic_launcher_fg.xml)
                      on the icon background colour, full bleed (Play applies its own mask)
  featureGraphic.png  1024x500, 24-bit PNG (no alpha): console frame, the icon and the title in Antonio

Needs Pillow (pip install pillow). The vector is rasterised by a small SVG-path parser (M/L/H/V/A/Z,
absolute and relative) with 4x supersampling.
"""
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow is required: python -m pip install pillow")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FONT = os.path.join(ROOT, "core", "src", "main", "assets", "fonts", "Antonio.ttf")
# listing folder -> (module, icon foreground drawable, title line 2, tagline)
APPS = {
    "launcher": ("app", "ic_launcher_fg", "LAUNCHER", "FIVE INTERFACES  ·  ONE HOME SCREEN"),
    "messages": ("messages", "ic_messages_fg", "MESSAGES", "SMS + MMS  ·  CONSOLE STYLE"),
    "keyboard": ("keyboard", "ic_keyboard_fg", "KEYBOARD", "TYPE ON THE CONSOLE"),
    "lockscreen": ("lockscreen", "ic_lock_fg", "LOCK SCREEN", "SECURE  ·  YOUR PIN STILL GUARDS IT"),
}


def vector_path(module, drawable):
    return os.path.join(ROOT, module, "src", "main", "res", "drawable", drawable + ".xml")


def out_dir(app):
    return os.path.join(ROOT, "fastlane", "metadata", app, "android", "en-US", "images")
ANDROID = "{http://schemas.android.com/apk/res/android}"

ORANGE, GOLD, PEACH = (255, 156, 0), (255, 204, 102), (255, 204, 153)
LAVENDER, PINK, BLUE = (180, 160, 240), (221, 136, 170), (153, 184, 255)


def hex_rgb(value):
    v = value.lstrip("#")
    if len(v) == 8:
        v = v[2:]
    return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))


def arc_points(x1, y1, rx, ry, phi, large, sweep, x2, y2, steps=24):
    """SVG elliptical arc (endpoint parameterisation) as points, excluding the start point."""
    if rx == 0 or ry == 0:
        return [(x2, y2)]
    phi = math.radians(phi)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    lam = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        coef = -coef
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def angle(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    pts = []
    for i in range(1, steps + 1):
        t = t1 + dt * i / steps
        x, y = rx * math.cos(t), ry * math.sin(t)
        pts.append((cp * x - sp * y + cx, sp * x + cp * y + cy))
    return pts


def path_polygons(d):
    """Parses SVG path data into a list of polygons (lists of points)."""
    tokens = re.findall(r"[MmLlHhVvAaZz]|-?\d*\.?\d+(?:e-?\d+)?", d)
    polys, cur = [], []
    x = y = sx = sy = 0.0
    i, cmd = 0, None

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    while i < len(tokens):
        if re.match(r"[A-Za-z]", tokens[i]):
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                if cur:
                    polys.append(cur)
                cur, x, y = [], sx, sy
                continue
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            if cur:
                polys.append(cur)
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            sx, sy = x, y
            cur = [(x, y)]
            cmd = "l" if rel else "L"
        elif c == "L":
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            cur.append((x, y))
        elif c == "H":
            nx = num()
            x = x + nx if rel else nx
            cur.append((x, y))
        elif c == "V":
            ny = num()
            y = y + ny if rel else ny
            cur.append((x, y))
        elif c == "A":
            rx, ry, rot, large, sweep, nx, ny = (num() for _ in range(7))
            ex, ey = (x + nx, y + ny) if rel else (nx, ny)
            cur.extend(arc_points(x, y, rx, ry, rot, int(large), int(sweep), ex, ey))
            x, y = ex, ey
        else:
            raise ValueError(f"unsupported path command {cmd}")
    if cur:
        polys.append(cur)
    return polys


def load_vector(vector):
    root = ET.parse(vector).getroot()
    vw = float(root.get(ANDROID + "viewportWidth"))
    vh = float(root.get(ANDROID + "viewportHeight"))
    shapes = [(hex_rgb(p.get(ANDROID + "fillColor")), path_polygons(p.get(ANDROID + "pathData")))
              for p in root.iter("path")]
    return vw, vh, shapes


def draw_vector(img, shapes, scale, ox, oy):
    d = ImageDraw.Draw(img)
    for color, polys in shapes:
        for poly in polys:
            if len(poly) >= 3:
                d.polygon([(ox + px * scale, oy + py * scale) for px, py in poly], fill=color)


def icon(vector, size=512, ss=4):
    vw, vh, shapes = load_vector(vector)
    big = size * ss
    img = Image.new("RGBA", (big, big), (0, 0, 0, 255))
    # Content spans roughly 26..89 of the 108 viewport; zoom so it fills ~74% of the square, centred.
    xs = [p[0] for _, polys in shapes for poly in polys for p in poly]
    ys = [p[1] for _, polys in shapes for poly in polys for p in poly]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    scale = big * 0.74 / max(w, h)
    ox = (big - w * scale) / 2 - min(xs) * scale
    oy = (big - h * scale) / 2 - min(ys) * scale
    draw_vector(img, shapes, scale, ox, oy)
    return img.resize((size, size), Image.LANCZOS)


def feature_graphic(vector, word, tagline, ss=2):
    W, H = 1024 * ss, 500 * ss
    img = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(img)
    s = ss
    rail = 150 * s
    def fillet(x, y, r, color, down=True):
        """Concave corner where a bar meets the rail: colour square minus a black quarter circle."""
        if down:
            d.rectangle([x, y, x + r, y + r], fill=color)
            d.ellipse([x, y, x + 2 * r, y + 2 * r], fill=(0, 0, 0))
        else:
            d.rectangle([x, y - r, x + r, y], fill=color)
            d.ellipse([x, y - 2 * r, x + 2 * r, y], fill=(0, 0, 0))

    # top elbow + bar
    d.rounded_rectangle([24 * s, 24 * s, 24 * s + rail + 60 * s, 150 * s], radius=70 * s, fill=ORANGE)
    d.rectangle([24 * s + rail, 24 * s, W - 120 * s, 52 * s], fill=ORANGE)
    d.rectangle([24 * s + rail, 52 * s, 24 * s + rail + 70 * s, 150 * s], fill=(0, 0, 0))
    fillet(24 * s + rail, 52 * s, 30 * s, ORANGE)
    d.rectangle([W - 112 * s, 24 * s, W - 72 * s, 52 * s], fill=GOLD)
    d.rounded_rectangle([W - 64 * s, 24 * s, W - 24 * s, 52 * s], radius=14 * s, fill=PEACH)
    d.rectangle([W - 64 * s, 24 * s, W - 44 * s, 52 * s], fill=PEACH)
    # sidebar keys
    y = 156 * s
    for color, hgt in ((LAVENDER, 70), (PINK, 44), (BLUE, 90)):
        d.rectangle([24 * s, y, 24 * s + rail, y + hgt * s], fill=color)
        y += (hgt + 6) * s
    # bottom elbow + bar
    d.rounded_rectangle([24 * s, y, 24 * s + rail + 60 * s, H - 24 * s], radius=50 * s, fill=LAVENDER)
    d.rectangle([24 * s, y, 24 * s + rail, y + 40 * s], fill=LAVENDER)
    d.rectangle([24 * s + rail, H - 52 * s, W - 120 * s, H - 24 * s], fill=LAVENDER)
    d.rectangle([24 * s + rail, y, 24 * s + rail + 70 * s, H - 52 * s], fill=(0, 0, 0))
    fillet(24 * s + rail, H - 52 * s, 30 * s, LAVENDER, down=False)
    d.rectangle([W - 112 * s, H - 52 * s, W - 72 * s, H - 24 * s], fill=BLUE)
    d.rounded_rectangle([W - 64 * s, H - 52 * s, W - 24 * s, H - 24 * s], radius=14 * s, fill=PEACH)
    d.rectangle([W - 64 * s, H - 52 * s, W - 44 * s, H - 24 * s], fill=PEACH)

    # icon art, right side
    ic = icon(vector, 300 * s, ss=2).convert("RGB")
    img.paste(ic, (W - 340 * s, (H - 300 * s) // 2))

    # title
    try:
        title_font = ImageFont.truetype(FONT, 118 * s)
        sub_font = ImageFont.truetype(FONT, 38 * s)
    except OSError:
        title_font = sub_font = ImageFont.load_default()
    x0 = 24 * s + rail + 56 * s
    d.text((x0, 148 * s), "TREKUI", font=title_font, fill=ORANGE)
    mid_font = sub_font.font_variant(size=76 * s) if hasattr(sub_font, "font_variant") else sub_font
    d.text((x0, 260 * s), word, font=mid_font, fill=PEACH)
    d.text((x0 + 4 * s, 356 * s), tagline, font=sub_font, fill=LAVENDER)
    return img.resize((1024, 500), Image.LANCZOS)


OWNER_ICON = os.path.join(ROOT, "tools", "art", "store", "icon.png")
OWNER_FEATURE = os.path.join(ROOT, "tools", "art", "store", "featured.png")


def owner_art():
    """The owner's own store art (tools/art/store/icon.png and featured.png), fitted to Play's sizes, or None."""
    if not (os.path.exists(OWNER_ICON) and os.path.exists(OWNER_FEATURE)):
        return None
    ic = Image.open(OWNER_ICON).convert("RGBA").resize((512, 512), Image.LANCZOS)
    f = Image.open(OWNER_FEATURE).convert("RGB")
    f = f.resize((1024, round(f.height * 1024 / f.width)), Image.LANCZOS)
    if f.height < 500:  # wider than 1024:500: fit the height instead, crop the sides
        f = Image.open(OWNER_FEATURE).convert("RGB")
        f = f.resize((round(f.width * 500 / f.height), 500), Image.LANCZOS)
        left = (f.width - 1024) // 2
        return ic, f.crop((left, 0, left + 1024, 500))
    top = (f.height - 500) // 2
    return ic, f.crop((0, top, 1024, top + 500))


def main():
    owned = owner_art()
    if owned:  # the owner's art wins over the generated placeholders, for every listing
        for app in APPS:
            out = out_dir(app)
            os.makedirs(out, exist_ok=True)
            owned[0].save(os.path.join(out, "icon.png"), optimize=True)
            owned[1].save(os.path.join(out, "featureGraphic.png"), optimize=True)
            print("wrote owner art to", out)
        return
    for app, (module, drawable, word, tagline) in APPS.items():
        vector = vector_path(module, drawable)
        out = out_dir(app)
        os.makedirs(out, exist_ok=True)
        icon(vector).save(os.path.join(out, "icon.png"), optimize=True)
        feature_graphic(vector, word, tagline).save(os.path.join(out, "featureGraphic.png"), optimize=True)
        print("wrote", os.path.join(out, "icon.png"), "and featureGraphic.png")


if __name__ == "__main__":
    main()
