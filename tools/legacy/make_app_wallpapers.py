#!/usr/bin/env python3
"""console backgrounds for Signal chats and Chrome's New Tab page (one per launcher colour theme).

Signal draws message bubbles over the wallpaper, so everything here is kept to the
edges and dimmed: a thin console frame down the left edge, a slim segmented rail on the
right, a header bar under Signal's toolbar and a faint sensor grid in the middle.
Output: <out_dir>/{signal,chrome}/<theme index>.png at 1440x3120 (Galaxy S25 Ultra).
Usage: make_app_wallpapers.py <assets/appthemes dir> <Antonio.ttf> [preview.png]
"""
import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_quickpanel_art import PALETTES, rgb  # noqa: E402  (same palettes as core/src/main/kotlin/com/gungreeneyes/trekui/Palette.kt)

W, H = 1440, 3120
SS = 2
DIM = 0.50            # frame brightness (bubbles must stay the brightest thing on screen)
SIDE = 46             # left sidebar width
BAR = 30              # header/footer bar height
TOP, BOT = 420, 2760  # frame spans the area between Signal's toolbar and composer


def col(h, f=DIM, a=255):
    r, g, b, _ = rgb(h)
    return (int(r * f), int(g * f), int(b * f), a)


def elbow(frame, y, color, flip, length, depth=210):
    """Left console elbow: sidebar + bar, outer radius and concave inner fillet; flip = bottom elbow ending at y."""
    s = SS
    ro, ri = 70, 34
    L = Image.new("RGBA", (length * s, depth * s), (0, 0, 0, 0))
    ld = ImageDraw.Draw(L)
    ld.rounded_rectangle((0, 0, (length + ro * 2) * s, (depth + ro * 2) * s), ro * s, fill=color)
    ld.rounded_rectangle((SIDE * s, BAR * s, (length + ri * 4) * s, (depth + ri * 4) * s), ri * s, fill=(0, 0, 0, 0))
    if flip:
        L = L.transpose(Image.FLIP_TOP_BOTTOM)
        frame.alpha_composite(L, (0, (y - depth) * s))
    else:
        frame.alpha_composite(L, (0, y * s))


def pill(d, x0, y0, x1, y1, color):
    s = SS
    r = (y1 - y0) / 2
    d.rounded_rectangle((x0 * s, y0 * s, x1 * s, y1 * s), r * s, fill=color)


