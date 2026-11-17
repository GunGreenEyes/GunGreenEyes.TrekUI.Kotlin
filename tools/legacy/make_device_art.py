#!/usr/bin/env python3
"""console art for the rest of the phone, one set per launcher colour theme:

  aod/<theme>.gif               animated Always On Display image (square, mostly black to spare the battery)
  nav/<theme>/nav_recents.png   Good Lock NavStar button images (Samsung order: recents · home · back)
  nav/<theme>/nav_home.png
  nav/<theme>/nav_back.png

Usage: make_device_art.py <assets/appthemes dir> <Antonio.ttf> [preview.png]
"""
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_quickpanel_art import PALETTES, rgb  # noqa: E402

SS = 3


def col(h, f=1.0):
    r, g, b, _ = rgb(h)
    return (int(r * f), int(g * f), int(b * f))


def font(path, size, weight):
    f = ImageFont.truetype(path, size)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


# ---- Always On Display ---------------------------------------------------------------------------

AOD = 600
FRAMES = 16


def elbow(im, y, length, depth, side, bar, ro, color, flip, ri=22):
    """console elbow at the left edge: outer radius ro, concave inner radius ri; flip = bottom elbow."""
    s = SS
    L = Image.new("RGBA", (length * s, depth * s), (0, 0, 0, 0))
    ld = ImageDraw.Draw(L)
    ld.rounded_rectangle((0, 0, (length + ro * 2) * s, (depth + ro * 2) * s), ro * s, fill=color + (255,))
    ld.rounded_rectangle((side * s, bar * s, (length + ri * 4) * s, (depth + ri * 4) * s), ri * s, fill=(0, 0, 0, 0))
    if flip:
        L = L.transpose(Image.FLIP_TOP_BOTTOM)
    im.paste(L, (0, y * s), L)


