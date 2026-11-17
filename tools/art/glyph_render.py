"""Renders the pictogram sets.

Usage:
    python glyph_render.py <outroot>            # <outroot>/{engraved,deco,stamp,trekui}/<name>.png
    python glyph_render.py --assets <assetsdir> # straight into app/src/main/assets layout:
                                                #   pipe/glyphs/{engraved,deco,stamp}/ and glyphs/

The core 28 (glyphs.G) get the three pipe styles; the extended set (glyphs_extra.E, LCA-T22) gets
the three pipe styles plus a bold "trekui" stroke style. The plain TrekUI set for the core 28 still
comes from tools/legacy/make_glyphs.py, so "trekui" is never rendered for those names (it would
overwrite the hand-tuned solid silhouettes).

Needs node with @resvg/resvg-js (npm install --prefix tools/art; the setup scripts do this) and Pillow.
"""
import json, re, subprocess, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from glyphs import G
from glyphs_extra import E
from PIL import Image, ImageFilter, ImageChops
import random

STYLES = {"engraved": 7, "deco": 11, "stamp": 14, "trekui": 15}
ALL = dict(G)
ALL.update(E)


def names_for(style):
    """Glyph names rendered in a style: everything for the pipe styles, only the extended set for trekui."""
    return list(E) if style == "trekui" else list(ALL)


def svg_for(name, w):
    body = ALL[name]
    body = re.sub(r'style="stroke-width:calc\(var\(--w\)\*([0-9.]+)\)"', lambda m: f'stroke-width="{w*float(m.group(1)):.1f}"', body)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192" width="192" height="192">
<style>* {{ fill:none; stroke:#fff; stroke-width:{w}; stroke-linecap:round; stroke-linejoin:round }} .f {{ fill:#fff }}</style>
<g>{body}</g></svg>'''


def out_dir(outroot, style, assets):
    if not assets:
        return os.path.join(outroot, style)
    return os.path.join(outroot, "glyphs") if style == "trekui" else os.path.join(outroot, "pipe", "glyphs", style)


def main(outroot, assets=False):
    jobs = []
    tmp = os.path.join(outroot if not assets else os.environ.get("TMP", outroot), "_glyph_tmp"); os.makedirs(tmp, exist_ok=True)
    for st, w in STYLES.items():
        for n in names_for(st):
            jobs.append({"svg": svg_for(n, w), "out": f"{tmp}/{st}_{n}.png", "width": 192})
    jf = os.path.join(tmp, "jobs.json"); json.dump(jobs, open(jf, "w"))
    subprocess.run(["node", os.path.join(os.path.dirname(__file__), "render.js"), jf], check=True)
    rnd = random.Random(7)
    for st in STYLES:
        od = out_dir(outroot, st, assets); os.makedirs(od, exist_ok=True)
        for n in names_for(st):
            im = Image.open(f"{tmp}/{st}_{n}.png").convert("RGBA")
            a = im.getchannel("A")
            if st == "engraved":
                # fine hatching inside filled marks, like a burin cut
                hatch = Image.new("L", a.size, 255)
                px = hatch.load()
                for y in range(a.size[1]):
                    for x in range(a.size[0]):
                        if (x + y) % 7 == 0: px[x, y] = 120
                a = ImageChops.multiply(a, hatch)
            elif st == "stamp":
                # worn rubber stamp: speckled ink loss
                noise = Image.effect_noise(a.size, 90).filter(ImageFilter.GaussianBlur(1.2))
                mask = noise.point(lambda v: 0 if v < 70 else 255)
                a = ImageChops.multiply(a, mask)
                a = a.filter(ImageFilter.GaussianBlur(0.6))
            out = Image.new("RGBA", a.size, (255, 255, 255, 0)); out.putalpha(a)
            out.save(os.path.join(od, n + ".png"), optimize=True)
    print("done")


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--assets":
        main(sys.argv[2], assets=True)
    else:
        main(sys.argv[1])