def render(pal, font_path, texts):
    name, c, text_hi = pal
    im = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 255))
    frame = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)
    s = SS

    # faint starfield
    rnd = random.Random(1701)
    sd = ImageDraw.Draw(im)
    for _ in range(420):
        x, y = rnd.uniform(0, W), rnd.uniform(0, H)
        r = rnd.choice((1.0, 1.2, 1.5, 2.0))
        v = rnd.randint(40, 95)
        sd.ellipse(((x - r) * s, (y - r) * s, (x + r) * s, (y + r) * s), fill=(v, v, int(v * 1.1), 255))

    # faint sensor grid in the middle (rings + ticks), well below bubble contrast
    g = Image.new("RGBA", im.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(g)
    cx, cy = W * 0.54, H * 0.50
    gc = col(c[2], 0.22)
    for r in (230, 420, 610):
        gd.ellipse(((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s), outline=gc, width=3 * s)
    for k in range(72):
        a = k * math.pi / 36
        r0, r1 = (610, 650) if k % 6 == 0 else (610, 628)
        gd.line(((cx + r0 * math.cos(a)) * s, (cy + r0 * math.sin(a)) * s,
                 (cx + r1 * math.cos(a)) * s, (cy + r1 * math.sin(a)) * s), fill=gc, width=3 * s)
    gd.line(((cx - 680) * s, cy * s, (cx + 680) * s, cy * s), fill=col(c[2], 0.14), width=2 * s)
    gd.line((cx * s, (cy - 680) * s, cx * s, (cy + 680) * s), fill=col(c[2], 0.14), width=2 * s)
    im.alpha_composite(g)

    # left frame: top elbow, segmented sidebar, bottom elbow
    elbow(frame, TOP, col(c[0]), False, 900)
    elbow(frame, BOT, col(c[1]), True, 760)
    pill(d, 920, TOP, 1060, TOP + BAR, col(c[3]))
    pill(d, 1080, TOP, 1380, TOP + BAR, col(c[1]))
    pill(d, 780, BOT - BAR, 1000, BOT, col(c[4]))
    pill(d, 1020, BOT - BAR, 1380, BOT, col(c[0]))
    y = TOP + 210 + 14
    seg = [(c[5], 260), (c[1], 140), (c[3], 520), (c[2], 180), (c[4], 360), (c[0], 120)]
    end = BOT - 210 - 14
    i = 0
    while y < end:
        h, ln = seg[i % len(seg)]
        y1 = min(end, y + ln)
        d.rectangle((0, y * s, SIDE * s, y1 * s), fill=col(h))
        y = y1 + 14
        i += 1

    # right rail: thin segments at the far edge
    y = TOP + 80
    i = 0
    while y < BOT - 80:
        h, ln = seg[(i + 3) % len(seg)]
        ln = int(ln * 0.8)
        y1 = min(BOT - 80, y + ln)
        d.rounded_rectangle(((W - 14) * s, y * s, W * s, y1 * s), 7 * s, fill=col(h, DIM * 0.8))
        y = y1 + 22
        i += 1

    # labels in the black gaps of the bars
    f_title = ImageFont.truetype(font_path, 40 * s)
    f_code = ImageFont.truetype(font_path, 30 * s)
    try:
        f_title.set_variation_by_axes([700])
        f_code.set_variation_by_axes([500])
    except Exception:
        pass
    th = col(text_hi, 0.55)
    d.text(((SIDE + 92) * s, (TOP + BAR + 16) * s), texts[0], font=f_title, fill=th)
    d.text(((SIDE + 92) * s, (TOP + BAR + 66) * s), texts[1] + "  ·  " + name, font=f_code, fill=col(c[1], 0.45))
    d.text(((SIDE + 92) * s, (BOT - BAR - 60) * s), texts[2], font=f_code, fill=col(c[0], 0.45))

    im.alpha_composite(frame)
    return im.resize((W, H), Image.LANCZOS).convert("RGB")


def keyboard(pal):
    """Keyboard background (Theme Park / Gboard): black panel with a thin console frame in the key margins."""
    c = [rgb(h) for h in pal[1]]
    kw, kh, t, side, r = 1440, 1000, 22, 22, 60
    im = Image.new("RGBA", (kw * SS, kh * SS), (0, 0, 0, 255))
    d = ImageDraw.Draw(im)

    def box(x0, y0, x1, y1, color, rad=0):
        d.rounded_rectangle((x0 * SS, y0 * SS, x1 * SS, y1 * SS), rad * SS, fill=color)
    box(0, 0, r * 2, r * 2, c[0], r); box(r, 0, 760, t, c[0]); box(0, r, side, 330, c[0])
    d.rounded_rectangle((side * SS, t * SS, (side + 120) * SS, (t + 120) * SS), 26 * SS, fill=(0, 0, 0, 255))
    box(776, 0, 930, t, c[3], t // 2); box(946, 0, 1250, t, c[1], t // 2); box(1266, 0, kw, t, c[4], t // 2)
    box(0, 344, side, 520, c[1]); box(0, 534, side, 640, c[5])
    box(0, kh - r * 2, r * 2, kh, c[2], r); box(r, kh - t, 700, kh, c[2]); box(0, 654, side, kh - r, c[2])
    d.rounded_rectangle((side * SS, (kh - t - 120) * SS, (side + 120) * SS, (kh - t) * SS), 26 * SS, fill=(0, 0, 0, 255))
    box(716, kh - t, 1010, kh, c[0], t // 2); box(1026, kh - t, kw, kh, c[1], t // 2)
    box(kw - 12, 60, kw, 300, c[3], 6); box(kw - 12, 316, kw, 600, c[4], 6); box(kw - 12, 616, kw, 940, c[1], 6)
    return im.resize((kw, kh), Image.LANCZOS).convert("RGB")


def preview(images, names, out):
    """Side-by-side thumbnails with mock Signal bubbles to check readability."""
    tw, th = 300, 650
    sheet = Image.new("RGB", (tw * len(images) + 20 * (len(images) + 1), th + 70), (24, 24, 28))
    d = ImageDraw.Draw(sheet)
    for i, (im, pal) in enumerate(zip(images, names)):
        t = im.resize((tw, th), Image.LANCZOS)
        td = ImageDraw.Draw(t)
        out_c = rgb(pal[1][0])[:3]
        for k, (y, mine, w) in enumerate(((180, False, 180), (240, True, 150), (300, True, 210), (370, False, 230), (440, True, 120))):
            x0 = tw - 12 - w if mine else 14
            td.rounded_rectangle((x0, y, x0 + w, y + 46), 14, fill=out_c if mine else (48, 48, 52))
            td.line((x0 + 12, y + 18, x0 + w - 24, y + 18), fill=(0, 0, 0) if mine else (225, 225, 225), width=3)
            td.line((x0 + 12, y + 30, x0 + w - 60, y + 30), fill=(0, 0, 0) if mine else (225, 225, 225), width=3)
        td.rectangle((0, 0, tw, 82), fill=(18, 18, 18))
        td.rectangle((0, th - 70, tw, th), fill=(18, 18, 18))
        x = 20 + i * (tw + 20)
        sheet.paste(t, (x, 20))
        d.text((x, th + 34), pal[0], fill=(220, 220, 220))
    sheet.save(out)


KINDS = {  # asset folder: header title, sub-line prefix, footer code
    "signal": ("SUBSPACE COMMUNICATIONS", "CHANNEL SECURE", "47-2401  ·  ENCRYPTION ACTIVE"),
    "chrome": ("CENTRAL COMPUTER ACCESS", "NETWORK INTERFACE ONLINE", "02-4791  ·  SUBSPACE NETWORK LINK"),
}


def main():
    out = Path(sys.argv[1])
    imgs = []
    for kind, texts in KINDS.items():
        (out / kind).mkdir(parents=True, exist_ok=True)
        for i, pal in enumerate(PALETTES):
            im = render(pal, sys.argv[2], texts)
            im.save(out / kind / f"{i}.png", optimize=True)
            if kind == "signal":
                imgs.append(im)
    (out / "keyboard").mkdir(parents=True, exist_ok=True)
    for i, pal in enumerate(PALETTES):
        keyboard(pal).save(out / "keyboard" / f"{i}.png", optimize=True)
    if len(sys.argv) > 3:
        preview(imgs, PALETTES, sys.argv[3])
    print(f"wrote {len(KINDS) * len(PALETTES)} app wallpapers to {out}")


if __name__ == "__main__":
    main()
