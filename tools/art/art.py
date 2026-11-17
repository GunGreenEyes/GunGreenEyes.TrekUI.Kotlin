"""Paper/metal grain textures and FACTION ART badges for the three pipe interfaces."""
import os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops
sys.path.insert(0, os.path.dirname(__file__))
from textures import noise, save

OUT = sys.argv[1]; GL = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

# light grayscale grains, tinted in code with a MULTIPLY colour filter
h = w = 320
fib = noise(h, w, 2.0, 24, 2); fib2 = noise(h, w, 24, 2.0, 2); base = noise(h, w, 160, 160, 4)
g = 0.90 + 0.06 * (base - 0.5) + 0.07 * (fib - 0.5) + 0.05 * (fib2 - 0.5)
fleck = noise(h, w, 1.5, 1.5, 1) > 0.94
g = np.where(fleck, g * 0.82, g)
save(np.repeat((np.clip(g, 0, 1) * 255)[..., None], 3, 2), f"{OUT}/paper_grain.jpg", 92)
streak = noise(h, w, 1.2, 160, 2); broad = noise(h, w, 120, 300, 3)
g = 0.86 + 0.10 * (streak - 0.5) + 0.10 * (broad - 0.5)
save(np.repeat((np.clip(g, 0, 1) * 255)[..., None], 3, 2), f"{OUT}/brushed.jpg", 92)

NAMES = ["apps","browser","calculator","calendar","camera","clock","contacts","files","fitness","home","mail","maps",
         "messages","music","notes","phone","photos","radio","search","settings","store","system","video","weather"]
S = 192; SS = 3; Z = S * SS

def glyph(style, n, size, color):
    g = Image.open(f"{GL}/{style}/{n}.png").getchannel("A").resize((size, size), Image.LANCZOS)
    im = Image.new("RGBA", (size, size), color); im.putalpha(g); return im

def radial(size, c0, c1, cx=0.38, cy=0.32):
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
    d = np.clip(np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / 0.8, 0, 1)[..., None]
    a = np.array(c0, np.float32) * (1 - d) + np.array(c1, np.float32) * d
    return Image.fromarray(a.astype(np.uint8))

def tob(n):
    im = Image.new("RGBA", (Z, Z), (0, 0, 0, 0))
    disc = radial(Z, (246, 222, 150), (122, 84, 30))
    m = Image.new("L", (Z, Z), 0); d = ImageDraw.Draw(m); d.ellipse((6*SS, 6*SS, Z-6*SS, Z-6*SS), fill=255)
    im.paste(disc, (0, 0), m)
    d = ImageDraw.Draw(im)
    d.ellipse((6*SS, 6*SS, Z-6*SS, Z-6*SS), outline=(70, 44, 16, 255), width=4*SS)
    d.ellipse((20*SS, 20*SS, Z-20*SS, Z-20*SS), outline=(92, 60, 22, 230), width=2*SS)
    d.ellipse((25*SS, 25*SS, Z-25*SS, Z-25*SS), outline=(255, 236, 180, 140), width=1*SS)
    for a in range(0, 360, 45):  # rivets
        import math
        r = (Z / 2 - 13 * SS); cx = Z/2 + r*math.cos(math.radians(a)); cy = Z/2 + r*math.sin(math.radians(a))
        d.ellipse((cx-3*SS, cy-3*SS, cx+3*SS, cy+3*SS), fill=(90, 58, 20, 255))
        d.ellipse((cx-2*SS, cy-2.6*SS, cx+0.6*SS, cy-0.2*SS), fill=(255, 240, 190, 200))
    gs = int(Z * 0.56); gl = glyph("engraved", n, gs, (52, 30, 12, 255))
    im.alpha_composite(gl, ((Z-gs)//2, (Z-gs)//2))
    return im

def lounge(n):
    im = Image.new("RGBA", (Z, Z), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    def octo(i):
        k = Z * 0.27 - i; a, b = i, Z - i
        return [(a+k, a), (b-k, a), (b, a+k), (b, b-k), (b-k, b), (a+k, b), (a, b-k), (a, a+k)]
    d.polygon(octo(4*SS), fill=(14, 9, 8, 255))
    # fan rising from the bottom
    import math
    for i in range(13):
        ang = math.pi + math.pi * i / 12
        d.line((Z/2, Z*0.86, Z/2 + math.cos(ang)*Z*0.42, Z*0.86 + math.sin(ang)*Z*0.42), fill=(212, 175, 55, 46), width=2*SS)
    d.polygon(octo(4*SS), outline=(212, 175, 55, 255), width=4*SS)
    d.polygon(octo(14*SS), outline=(212, 175, 55, 170), width=1*SS)
    gs = int(Z * 0.5); gl = glyph("deco", n, gs, (236, 210, 140, 255))
    im.alpha_composite(gl, ((Z-gs)//2, int(Z*0.22)))
    return im

def journal(n):
    im = Image.new("RGBA", (Z, Z), (0, 0, 0, 0))
    paper = Image.open(f"{OUT}/paper_grain.jpg").convert("RGB").resize((Z, Z))
    tint = Image.new("RGB", (Z, Z), (214, 176, 118))
    paper = ImageChops.multiply(paper, tint)
    m = Image.new("L", (Z, Z), 0); d = ImageDraw.Draw(m)
    d.rounded_rectangle((8*SS, 8*SS, Z-8*SS, Z-8*SS), radius=16*SS, fill=255)
    m = m.filter(ImageFilter.GaussianBlur(0.8 * SS))
    im.paste(paper, (0, 0), m)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((8*SS, 8*SS, Z-8*SS, Z-8*SS), radius=16*SS, outline=(110, 70, 36, 255), width=2*SS)
    d.ellipse((30*SS, 30*SS, Z-30*SS, Z-30*SS), outline=(168, 50, 40, 220), width=4*SS)
    d.ellipse((40*SS, 40*SS, Z-40*SS, Z-40*SS), outline=(168, 50, 40, 150), width=2*SS)
    gs = int(Z * 0.46); gl = glyph("stamp", n, gs, (168, 50, 40, 235))
    im.alpha_composite(gl, ((Z-gs)//2, (Z-gs)//2))
    return im

for key, fn in (("tobacconist", tob), ("lounge", lounge), ("journal", journal)):
    od = f"{OUT}/art/{key}"; os.makedirs(od, exist_ok=True)
    for n in NAMES:
        fn(n).resize((S, S), Image.LANCZOS).save(f"{od}/{n}.png", optimize=True)
sheet = Image.new("RGB", (8*100, 3*100*1), (34, 20, 12))
for r, key in enumerate(("tobacconist", "lounge", "journal")):
    for i, n in enumerate(NAMES[:8]):
        sheet.paste(Image.open(f"{OUT}/art/{key}/{n}.png").resize((92, 92)), (i*100+4, r*100+4), Image.open(f"{OUT}/art/{key}/{n}.png").resize((92, 92)))
sheet.save(f"{OUT}/../art_sheet.png"); print("art ok")
