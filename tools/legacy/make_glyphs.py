#!/usr/bin/env python3
"""Generate the console pictogram set used by the launcher's "CONSOLE" icon style.

Each glyph is drawn white-on-transparent; the launcher tints it with the active
colour theme at runtime and draws the console frame around it, so one set of PNGs
serves every theme. Usage: make_glyphs.py <output_dir>
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

S = 768          # supersampled canvas (4x of the 192px output)
OUT = 192
W = 56           # standard stroke width at 4x
FG = (255, 255, 255, 255)
CLR = (0, 0, 0, 0)


def canvas():
    im = Image.new("RGBA", (S, S), CLR)
    return im, ImageDraw.Draw(im)


def rr(d, box, r, fill=FG, outline=None, width=0):
    d.rounded_rectangle(box, r, fill=fill, outline=outline, width=width)


def ring(d, cx, cy, r, w=W, fill=FG):
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=fill, width=w)


def disc(d, cx, cy, r, fill=FG):
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill)


def line(d, pts, w=W, fill=FG):
    d.line(pts, fill=fill, width=w, joint="curve")
    for p in (pts[0], pts[-1]):
        disc(d, p[0], p[1], w / 2, fill)


def g_apps(d):
    for i in range(3):
        for j in range(3):
            x, y = 150 + i * 168, 150 + j * 168
            rr(d, (x, y, x + 132, y + 132), 30)


def g_phone(d):
    # classic handset: thick arc with ear/mouth pads
    d.arc((130, 150, 638, 640), 200, 340, fill=FG, width=110)
    rr(d, (120, 300, 290, 420), 40)
    rr(d, (478, 300, 648, 420), 40)
    # base
    d.polygon([(250, 420), (518, 420), (600, 620), (168, 620)], fill=FG)
    rr(d, (168, 560, 600, 640), 30)
    disc(d, 384, 520, 62, CLR)


def g_messages(d):
    rr(d, (120, 150, 648, 530), 90)
    d.polygon([(210, 500), (330, 500), (180, 650)], fill=FG)
    for k, y in enumerate((260, 340, 420)):
        x2 = (540, 540, 430)[k]
        line(d, [(230, y), (x2, y)], 40, CLR)


def g_browser(d):
    ring(d, 384, 384, 250)
    d.ellipse((384 - 110, 134, 384 + 110, 634), outline=FG, width=44)
    line(d, [(150, 384), (618, 384)], 40)
    d.arc((180, 60, 588, 300), 25, 155, fill=FG, width=40)
    d.arc((180, 468, 588, 708), 205, 335, fill=FG, width=40)


def g_camera(d):
    rr(d, (110, 240, 658, 610), 70)
    rr(d, (270, 160, 498, 280), 40)
    disc(d, 384, 425, 140, CLR)
    disc(d, 384, 425, 88)
    disc(d, 570, 310, 26, CLR)


def g_settings(d):
    cx = cy = 384
    for k in range(8):
        a = k * math.pi / 4
        pts = []
        for dx, dy in ((-55, 150), (55, 150), (45, 270), (-45, 270)):
            x = cx + dx * math.cos(a) - dy * math.sin(a)
            y = cy + dx * math.sin(a) + dy * math.cos(a)
            pts.append((x, y))
        d.polygon(pts, fill=FG)
    disc(d, cx, cy, 200)
    disc(d, cx, cy, 85, CLR)


def g_calculator(d):
    rr(d, (170, 100, 598, 668), 60)
    rr(d, (230, 160, 538, 280), 20, fill=CLR)
    for i in range(3):
        for j in range(3):
            x, y = 236 + i * 106, 330 + j * 106
            rr(d, (x, y, x + 84, y + 84), 18, fill=CLR)


def g_calendar(d):
    rr(d, (120, 160, 648, 650), 60)
    rr(d, (175, 300, 593, 595), 26, fill=CLR)
    for x in (250, 518):
        rr(d, (x - 30, 100, x + 30, 230), 30)
    for i in range(3):
        for j in range(2):
            x, y = 225 + i * 120, 345 + j * 120
            rr(d, (x, y, x + 80, y + 80), 16)


def g_clock(d):
    ring(d, 384, 384, 260)
    line(d, [(384, 384), (384, 210)], 52)
    line(d, [(384, 384), (520, 450)], 52)
    disc(d, 384, 384, 40)


def g_contacts(d):
    disc(d, 384, 270, 130)
    d.pieslice((160, 430, 608, 880), 180, 360, fill=FG)
    rr(d, (160, 640, 608, 668), 10)


def g_files(d):
    d.polygon([(110, 200), (300, 200), (350, 260), (110, 260)], fill=FG)
    rr(d, (110, 230, 658, 620), 50)
    line(d, [(160, 300), (608, 300)], 30, CLR)


def g_fitness(d):
    disc(d, 270, 300, 150)
    disc(d, 498, 300, 150)
    d.polygon([(130, 360), (638, 360), (384, 660)], fill=FG)
    line(d, [(150, 400), (300, 400), (350, 320), (420, 480), (470, 400), (618, 400)], 36, CLR)


def g_home(d):
    d.polygon([(384, 110), (680, 380), (88, 380)], fill=FG)
    rr(d, (170, 330, 598, 660), 30)
    rr(d, (330, 470, 438, 660), 20, fill=CLR)
    rr(d, (520, 150, 590, 300), 10)


def g_mail(d):
    rr(d, (100, 200, 668, 580), 60)
    line(d, [(160, 260), (384, 440), (608, 260)], 46, CLR)


def g_maps(d):
    disc(d, 384, 300, 200)
    d.polygon([(205, 380), (563, 380), (384, 680)], fill=FG)
    disc(d, 384, 300, 80, CLR)


def g_music(d):
    d.ellipse((140, 500, 320, 640), fill=FG)
    d.ellipse((440, 440, 620, 580), fill=FG)
    rr(d, (270, 170, 330, 580), 10)
    rr(d, (570, 110, 630, 520), 10)
    d.polygon([(270, 170), (630, 110), (630, 220), (270, 280)], fill=FG)


def g_notes(d):
    rr(d, (170, 100, 598, 668), 50)
    for y in (230, 330, 430, 530):
        line(d, [(250, y), (518 if y != 530 else 420, y)], 40, CLR)


def g_photos(d):
    rr(d, (110, 150, 658, 618), 60, outline=FG, width=W, fill=None)
    d.polygon([(150, 580), (330, 330), (460, 500), (530, 420), (620, 580)], fill=FG)
    disc(d, 520, 280, 55)


def g_radio(d):
    disc(d, 384, 384, 60)
    for r in (150, 250):
        d.arc((384 - r, 384 - r, 384 + r, 384 + r), 135, 225, fill=FG, width=48)
        d.arc((384 - r, 384 - r, 384 + r, 384 + r), -45, 45, fill=FG, width=48)
    rr(d, (360, 420, 408, 690), 20)


def g_search(d):
    ring(d, 330, 330, 170, 64)
    line(d, [(460, 460), (620, 620)], 90)


def g_store(d):
    d.arc((250, 100, 518, 400), 180, 360, fill=FG, width=48)
    d.polygon([(140, 260), (628, 260), (590, 660), (178, 660)], fill=FG)
    for x in (270, 498):
        disc(d, x, 340, 26, CLR)


def g_system(d):
    for k, (y, ln) in enumerate(((210, 480), (384, 300), (558, 420))):
        rr(d, (140, y - 55, 628, y + 55), 55)
        disc(d, 140 + ln, y, 38, CLR)


def g_video(d):
    rr(d, (100, 170, 668, 598), 80)
    d.polygon([(320, 270), (320, 498), (500, 384)], fill=CLR)


def g_weather(d):
    cx, cy = 470, 290
    for k in range(8):
        a = k * math.pi / 4
        line(d, [(cx + 150 * math.cos(a), cy + 150 * math.sin(a)),
                 (cx + 210 * math.cos(a), cy + 210 * math.sin(a))], 40)
    disc(d, cx, cy, 110)
    # cloud with a clear gap separating it from the sun
    for grow in (34, 0):
        f = CLR if grow else FG
        disc(d, 260, 470, 120 + grow, f)
        disc(d, 400, 420, 150 + grow, f)
        rr(d, (140 - grow, 460 - grow, 600 + grow, 610 + grow), 75 + grow, fill=f)


def g_alert(d):
    d.polygon([(384, 110), (680, 640), (88, 640)], fill=FG)
    rr(d, (354, 290, 414, 500), 28, fill=CLR)
    disc(d, 384, 565, 36, CLR)


def g_secure(d):
    # secure comms: speech bubble with a padlock cut into it
    rr(d, (110, 140, 658, 520), 100)
    d.polygon([(200, 490), (330, 490), (170, 660)], fill=FG)
    d.arc((320, 200, 448, 330), 180, 360, fill=CLR, width=34)
    rr(d, (290, 275, 478, 430), 26, fill=CLR)
    disc(d, 384, 340, 22)
    rr(d, (374, 345, 394, 395), 8)


def g_officemail(d):
    # envelope with a schedule grid behind it (mail + calendar)
    rr(d, (300, 90, 660, 430), 40)
    for i in range(3):
        for j in range(2):
            x, y = 350 + i * 95, 210 + j * 90
            rr(d, (x, y, x + 60, y + 55), 12, fill=CLR)
    rr(d, (90, 300, 560, 650), 50)
    rr(d, (90, 300, 560, 650), 50, outline=CLR, width=34, fill=None)
    rr(d, (124, 334, 526, 616), 30)
    line(d, [(140, 350), (325, 490), (510, 350)], 40, CLR)


def g_computer(d):
    # ship's computer: voice waveform inside a sensor ring
    ring(d, 384, 384, 280, 48)
    for k, h in enumerate((70, 150, 250, 330, 250, 150, 70)):
        x = 384 + (k - 3) * 66
        rr(d, (x - 22, 384 - h // 2, x + 22, 384 + h // 2), 22)


GLYPHS = {k[2:]: v for k, v in globals().items() if k.startswith("g_")}


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for name, fn in GLYPHS.items():
        im, d = canvas()
        fn(d)
        im.resize((OUT, OUT), Image.LANCZOS).save(out / f"{name}.png", optimize=True)
    print(f"wrote {len(GLYPHS)} glyphs to {out}")


if __name__ == "__main__":
    main()