def aod_frames(pal, font_path):
    name, c, text_hi = pal
    s = SS
    W = AOD
    f_title = font(font_path, 64 * s, 700)
    f_sub = font(font_path, 22 * s, 500)
    f_num = font(font_path, 21 * s, 500)
    rnd = random.Random(47)
    cols, rows = 4, 7
    grid = [[rnd.randint(10, 99999) for _ in range(cols)] for _ in range(rows)]
    out = []
    for fr in range(FRAMES):
        # data cascade: every other frame a new row drops in at the top
        if fr % 2 == 0 and fr:
            grid.insert(0, [rnd.randint(10, 99999) for _ in range(cols)])
            grid.pop()
        im = Image.new("RGB", (W * s, W * s), (0, 0, 0))
        d = ImageDraw.Draw(im)

        def box(x0, y0, x1, y1, color, r=0):
            d.rounded_rectangle((x0 * s, y0 * s, x1 * s, y1 * s), r * s, fill=color)

        # top elbow
        side, bar, ro = 70, 26, 56
        elbow(im, 0, 360, 210, side, bar, ro, col(c[0]), False)
        box(372, 0, 452, bar, col(c[3]), bar // 2)
        box(464, 0, W, bar, col(c[4]), bar // 2)
        # sidebar segments (one lights up per frame like a status scan)
        segs = [(222, 300, c[1]), (312, 360, c[5]), (372, 440, c[3])]
        lit = (fr // 4) % len(segs)
        for i, (y0, y1, h) in enumerate(segs):
            box(0, y0, side, y1, col(h, 1.0 if i == lit else 0.55))
        # bottom elbow
        elbow(im, W - 148, 330, 148, side, bar, ro, col(c[1]), True)
        box(342, W - bar, 430, W, col(c[0]), bar // 2)
        box(442, W - bar, W, W, col(c[2]), bar // 2)

        # title
        d.text(((W - 16) * s, (bar + 10) * s), "TREKUI", font=f_title, fill=col(c[0]), anchor="ra")
        d.text(((W - 18) * s, (bar + 86) * s), "STANDBY", font=f_sub, fill=col(c[1]), anchor="ra")

        # data cascade
        x0, y0 = 196, 186
        for r_i, row in enumerate(grid):
            fresh = r_i == 0 and fr % 2 == 0
            for c_i, v in enumerate(row):
                color = col(c[2] if c_i % 2 else c[1], 1.0 if fresh else 0.45 + 0.07 * (rows - r_i) / rows)
                if c_i == (fr + r_i) % cols and r_i == (fr * 3) % rows:
                    color = col(c[0])
                d.text(((x0 + c_i * 96) * s, (y0 + r_i * 31) * s), f"{v}", font=f_num, fill=color, anchor="la")

        # progress strip: a highlight sweeps across 10 blocks
        bx, by, bw = 196, 430, 380
        n = 10
        step = bw / n
        hi = fr % FRAMES * n // FRAMES
        for k in range(n):
            color = col(c[0]) if k == hi else col(c[0], 0.3) if k < hi else col(c[2], 0.25)
            box(bx + k * step, by, bx + k * step + step - 8, by + 18, color, 9)
        d.text(((W - 18) * s, (by + 38) * s), f"SYS 47-{2401 + fr:04d}", font=f_sub, fill=col(text_hi, 0.6), anchor="ra")
        out.append(im.resize((W, W), Image.LANCZOS))
    return out


def save_gif(frames, path):
    pal_frames = [f.convert("P", palette=Image.ADAPTIVE, colors=64) for f in frames]
    pal_frames[0].save(path, save_all=True, append_images=pal_frames[1:], duration=160, loop=0, optimize=True, disposal=1)


# ---- NavStar buttons -----------------------------------------------------------------------------

NB = 256


def nav_button(kind, color):
    s = SS
    im = Image.new("RGBA", (NB * s, NB * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    x0, x1, y0, y1 = 10, NB - 10, 76, NB - 76
    h = y1 - y0
    r = h // 2
    d.rounded_rectangle((x0 * s, y0 * s, x1 * s, y1 * s), r * s, fill=color)
    # console end caps: the outer buttons are rounded on the outside only
    if kind == "recents":
        d.rectangle(((x1 - r) * s, y0 * s, x1 * s, y1 * s), fill=color)
    elif kind == "back":
        d.rectangle((x0 * s, y0 * s, (x0 + r) * s, y1 * s), fill=color)
    k = (0, 0, 0, 255)
    cx, cy = NB // 2, NB // 2
    if kind == "recents":
        for dx in (-26, 0, 26):
            d.rounded_rectangle(((cx + dx - 6) * s, (cy - 22) * s, (cx + dx + 6) * s, (cy + 22) * s), 6 * s, fill=k)
    elif kind == "home":
        d.rounded_rectangle(((cx - 24) * s, (cy - 24) * s, (cx + 24) * s, (cy + 24) * s), 14 * s, outline=k, width=10 * s)
    else:
        d.line(((cx + 12) * s, (cy - 24) * s, (cx - 14) * s, cy * s, (cx + 12) * s, (cy + 24) * s), fill=k, width=11 * s, joint="curve")
        for px, py in ((cx + 12, cy - 24), (cx + 12, cy + 24), (cx - 14, cy)):
            d.ellipse(((px - 5.5) * s, (py - 5.5) * s, (px + 5.5) * s, (py + 5.5) * s), fill=k)
    return im.resize((NB, NB), Image.LANCZOS)


NAV = (("recents", 1), ("home", 0), ("back", 3))


def main():
    out = Path(sys.argv[1])
    previews = []
    for i, pal in enumerate(PALETTES):
        (out / "aod").mkdir(parents=True, exist_ok=True)
        frames = aod_frames(pal, sys.argv[2])
        save_gif(frames, out / "aod" / f"{i}.gif")
        nd = out / "nav" / str(i)
        nd.mkdir(parents=True, exist_ok=True)
        btns = []
        for kind, ci in NAV:
            b = nav_button(kind, rgb(pal[1][ci]))
            b.save(nd / f"nav_{kind}.png", optimize=True)
            btns.append(b)
        previews.append((frames[5], btns))
    if len(sys.argv) > 3:
        sheet = Image.new("RGB", (len(previews) * 320 + 20, 470), (24, 24, 28))
        for i, (f, btns) in enumerate(previews):
            x = 20 + i * 320
            sheet.paste(f.resize((300, 300)), (x, 20))
            bar = Image.new("RGB", (300, 90), (0, 0, 0))
            for k, b in enumerate(btns):
                t = b.resize((96, 96))
                bar.paste(t, (4 + k * 98, -3), t)
            sheet.paste(bar, (x, 340))
        sheet.save(sys.argv[3])
    print(f"wrote AOD + NavStar art for {len(PALETTES)} themes to {out}")


if __name__ == "__main__":
    main()
