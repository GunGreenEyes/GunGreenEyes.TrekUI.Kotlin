#!/usr/bin/env python3
"""console art for Samsung Good Lock › QuickStar › "Edit panel style" (One UI 9).

Renders one set per launcher colour theme (same palettes as Palette.java):
  qp_button_1..8.png   square quick-settings buttons (black centre keeps the system icon legible)
  qp_button_wide.png   2x1 button
  qp_button_box.png    console frame for the button box
  qp_brightness.png    brightness slider track
  qp_volume.png        volume slider track
  qp_media_player.png  console frame for the media player
Usage: make_quickpanel_art.py <assets/qp dir> <Antonio.ttf> [preview.png]
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

PALETTES = [  # name, accents c0..c5, textHi  (mirror of Palette.java)
    ("CLASSIC", ["#EA9C72", "#8899FF", "#D29B7F", "#CF4F4F", "#EDB378", "#BAA4E5"], "#FFFFFF"),
    ("MIDNIGHT BLUE", ["#5588FF", "#99CCFF", "#3D6BCC", "#CCDDFF", "#FFAA33", "#7788CC"], "#E2EEFF"),
    ("RED ALERT", ["#E03030", "#FF7766", "#B04040", "#FFAA99", "#FF5A1F", "#CC5566"], "#FFE0D8"),
    ("ENGINEERING AMBER", ["#FF9900", "#FFC266", "#CC7A00", "#FFE0A0", "#E8A33D", "#B36B00"], "#FFEBCC"),
    ("NEBULA VIOLET", ["#AA77EE", "#CCB0FF", "#6688EE", "#EE99CC", "#FFBB66", "#8866CC"], "#EEE4FF"),
    ("ASTROMETRICS TEAL", ["#33BBCC", "#88DDEE", "#3388BB", "#BBEEFF", "#FFAA44", "#5599AA"], "#E0FAFF"),
]
SS = 2  # supersampling
BLACK = (0, 0, 0, 255)


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def dim(h, f):
    r, g, b, _ = rgb(h)
    return (int(r * f), int(g * f), int(b * f), 255)


def canvas(w, h):
    im = Image.new("RGBA", (w * SS, h * SS), BLACK)
    return im, ImageDraw.Draw(im)


def done(im, w, h):
    return im.resize((w, h), Image.LANCZOS)


def elbow(d, x, y, w_side, bar_h, r_out, r_in, length, depth, color, flip=False, H=None):
    """Top-left console elbow (or bottom-left when flip=True, mirrored inside height H)."""
    s = SS
    pts_fill = []
    # draw with primitives: outer rounded corner + bar + sidebar + inner concave fillet
    def Y(v):
        return (H - v) if flip else v
    x0, x1 = x * s, (x + length) * s
    # sidebar column
    top, bot = sorted((Y(y) * s, Y(y + depth) * s))
    d.rectangle((x0, top, x0 + w_side * s, bot), fill=color)
    # horizontal bar
    btop, bbot = sorted((Y(y) * s, Y(y + bar_h) * s))
    d.rectangle((x0, btop, x1, bbot), fill=color)
    # outer rounded corner: cut square then draw quarter circle
    ro = r_out * s
    cy0 = Y(y) * s
    if not flip:
        d.rectangle((x0, cy0, x0 + ro, cy0 + ro), fill=BLACK)
        d.pieslice((x0, cy0, x0 + 2 * ro, cy0 + 2 * ro), 180, 270, fill=color)
    else:
        d.rectangle((x0, cy0 - ro, x0 + ro, cy0), fill=BLACK)
        d.pieslice((x0, cy0 - 2 * ro, x0 + 2 * ro, cy0), 90, 180, fill=color)
    # inner concave fillet
    ri = r_in * s
    ix, iy = (x + w_side) * s, Y(y + bar_h) * s
    if not flip:
        d.rectangle((ix, iy, ix + ri, iy + ri), fill=color)
        d.pieslice((ix, iy, ix + 2 * ri, iy + 2 * ri), 180, 270, fill=BLACK)
    else:
        d.rectangle((ix, iy - ri, ix + ri, iy), fill=color)
        d.pieslice((ix, iy - 2 * ri, ix + 2 * ri, iy), 90, 180, fill=BLACK)


def text(d, xy, s, size, color, font_path, anchor="rs"):
    f = ImageFont.truetype(font_path, size * SS)
    try:
        f.set_variation_by_axes([700])
    except Exception:
        pass
    d.text((xy[0] * SS, xy[1] * SS), s, font=f, fill=color, anchor=anchor)


def button(c, i, font):
    W = H = 300
    im, d = canvas(W, H)
    col = rgb(c[i % 6])
    s = SS
    d.rounded_rectangle((6 * s, 6 * s, (W - 6) * s, (H - 6) * s), 70 * s, fill=BLACK, outline=col, width=6 * s)
    d.rounded_rectangle((26 * s, 40 * s, 60 * s, (H - 40) * s), 17 * s, fill=col)          # left console bar
    d.rounded_rectangle((200 * s, (H - 46) * s, (W - 40) * s, (H - 28) * s), 9 * s, fill=rgb(c[(i + 1) % 6]))
    text(d, (W - 44, 58), "%02d" % (i + 1), 30, col, font)
    return done(im, W, H)


def button_wide(c, font):
    W, H = 600, 300
    im, d = canvas(W, H)
    s = SS
    d.rounded_rectangle((6 * s, 6 * s, (W - 6) * s, (H - 6) * s), 70 * s, fill=BLACK, outline=rgb(c[1]), width=6 * s)
    d.rounded_rectangle((26 * s, 40 * s, 60 * s, (H - 40) * s), 17 * s, fill=rgb(c[0]))
    for k, x in enumerate((360, 440, 500)):
        d.rectangle((x * s, (H - 46) * s, (x + (70 if k == 0 else 50)) * s, (H - 28) * s), fill=rgb(c[(k + 2) % 6]))
    text(d, (W - 44, 58), "47-A", 30, rgb(c[0]), font)
    return done(im, W, H)


def button_box(c, font):
    W, H = 1200, 900
    im, d = canvas(W, H)
    elbow(d, 0, 0, 90, 28, 70, 30, W - 330, 160, rgb(c[1]))
    elbow(d, 0, 0, 90, 28, 70, 30, W - 360, 160, rgb(c[3]), flip=True, H=H)
    s = SS
    d.rectangle(((W - 315) * s, 0, (W - 260) * s, 28 * s), fill=rgb(c[0]))
    d.rectangle(((W - 345) * s, (H - 28) * s, (W - 290) * s, H * s), fill=rgb(c[4]))
    y = 205
    for k, hh in enumerate((150, 110, 190)):
        d.rectangle((0, y * s, 90 * s, (y + hh) * s), fill=rgb(c[(k * 2) % 6]))
        text(d, (84, y + hh - 10), "%02d-%03d" % (k * 17 + 3, k * 211 + 47), 22, BLACK, font)
        y += hh + 12
    text(d, (W - 16, 50), "OPERATIONS", 44, rgb(c[0]), font, anchor="rs")
    return done(im, W, H)


def slider(c, idx, label, font):
    W, H = 1200, 200
    im, d = canvas(W, H)
    s = SS
    col = rgb(c[idx])
    d.rounded_rectangle((4 * s, 4 * s, (W - 4) * s, (H - 4) * s), 96 * s, fill=BLACK, outline=col, width=6 * s)
    # segmented console readout along the bottom edge
    n, x0, x1 = 24, 150, W - 150
    seg = (x1 - x0) / n
    for k in range(n):
        a = x0 + k * seg
        d.rectangle((int(a * s), (H - 44) * s, int((a + seg - 8) * s), (H - 30) * s),
                    fill=col if k < n * 0.62 else dim(c[(idx + 3) % 6], 0.35))
    d.rounded_rectangle((26 * s, 40 * s, 70 * s, (H - 40) * s), 22 * s, fill=col)
    d.rounded_rectangle(((W - 70) * s, 40 * s, (W - 26) * s, (H - 40) * s), 22 * s, fill=rgb(c[(idx + 1) % 6]))
    text(d, (W - 96, 60), label, 30, col, font)
    return done(im, W, H)


def media(c, font):
    W, H = 1200, 520
    im, d = canvas(W, H)
    elbow(d, 0, 0, 80, 24, 60, 26, W - 330, 120, rgb(c[0]))
    elbow(d, 0, 0, 80, 24, 60, 26, W - 120, 120, rgb(c[2]), flip=True, H=H)
    s = SS
    d.rectangle((0, 160 * s, 80 * s, 360 * s), fill=rgb(c[1]))
    text(d, (74, 350), "AUD", 22, BLACK, font)
    d.rectangle(((W - 315) * s, 0, (W - 270) * s, 24 * s), fill=rgb(c[4]))
    text(d, (W - 16, 46), "AUDIO SYSTEMS", 40, rgb(c[0]), font)
    return done(im, W, H)


def main():
    out = Path(sys.argv[1])
    font = sys.argv[2]
    prev = sys.argv[3] if len(sys.argv) > 3 else None
    sheets = []
    for t, (name, acc, hi) in enumerate(PALETTES):
        d = out / str(t)
        d.mkdir(parents=True, exist_ok=True)
        imgs = {}
        for i in range(8):
            imgs["qp_button_%d.png" % (i + 1)] = button(acc, i, font)
        imgs["qp_button_wide.png"] = button_wide(acc, font)
        imgs["qp_button_box.png"] = button_box(acc, font)
        imgs["qp_brightness.png"] = slider(acc, 0, "ILLUMINATION", font)
        imgs["qp_volume.png"] = slider(acc, 2, "AUDIO LEVEL", font)
        imgs["qp_media_player.png"] = media(acc, font)
        for fn, im in imgs.items():
            im.convert("RGB").save(d / fn, optimize=True)
        if prev and t in (0, 2):
            sheets.append((name, imgs))
    if prev:
        sheet = Image.new("RGB", (1240, 840 * len(sheets)), (40, 40, 40))
        for k, (name, imgs) in enumerate(sheets):
            oy = k * 840
            sheet.paste(imgs["qp_button_box.png"].resize((600, 450)), (10, oy + 10))
            sheet.paste(imgs["qp_media_player.png"].resize((600, 260)), (630, oy + 10))
            sheet.paste(imgs["qp_brightness.png"].resize((600, 100)), (630, oy + 290))
            sheet.paste(imgs["qp_volume.png"].resize((600, 100)), (630, oy + 400))
            for i in range(8):
                sheet.paste(imgs["qp_button_%d.png" % (i + 1)].resize((140, 140)), (10 + i * 150, oy + 520))
            sheet.paste(imgs["qp_button_wide.png"].resize((280, 140)), (10, oy + 680))
        sheet.save(prev)
    print("ok")


if __name__ == "__main__":
    main()
