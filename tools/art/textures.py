"""Procedural materials for the pipe-lounge interfaces.

walnut  - quarter-sawn walnut (Tobacconist backdrop and drawer fronts)
leather - pebbled tobacco-brown leather (Blend Journal backdrop and spine)
kraft   - kraft paper (Blend Journal tags)
parch   - parchment (Tobacconist label cards and ledger)
lacquer - black lacquer with an ember glow and gold deco fan (Ember Lounge backdrop)

Usage: textures.py <out dir>
"""
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageFilter

RNG = np.random.default_rng(1928)


def noise(h, w, sy, sx, octaves=4, seed=None):
    """Fractal value noise in [0,1], feature size sy x sx pixels at the first octave."""
    rng = np.random.default_rng(seed) if seed is not None else RNG
    out = np.zeros((h, w), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        gh, gw = max(2, int(h / sy) + 2), max(2, int(w / sx) + 2)
        g = rng.random((gh, gw)).astype(np.float32)
        im = Image.fromarray((g * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        out += amp * (np.asarray(im, np.float32) / 255.0)
        tot += amp
        amp *= 0.5
        sy, sx = sy / 2, sx / 2
    return out / tot


def ramp(v, stops):
    """Map v in [0,1] through colour stops [(pos, (r,g,b)), ...]."""
    v = np.clip(v, 0, 1)
    out = np.zeros(v.shape + (3,), np.float32)
    pos = [s[0] for s in stops]
    for c in range(3):
        out[..., c] = np.interp(v, pos, [s[1][c] for s in stops])
    return out


def save(arr, path, q=88):
    im = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    if path.endswith(".png"):
        im.save(path, optimize=True)
    else:
        im.save(path, quality=q, optimize=True, progressive=True)
    return im


def walnut(h, w, dark=1.0):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    warp = noise(h, w, 900, 260, 3) * 2.2 + noise(h, w, 260, 120, 2) * 0.5
    rings = np.sin((x / 48.0 + warp * 3.2) * 2 * np.pi) * 0.7 + np.sin((x / 11.0 + warp * 7.0) * 2 * np.pi) * 0.3
    figure = noise(h, w, 420, 60, 4)
    pores = noise(h, w, 30, 1.6, 2)
    v = 0.42 + 0.20 * rings * (0.4 + figure) + 0.18 * (figure - 0.5) + 0.14 * (pores - 0.5)
    col = ramp(v, [(0.0, (26, 13, 7)), (0.35, (52, 28, 15)), (0.6, (84, 50, 28)), (0.85, (112, 70, 38)), (1.0, (138, 92, 52))])
    return col * dark


def vignette(h, w, strength=0.55, cx=0.5, cy=0.45):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt(((x / w - cx) * 1.3) ** 2 + ((y / h - cy) * 0.75) ** 2)
    return np.clip(1 - strength * d ** 1.6 * 2.2, 0.25, 1)[..., None]


def leather(h, w):
    pebble = noise(h, w, 9, 9, 2)
    pebble = np.abs(pebble - 0.5) * 2
    cells = (1 - pebble) ** 3
    broad = noise(h, w, 500, 500, 3)
    fine = noise(h, w, 3, 3, 1)
    v = 0.30 + 0.30 * broad + 0.16 * cells + 0.08 * (fine - 0.5)
    return ramp(v, [(0.0, (22, 12, 7)), (0.4, (48, 27, 15)), (0.65, (74, 43, 23)), (1.0, (110, 70, 40))])


def kraft(h, w):
    base = noise(h, w, 200, 200, 4)
    fib = noise(h, w, 2.0, 22, 2)
    fib2 = noise(h, w, 22, 2.0, 2)
    v = 0.5 + 0.22 * (base - 0.5) + 0.20 * (fib - 0.5) + 0.12 * (fib2 - 0.5)
    col = ramp(v, [(0.0, (150, 108, 62)), (0.5, (196, 152, 98)), (1.0, (226, 190, 136))])
    # scattered darker flecks
    fleck = (noise(h, w, 1.5, 1.5, 1) > 0.93)[..., None]
    return np.where(fleck, col * 0.72, col)


def parchment(h, w):
    blot = noise(h, w, 160, 160, 5)
    fine = noise(h, w, 2, 2, 1)
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    edge = np.minimum(np.minimum(x, w - 1 - x) / w, np.minimum(y, h - 1 - y) / h)
    v = 0.62 + 0.30 * (blot - 0.5) + 0.06 * (fine - 0.5) - np.clip(0.18 - edge, 0, 1) * 1.2
    return ramp(v, [(0.0, (150, 112, 66)), (0.45, (206, 176, 128)), (0.75, (232, 212, 168)), (1.0, (244, 230, 196))])


def render_svg(svg, width, out):
    tools = os.path.dirname(os.path.abspath(__file__))
    jf = out + ".json"
    json.dump([{"svg": svg, "out": out, "width": width}], open(jf, "w"))
    subprocess.run(["node", os.path.join(tools, "render.js"), jf], check=True, capture_output=True)
    os.remove(jf)
    return Image.open(out).convert("RGBA")


def lacquer(h, w, tmpdir):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    grain = noise(h, w, 400, 400, 4)
    base = ramp(0.3 + 0.2 * grain, [(0, (6, 4, 4)), (1, (20, 12, 10))])
    # ember glow rising from the bottom centre
    d = np.sqrt(((x - w / 2) / (w * 0.75)) ** 2 + ((y - h * 1.02) / (h * 0.55)) ** 2)
    glow = np.clip(1 - d, 0, 1) ** 2.2
    base += glow[..., None] * np.array([150, 28, 14], np.float32)
    # deco art: sunburst fan from the bottom, pinstripes, stepped crown
    rays = []
    cx, cy = w / 2, h * 1.0
    import math
    n = 36
    for i in range(n + 1):
        a = math.pi + math.pi * i / n
        x2, y2 = cx + math.cos(a) * h * 0.95, cy + math.sin(a) * h * 0.95
        rays.append(f'<line x1="{cx:.0f}" y1="{cy:.0f}" x2="{x2:.0f}" y2="{y2:.0f}"/>')
    rings = "".join(f'<circle cx="{cx:.0f}" cy="{cy:.0f}" r="{r}"/>' for r in (h * 0.22, h * 0.24, h * 0.46))
    p = w * 0.035
    stripes = "".join(f'<line x1="{p + k * w*0.012:.0f}" y1="0" x2="{p + k * w*0.012:.0f}" y2="{h}"/><line x1="{w - p - k * w*0.012:.0f}" y1="0" x2="{w - p - k * w*0.012:.0f}" y2="{h}"/>' for k in range(3))
    s = w * 0.05
    crown = f'<path d="M{p+w*0.04:.0f} {h*0.0:.0f} V{h*0.012:.0f} H{w/2 - 3*s:.0f} v{s*0.5:.0f} h{s:.0f} v{s*0.5:.0f} h{s:.0f} v{s*0.5:.0f} h{2*s:.0f} v-{s*0.5:.0f} h{s:.0f} v-{s*0.5:.0f} h{s:.0f} v-{s*0.5:.0f} H{w - p - w*0.04:.0f} V0"/>'
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
<g stroke="#D4AF37" fill="none">
<g stroke-width="{w*0.0022:.1f}" opacity="0.10">{"".join(rays)}</g>
<g stroke-width="{w*0.003:.1f}" opacity="0.22">{rings}</g>
<g stroke-width="{w*0.0028:.1f}" opacity="0.38">{stripes}</g>
<g stroke-width="{w*0.004:.1f}" opacity="0.30">{crown}</g>
</g></svg>'''
    art = render_svg(svg, w, os.path.join(tmpdir, "_lacquer_art.png"))
    a = np.asarray(art, np.float32)
    alpha = a[..., 3:4] / 255.0
    base = base * (1 - alpha) + a[..., :3] * alpha
    return base


def leaf_svg(w, h):
    """Botanical study of a Nicotiana tabacum leaf, drawn in thin sepia ink."""
    veins = []
    for i in range(9):
        t = 0.12 + i * 0.09
        yy = h * (0.18 + t * 0.7)
        span = (1 - abs(t - 0.5) * 1.5) * w * 0.30
        veins.append(f'<path d="M{w*0.5:.0f} {yy:.0f} q{-span*0.5:.0f} {-h*0.03:.0f} {-span:.0f} {-h*0.07:.0f}"/>')
        veins.append(f'<path d="M{w*0.5:.0f} {yy:.0f} q{span*0.5:.0f} {-h*0.03:.0f} {span:.0f} {-h*0.07:.0f}"/>')
    outline = (f'M{w*0.5:.0f} {h*0.1:.0f} C{w*0.86:.0f} {h*0.28:.0f} {w*0.9:.0f} {h*0.62:.0f} {w*0.52:.0f} {h*0.9:.0f} '
               f'C{w*0.1:.0f} {h*0.62:.0f} {w*0.14:.0f} {h*0.28:.0f} {w*0.5:.0f} {h*0.1:.0f}Z')
    return f'''<g fill="none" stroke-linecap="round"><path d="{outline}" stroke-width="{w*0.006:.1f}"/>
<path d="M{w*0.5:.0f} {h*0.1:.0f} Q{w*0.53:.0f} {h*0.5:.0f} {w*0.5:.0f} {h*0.98:.0f}" stroke-width="{w*0.008:.1f}"/>
<g stroke-width="{w*0.003:.1f}">{"".join(veins)}</g></g>'''


def journal_backdrop(h, w, tmpdir):
    base = leather(h, w) * vignette(h, w, 0.5)
    # faint botanical plate: two tobacco leaves and a flower spray
    lw, lh = w * 0.62, h * 0.48
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
<g stroke="#D9A441" opacity="0.13">
<g transform="translate({w*0.42:.0f} {h*0.36:.0f}) rotate(18) translate({-lw/2:.0f} {-lh/2:.0f})"><svg width="{lw:.0f}" height="{lh:.0f}">{leaf_svg(lw, lh)}</svg></g>
<g transform="translate({w*0.68:.0f} {h*0.74:.0f}) rotate(-24) scale(0.7) translate({-lw/2:.0f} {-lh/2:.0f})"><svg width="{lw:.0f}" height="{lh:.0f}">{leaf_svg(lw, lh)}</svg></g>
</g>
<g stroke="#C8A06A" stroke-width="{w*0.004:.1f}" stroke-dasharray="{w*0.018:.0f} {w*0.012:.0f}" fill="none" opacity="0.45">
<rect x="{w*0.03:.0f}" y="{w*0.03:.0f}" width="{w*0.94:.0f}" height="{h - w*0.06:.0f}" rx="{w*0.03:.0f}"/></g>
</svg>'''
    art = render_svg(svg, w, os.path.join(tmpdir, "_journal_art.png"))
    a = np.asarray(art, np.float32)
    alpha = a[..., 3:4] / 255.0
    return base * (1 - alpha) + a[..., :3] * alpha


def tob_backdrop(h, w):
    wood = walnut(h, w, 0.82) * vignette(h, w, 0.6)
    # raised-panel mouldings: a lighter bevel line and a dark shadow line, inset
    img = wood.copy()
    for inset, tone in ((0.035, 1.35), (0.042, 0.55)):
        i0 = int(w * inset)
        t = max(2, int(w * 0.003))
        for (y0, y1, x0, x1) in ((i0, i0 + t, i0, w - i0), (h - i0 - t, h - i0, i0, w - i0), (i0, h - i0, i0, i0 + t), (i0, h - i0, w - i0 - t, w - i0)):
            img[y0:y1, x0:x1] *= tone
    return img


def main(out):
    os.makedirs(out, exist_ok=True)
    tmp = out
    H, W = 2400, 1080
    save(tob_backdrop(H, W), f"{out}/tob_backdrop.jpg")
    save(lacquer(H, W, tmp), f"{out}/lounge_backdrop.jpg")
    save(journal_backdrop(H, W, tmp), f"{out}/journal_backdrop.jpg")
    for f in ("_lacquer_art.png", "_journal_art.png"):
        p = os.path.join(tmp, f)
        if os.path.exists(p):
            os.remove(p)
    # tiles drawn with MIRROR tiling, so they need no seams
    save(walnut(384, 384, 1.0), f"{out}/walnut.jpg", 90)
    save(leather(320, 320), f"{out}/leather.jpg", 90)
    save(kraft(320, 320), f"{out}/kraft.jpg", 90)
    save(parchment(384, 384), f"{out}/parchment.jpg", 90)
    print("textures ok")


if __name__ == "__main__":
    main(sys.argv[1])
